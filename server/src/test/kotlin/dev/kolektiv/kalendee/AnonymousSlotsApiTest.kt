package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AvailabilityWindowBody
import dev.kolektiv.kalendee.api.PublicLinkBody
import dev.kolektiv.kalendee.api.UpdateAvailabilityBody
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.web.CalendarSharingOut
import dev.kolektiv.kalendee.web.CalendarSlotsOut
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class AnonymousSlotsApiTest {
    @Test
    fun anonymousViewerReadsSlotsOnPublicCalendar() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")
        alice.setAvailability(calendar.id.value, accessMode = "public")
        alice.enablePublicLink(calendar.id.value)
        alice.createEvent(
            calendar.id.value,
            "Standup",
            Instant.parse("2030-01-07T10:00:00Z"),
            Instant.parse("2030-01-07T11:00:00Z"),
        )

        val response = jsonClient().get("/api/v1/calendars/${calendar.id.value}/slots?from=$monday&to=$monday")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val anonymous = response.body<CalendarSlotsOut>()
        assertEquals(calendar.id.value, anonymous.calendarId)
        assertEquals("UTC", anonymous.timeZone)
        assertTrue(anonymous.requestsEnabled)
        val day = anonymous.days.single()
        assertEquals(8, day.slots.size)
        assertFalse(day.slots.single { it.start == "2030-01-07T10:00:00Z" }.available)
        assertEquals(7, day.slots.count { it.available })

        val owner = alice.get("/api/v1/calendars/${calendar.id.value}/slots?from=$monday&to=$monday")
            .body<CalendarSlotsOut>()
        assertEquals(owner, anonymous)
    }

    @Test
    fun anonymousSlotsDeniedWhenAccessDenies() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Private")

        assertEquals(
            HttpStatusCode.NotFound,
            jsonClient().get("/api/v1/calendars/${calendar.id.value}/slots?from=$monday&to=$monday").status,
        )

        alice.setAvailability(calendar.id.value, accessMode = "signed_in")
        alice.enablePublicLink(calendar.id.value)

        assertEquals(
            HttpStatusCode.NotFound,
            jsonClient().get("/api/v1/calendars/${calendar.id.value}/slots?from=$monday&to=$monday").status,
        )

        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val signedIn = bob.get("/api/v1/calendars/${calendar.id.value}/slots?from=$monday&to=$monday")
        assertEquals(HttpStatusCode.OK, signedIn.status, signedIn.bodyAsText())
        assertEquals(8, signedIn.body<CalendarSlotsOut>().days.single().slots.size)
    }

    @Test
    fun otherCalendarRoutesStillRequireSession() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")
        val anonymous = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/v1/calendars").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            anonymous.get("/api/v1/calendars/${calendar.id.value}/availability").status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            anonymous.put("/api/v1/calendars/${calendar.id.value}/availability") {
                contentType(ContentType.Application.Json)
                setBody("""{"requestsEnabled":true,"slotMinutes":60,"accessMode":"public","windows":[]}""")
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            anonymous.get("/api/v1/calendars/${calendar.id.value}/slot-requests").status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            anonymous.post("/api/v1/calendars/${calendar.id.value}/slot-requests") {
                contentType(ContentType.Application.Json)
                setBody("""{"start":"2030-01-07T09:00:00Z","end":"2030-01-07T10:00:00Z"}""")
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            anonymous.get("/api/v1/calendars/${calendar.id.value}/slots/extra").status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            anonymous.put("/api/v1/calendars/${calendar.id.value}/slots").status,
        )
    }
}

private const val monday = "2030-01-07"

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
) {
    val response = post("/api/v1/calendars/$calendarId/events") {
        contentType(ContentType.Application.Json)
        setBody(CreateEvent(title = title, start = start, end = end))
    }
    check(response.status == HttpStatusCode.Created) { "create event failed: ${response.status}" }
}

private suspend fun HttpClient.setAvailability(calendarId: String, accessMode: String) {
    val response = put("/api/v1/calendars/$calendarId/availability") {
        contentType(ContentType.Application.Json)
        setBody(
            UpdateAvailabilityBody(
                requestsEnabled = true,
                slotMinutes = 60,
                accessMode = accessMode,
                windows = (0..4).map { AvailabilityWindowBody(it, 9 * 60, 17 * 60) },
            ),
        )
    }
    check(response.status == HttpStatusCode.OK) { "update availability failed: ${response.status}" }
}

private suspend fun HttpClient.enablePublicLink(calendarId: String): String {
    val response = put("/api/v1/calendars/$calendarId/public") {
        contentType(ContentType.Application.Json)
        setBody(PublicLinkBody(enabled = true))
    }
    check(response.status == HttpStatusCode.OK) { "enable public link failed: ${response.status}" }
    return response.body<CalendarSharingOut>().publicLinkToken ?: error("no public token")
}
