package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.web.CalendarRsvpSettingsOut
import dev.kolektiv.kalendee.web.EventSummary
import dev.kolektiv.kalendee.web.HomePage
import dev.kolektiv.keel.Keel
import dev.kolektiv.keel.KeelJson
import dev.kolektiv.keel.seed.KeelSeed
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class RsvpSettingsApiTest {
    @Test
    fun ownerReadsAndUpdatesCalendarRsvpSettings() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")

        val initial = alice.get("/api/v1/calendars/${calendar.id.value}/rsvp-settings")
        assertEquals(HttpStatusCode.OK, initial.status)
        val before = initial.body<CalendarRsvpSettingsOut>()
        assertFalse(before.rsvpEnabled)
        assertFalse(before.anonymousRsvpEnabled)

        val update = alice.setCalendarRsvp(
            calendar.id.value,
            rsvpEnabled = true,
            anonymousRsvpEnabled = true,
        )
        assertEquals(HttpStatusCode.OK, update.status)
        val after = update.body<CalendarRsvpSettingsOut>()
        assertTrue(after.rsvpEnabled)
        assertTrue(after.anonymousRsvpEnabled)

        val fetched = alice.get("/api/v1/calendars/${calendar.id.value}/rsvp-settings")
            .body<CalendarRsvpSettingsOut>()
        assertEquals(after, fetched)

        val listed = alice.get("/api/v1/calendars").body<List<Calendar>>()
            .single { it.id == calendar.id }
        assertTrue(listed.rsvpEnabled)
        assertTrue(listed.anonymousRsvpEnabled)

        val seed = KeelJson.codec.decodeFromString(
            KeelSeed.serializer(),
            alice.get("/") { header(KeelHeaders.VISIT, "true") }.bodyAsText(),
        )
        val home = KeelJson.codec.decodeFromJsonElement(HomePage.serializer(), seed.data)
        val summary = home.calendars.single { it.id == calendar.id.value }
        assertTrue(summary.rsvpEnabled)
        assertTrue(summary.anonymousRsvpEnabled)
    }

    @Test
    fun calendarRsvpSettingsAreOwnerOnly() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val carol = jsonClient()
        carol.registerAndLogin("carol")
        val dave = jsonClient()
        dave.registerAndLogin("dave")

        val calendar = alice.createCalendar("Work")
        alice.share(calendar.id.value, "bob", "read")
        alice.share(calendar.id.value, "carol", "write")

        assertEquals(
            HttpStatusCode.Forbidden,
            bob.get("/api/v1/calendars/${calendar.id.value}/rsvp-settings").status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            bob.setCalendarRsvp(calendar.id.value, rsvpEnabled = true).status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            carol.setCalendarRsvp(calendar.id.value, rsvpEnabled = true).status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            dave.get("/api/v1/calendars/${calendar.id.value}/rsvp-settings").status,
        )
        assertEquals(
            HttpStatusCode.OK,
            alice.setCalendarRsvp(calendar.id.value, rsvpEnabled = true).status,
        )
    }

    @Test
    fun eventRsvpOverridesRoundTripAndComputeEffectiveFlags() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")
        val event = alice.createEvent(calendar.id.value, "Planning", EventStart, EventEnd)
        assertEquals(
            HttpStatusCode.OK,
            alice.setCalendarRsvp(
                calendar.id.value,
                rsvpEnabled = true,
                anonymousRsvpEnabled = true,
            ).status,
        )

        val updated = alice.setEventRsvpOverrides(
            event.id.value,
            rsvpOverride = false,
            anonymousRsvpOverride = false,
        )
        assertEquals(HttpStatusCode.OK, updated.status)
        val summary = updated.body<EventSummary>()
        assertEquals(false, summary.rsvpOverride)
        assertEquals(false, summary.anonymousRsvpOverride)
        assertFalse(summary.rsvpEnabled)
        assertFalse(summary.openRsvp)

        val fetched = alice.get("/api/v1/events/${event.id.value}").body<Event>()
        assertEquals(false, fetched.rsvpOverride)
        assertEquals(false, fetched.anonymousRsvpOverride)
        assertFalse(fetched.rsvpEnabled)
        assertFalse(fetched.openRsvp)

        val cleared = alice.setEventRsvpOverrides(event.id.value)
        assertEquals(HttpStatusCode.OK, cleared.status)
        val clearedSummary = cleared.body<EventSummary>()
        assertNull(clearedSummary.rsvpOverride)
        assertNull(clearedSummary.anonymousRsvpOverride)
        assertTrue(clearedSummary.rsvpEnabled)
        assertTrue(clearedSummary.openRsvp)
    }

    @Test
    fun keelRsvpSettingsActionsRoundTrip() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")
        val event = alice.createEvent(calendar.id.value, "Planning", EventStart, EventEnd)

        val updated = alice.post("${Keel.ACTION_PATH}/kalendee.updateCalendarRsvpSettings") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"calendarId":"${calendar.id.value}","rsvpEnabled":true,"anonymousRsvpEnabled":false}""",
            )
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        val settings = updated.actionData<CalendarRsvpSettingsOut>()
        assertEquals(calendar.id.value, settings.calendarId)
        assertTrue(settings.rsvpEnabled)
        assertFalse(settings.anonymousRsvpEnabled)

        val read = alice.post("${Keel.ACTION_PATH}/kalendee.calendarRsvpSettings") {
            contentType(ContentType.Application.Json)
            setBody("""{"calendarId":"${calendar.id.value}"}""")
        }
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(settings, read.actionData<CalendarRsvpSettingsOut>())

        val overrides = alice.post("${Keel.ACTION_PATH}/kalendee.setEventRsvpOverrides") {
            contentType(ContentType.Application.Json)
            setBody("""{"eventId":"${event.id.value}","anonymousRsvpOverride":true}""")
        }
        assertEquals(HttpStatusCode.OK, overrides.status)
        val summary = overrides.actionData<EventSummary>()
        assertNull(summary.rsvpOverride)
        assertEquals(true, summary.anonymousRsvpOverride)
        assertTrue(summary.rsvpEnabled)
        assertTrue(summary.openRsvp)
    }

    private companion object {
        val EventStart: Instant = Instant.parse("2026-09-07T14:00:00Z")
        val EventEnd: Instant = Instant.parse("2026-09-07T15:00:00Z")
    }
}

private suspend inline fun <reified T> HttpResponse.actionData(): T =
    KeelJson.codec.decodeFromJsonElement(
        KeelJson.codec.parseToJsonElement(bodyAsText()).jsonObject.getValue("data"),
    )

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
): Event {
    val response = post("/api/v1/calendars/$calendarId/events") {
        contentType(ContentType.Application.Json)
        setBody(CreateEvent(title = title, start = start, end = end))
    }
    check(response.status == HttpStatusCode.Created) { "create event failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.share(calendarId: String, username: String, permission: String) {
    val response = post("/api/v1/calendars/$calendarId/shares") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username","permission":"$permission"}""")
    }
    check(response.status == HttpStatusCode.Created) { "share failed: ${response.status}" }
}

private suspend fun HttpClient.setCalendarRsvp(
    calendarId: String,
    rsvpEnabled: Boolean = false,
    anonymousRsvpEnabled: Boolean = false,
): HttpResponse = put("/api/v1/calendars/$calendarId/rsvp-settings") {
    contentType(ContentType.Application.Json)
    setBody(
        buildJsonObject {
            put("rsvpEnabled", rsvpEnabled)
            put("anonymousRsvpEnabled", anonymousRsvpEnabled)
        }.toString(),
    )
}

private suspend fun HttpClient.setEventRsvpOverrides(
    eventId: String,
    rsvpOverride: Boolean? = null,
    anonymousRsvpOverride: Boolean? = null,
): HttpResponse = put("/api/v1/events/$eventId/rsvp-settings") {
    contentType(ContentType.Application.Json)
    setBody(
        buildJsonObject {
            if (rsvpOverride != null) put("rsvpOverride", rsvpOverride)
            if (anonymousRsvpOverride != null) put("anonymousRsvpOverride", anonymousRsvpOverride)
        }.toString(),
    )
}
