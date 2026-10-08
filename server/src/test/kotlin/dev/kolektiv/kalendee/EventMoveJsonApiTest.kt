package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.api.MoveEventBody
import dev.kolektiv.kalendee.api.MoveEventResponse
import dev.kolektiv.kalendee.api.ShareCalendarBody
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.Recurrence
import dev.kolektiv.kalendee.calendar.RecurrenceFrequency
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class EventMoveJsonApiTest {
    @Test
    fun movesWholeSeriesToAnotherCalendar() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val source = alice.createMoveCalendar("Source")
        val destination = alice.createMoveCalendar("Destination")
        val event = alice.createMoveEvent(source.id.value, "Standup", start, end)

        val response = alice.moveEventJson(event.id.value, destination.id.value, etag = event.etag)

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val moved = response.body<MoveEventResponse>().events.single()
        assertEquals(event.id.value, moved.id.value)
        assertEquals(destination.id.value, moved.calendarId.value)
        assertNotEquals(event.etag, moved.etag)
        assertTrue(alice.listMoveEvents(source.id.value).isEmpty())
        assertEquals(listOf(event.id), alice.listMoveEvents(destination.id.value).map { it.id })
    }

    @Test
    fun movingFollowingOccurrencesSplitsTheSeries() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val source = alice.createMoveCalendar("Source")
        val destination = alice.createMoveCalendar("Destination")
        val event = alice.createMoveEvent(
            source.id.value,
            "Daily",
            start,
            end,
            recurrence = Recurrence(frequency = RecurrenceFrequency.DAILY, interval = 1, count = 5),
        )
        val occurrences = alice.listMoveEvents(source.id.value, rangeFrom, rangeTo).map { it.start }
        assertEquals(5, occurrences.size)
        val from = occurrences[2]

        val response = alice.moveEventJson(
            event.id.value,
            destination.id.value,
            from = from.toString(),
            etag = event.etag,
        )

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val moved = response.body<MoveEventResponse>().events
        assertEquals(2, moved.size)
        assertEquals(event.id.value, moved[0].id.value)
        assertEquals(source.id.value, moved[0].calendarId.value)
        assertEquals(2, moved[0].recurrence?.count ?: 0)
        assertEquals(destination.id.value, moved[1].calendarId.value)
        assertEquals(from, moved[1].start)
        assertEquals(3, moved[1].recurrence?.count ?: 0)
    }

    @Test
    fun etagMismatchIsPreconditionFailed() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val source = alice.createMoveCalendar("Source")
        val destination = alice.createMoveCalendar("Destination")
        val event = alice.createMoveEvent(source.id.value, "Standup", start, end)

        val response = alice.moveEventJson(event.id.value, destination.id.value, etag = "stale-etag")

        assertEquals(HttpStatusCode.PreconditionFailed, response.status)
        assertEquals("precondition_failed", response.body<ErrorBody>().error)
        assertEquals(listOf(event.id), alice.listMoveEvents(source.id.value).map { it.id })
    }

    @Test
    fun moveRequiresSessionAndCalendarWriteAccess() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val source = alice.createMoveCalendar("Source")
        val destination = alice.createMoveCalendar("Destination")
        val event = alice.createMoveEvent(source.id.value, "Standup", start, end)

        val anonymous = jsonClient().moveEventJson(event.id.value, destination.id.value)
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

        val stranger = bob.moveEventJson(event.id.value, destination.id.value)
        assertEquals(HttpStatusCode.NotFound, stranger.status)

        alice.shareMoveCalendar(source.id.value, "bob", "read")
        val readOnly = bob.moveEventJson(event.id.value, destination.id.value, etag = event.etag)
        assertEquals(HttpStatusCode.Forbidden, readOnly.status)
    }

    @Test
    fun moveValidationErrorsUseErrorBody() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val source = alice.createMoveCalendar("Source")
        val destination = alice.createMoveCalendar("Destination")
        val event = alice.createMoveEvent(source.id.value, "Standup", start, end)

        val badScope = alice.moveEventJson(event.id.value, destination.id.value, scope = "single")
        assertEquals(HttpStatusCode.BadRequest, badScope.status)
        assertTrue(badScope.body<ErrorBody>().message.contains("scope"))

        val badFrom = alice.moveEventJson(event.id.value, destination.id.value, from = "not-a-date")
        assertEquals(HttpStatusCode.BadRequest, badFrom.status)
        assertTrue(badFrom.body<ErrorBody>().message.contains("invalid from"))

        val badCalendar = alice.moveEventJson(event.id.value, "not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, badCalendar.status)

        val sameCalendar = alice.moveEventJson(event.id.value, source.id.value)
        assertEquals(HttpStatusCode.BadRequest, sameCalendar.status)

        val unknownEvent = alice.moveEventJson(Uuid.random().toString(), destination.id.value)
        assertEquals(HttpStatusCode.NotFound, unknownEvent.status)

        assertEquals(listOf(event.id), alice.listMoveEvents(source.id.value).map { it.id })
        assertNull(alice.listMoveEvents(destination.id.value).firstOrNull())
    }

    private companion object {
        val start: Instant = Instant.parse("2026-09-07T10:00:00Z")
        val end: Instant = Instant.parse("2026-09-07T10:30:00Z")
        const val rangeFrom = "2026-09-01T00:00:00Z"
        const val rangeTo = "2026-10-01T00:00:00Z"
    }
}

private suspend fun HttpClient.createMoveCalendar(name: String): Calendar {
    val response = post("/api/v1/calendars") {
        contentType(ContentType.Application.Json)
        setBody(CreateCalendar(displayName = name))
    }
    check(response.status == HttpStatusCode.Created) { "create calendar failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.createMoveEvent(
    calendarId: String,
    title: String,
    start: Instant,
    end: Instant,
    recurrence: Recurrence? = null,
): Event {
    val response = post("/api/v1/calendars/$calendarId/events") {
        contentType(ContentType.Application.Json)
        setBody(CreateEvent(title = title, start = start, end = end, recurrence = recurrence))
    }
    check(response.status == HttpStatusCode.Created) { "create event failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.listMoveEvents(
    calendarId: String,
    from: String? = null,
    to: String? = null,
): List<Event> {
    val query = if (from != null && to != null) "?from=$from&to=$to" else ""
    val response = get("/api/v1/calendars/$calendarId/events$query")
    check(response.status == HttpStatusCode.OK) { "list events failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.shareMoveCalendar(calendarId: String, username: String, permission: String) {
    val response = post("/api/v1/calendars/$calendarId/shares") {
        contentType(ContentType.Application.Json)
        setBody(ShareCalendarBody(username = username, permission = permission))
    }
    check(response.status == HttpStatusCode.Created) { "share failed: ${response.status}" }
}

private suspend fun HttpClient.moveEventJson(
    id: String,
    calendarId: String,
    scope: String = "following",
    from: String? = null,
    etag: String? = null,
): HttpResponse = post("/api/v1/events/$id/move") {
    contentType(ContentType.Application.Json)
    setBody(MoveEventBody(calendarId = calendarId, scope = scope, from = from, etag = etag))
}
