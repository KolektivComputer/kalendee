package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.ShareCalendarBody
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.Recurrence
import dev.kolektiv.kalendee.calendar.RecurrenceFrequency
import dev.kolektiv.kalendee.web.MoveEventOut
import dev.kolektiv.keel.Keel
import dev.kolektiv.keel.KeelJson
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
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.jsonObject

class MoveEventApiTest {
    @Test
    fun ownerMovesEventBetweenOwnedCalendars() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val source = alice.createCalendar("Source")
        val destination = alice.createCalendar("Destination")
        val event = alice.createEvent(source.id.value, "Standup", start, end)

        val response = alice.moveEvent(event.id.value, destination.id.value, etag = event.etag)

        assertEquals(HttpStatusCode.OK, response.status)
        val moved = response.moveEventOut()
        val movedEvent = moved.events.single()
        assertEquals(event.id.value, movedEvent.id)
        assertEquals(destination.id.value, movedEvent.calendarId)
        assertNotEquals(event.etag, movedEvent.etag)

        assertTrue(alice.listCalendarEvents(source.id.value).isEmpty())
        assertEquals(listOf(event.id), alice.listCalendarEvents(destination.id.value).map { it.id })
    }

    @Test
    fun movingFollowingOccurrencesSplitsTheSeries() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val source = alice.createCalendar("Source")
        val destination = alice.createCalendar("Destination")
        val event = alice.createEvent(
            source.id.value,
            "Daily",
            start,
            end,
            recurrence = Recurrence(frequency = RecurrenceFrequency.DAILY, interval = 1, count = 5),
        )
        val original = alice.listCalendarEvents(source.id.value, rangeFrom, rangeTo).map { it.start }
        assertEquals(5, original.size)
        val from = original[2]

        val response = alice.moveEvent(
            event.id.value,
            destination.id.value,
            from = from.toString(),
            etag = event.etag,
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val moved = response.moveEventOut()
        assertEquals(2, moved.events.size)
        val truncated = moved.events[0]
        val following = moved.events[1]
        assertEquals(event.id.value, truncated.id)
        assertEquals(source.id.value, truncated.calendarId)
        assertEquals(2, truncated.recurrence?.count)
        assertEquals(destination.id.value, following.calendarId)
        assertEquals(from.toString(), following.start)
        assertEquals(3, following.recurrence?.count)
        assertNotEquals(event.id.value, following.id)

        val sourceStarts = alice.listCalendarEvents(source.id.value, rangeFrom, rangeTo).map { it.start }
        assertEquals(2, sourceStarts.size)
        assertEquals(original.take(2), sourceStarts)
        val destinationStarts = alice.listCalendarEvents(destination.id.value, rangeFrom, rangeTo).map { it.start }
        assertEquals(3, destinationStarts.size)
        assertEquals(from, destinationStarts.first())
        assertEquals(original, (sourceStarts + destinationStarts).sorted())
    }

    @Test
    fun writeSharesOnBothCalendarsAllowMoveWithoutOwnership() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val source = alice.createCalendar("Source")
        val destination = alice.createCalendar("Destination")
        alice.share(source.id.value, "bob", "write")
        alice.share(destination.id.value, "bob", "write")
        val event = alice.createEvent(source.id.value, "Standup", start, end)

        val response = bob.moveEvent(event.id.value, destination.id.value, etag = event.etag)

        assertEquals(HttpStatusCode.OK, response.status)
        val moved = response.moveEventOut()
        assertEquals(destination.id.value, moved.events.single().calendarId)
        assertTrue(alice.listCalendarEvents(source.id.value).isEmpty())
        assertEquals(listOf(event.id), alice.listCalendarEvents(destination.id.value).map { it.id })
    }

    @Test
    fun perOccurrenceScopeIsRejectedWithScopeFieldError() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val source = alice.createCalendar("Source")
        val destination = alice.createCalendar("Destination")
        val event = alice.createEvent(source.id.value, "Standup", start, end)

        val response = alice.moveEvent(event.id.value, destination.id.value, scope = "single", etag = event.etag)

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(response.bodyAsText().contains("\"scope\""))
        assertEquals(event.id, alice.listCalendarEvents(source.id.value).single().id)
    }

    @Test
    fun invalidFromAndSameCalendarReportTheirFields() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val source = alice.createCalendar("Source")
        val destination = alice.createCalendar("Destination")
        val event = alice.createEvent(source.id.value, "Standup", start, end)

        val invalidFrom = alice.moveEvent(event.id.value, destination.id.value, from = "not-a-date", etag = event.etag)
        assertEquals(HttpStatusCode.UnprocessableEntity, invalidFrom.status)
        assertTrue(invalidFrom.bodyAsText().contains("\"from\""))

        val sameCalendar = alice.moveEvent(event.id.value, source.id.value, etag = event.etag)
        assertEquals(HttpStatusCode.UnprocessableEntity, sameCalendar.status)
        assertTrue(sameCalendar.bodyAsText().contains("\"calendarId\""))
    }

    private companion object {
        val start: Instant = Instant.parse("2026-09-07T10:00:00Z")
        val end: Instant = Instant.parse("2026-09-07T10:30:00Z")
        const val rangeFrom = "2026-09-01T00:00:00Z"
        const val rangeTo = "2026-10-01T00:00:00Z"
    }
}

private suspend fun HttpClient.createCalendar(name: String): Calendar {
    val response = post("/api/v1/calendars") {
        contentType(ContentType.Application.Json)
        setBody(CreateCalendar(displayName = name))
    }
    check(response.status == HttpStatusCode.Created) { "create calendar failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.createEvent(
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

private suspend fun HttpClient.listCalendarEvents(
    calendarId: String,
    from: String? = null,
    to: String? = null,
): List<Event> {
    val query = when {
        from != null && to != null -> "?from=$from&to=$to"
        else -> ""
    }
    val response = get("/api/v1/calendars/$calendarId/events$query")
    check(response.status == HttpStatusCode.OK) { "list events failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.share(calendarId: String, username: String, permission: String) {
    val response = post("/api/v1/calendars/$calendarId/shares") {
        contentType(ContentType.Application.Json)
        setBody(ShareCalendarBody(username = username, permission = permission))
    }
    check(response.status == HttpStatusCode.Created) { "share calendar failed: ${response.status}" }
}

private suspend fun HttpClient.moveEvent(
    id: String,
    calendarId: String,
    scope: String = "following",
    from: String? = null,
    etag: String? = null,
): HttpResponse {
    val fromJson = from?.let { "\"$it\"" } ?: "null"
    val etagJson = etag?.let { "\"$it\"" } ?: "null"
    return post("${Keel.ACTION_PATH}/kalendee.moveEvent") {
        contentType(ContentType.Application.Json)
        setBody("""{"id":"$id","calendarId":"$calendarId","scope":"$scope","from":$fromJson,"etag":$etagJson}""")
    }
}

private suspend fun HttpResponse.moveEventOut(): MoveEventOut {
    val root = KeelJson.codec.parseToJsonElement(bodyAsText()).jsonObject
    return KeelJson.codec.decodeFromJsonElement(MoveEventOut.serializer(), root.getValue("data"))
}
