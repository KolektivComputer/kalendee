package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.web.RsvpPage
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
import kotlin.uuid.Uuid
import kotlinx.serialization.json.decodeFromJsonElement

class RsvpPageTest {
    @Test
    fun tokenInviteRendersValidPage() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        val alice = jsonClient()
        alice.registerWithEmail("alice", "alice@example.com")
        val calendar = alice.createCalendar("Work")
        val event = alice.createEvent(calendar.id.value, "Offsite", EventStart, EventEnd)
        mail.clear()

        val invited = alice.post("/api/v1/events/${event.id.value}/attendees") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"guest@example.com","name":"Guest"}""")
        }
        assertEquals(HttpStatusCode.Created, invited.status)
        val inviteMail = mail.sent.single { it.to == "guest@example.com" }
        val token = RsvpLinkPattern.find(inviteMail.text)?.groupValues?.get(2)
            ?: error("no rsvp link in invite mail: ${inviteMail.text}")

        val response = jsonClient().get("/rsvp/${event.id.value}?token=$token") {
            header(KeelHeaders.VISIT, "true")
        }
        val page = response.rsvpPage()
        assertTrue(page.valid)
        assertEquals(event.id.value, page.eventId)
        assertEquals(token, page.token)
        assertEquals("Offsite", page.title)
        assertEquals("Work", page.calendarName)
        assertEquals("invited", page.status)
        assertTrue(page.whenText?.contains("2026-09-07") == true)
        assertFalse(page.requiresName)
        assertNull(page.viewer)
    }

    @Test
    fun openRsvpPageRequiresNameForAnonymous() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")
        val event = alice.createEvent(calendar.id.value, "Open house", EventStart, EventEnd)
        assertEquals(HttpStatusCode.OK, alice.enableOpenRsvp(event.id.value))

        val anonymous = jsonClient().get("/rsvp/${event.id.value}") {
            header(KeelHeaders.VISIT, "true")
        }.rsvpPage()
        assertTrue(anonymous.valid)
        assertTrue(anonymous.requiresName)
        assertNull(anonymous.viewer)
        assertNull(anonymous.token)
        assertEquals("Open house", anonymous.title)
        assertEquals("Work", anonymous.calendarName)
        assertTrue(anonymous.whenText?.contains("2026-09-07") == true)

        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val signedIn = bob.get("/rsvp/${event.id.value}") {
            header(KeelHeaders.VISIT, "true")
        }.rsvpPage()
        assertTrue(signedIn.valid)
        assertFalse(signedIn.requiresName)
        assertEquals("bob", signedIn.viewer?.username)
    }

    @Test
    fun invalidLinksRenderInvalidPage() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Private")
        val event = alice.createEvent(calendar.id.value, "Locked", EventStart, EventEnd)

        val anonymous = jsonClient()
        val closed = anonymous.get("/rsvp/${event.id.value}") {
            header(KeelHeaders.VISIT, "true")
        }.rsvpPage()
        assertFalse(closed.valid)
        assertNull(closed.title)

        val badToken = anonymous.get("/rsvp/${event.id.value}?token=not-a-real-token") {
            header(KeelHeaders.VISIT, "true")
        }.rsvpPage()
        assertFalse(badToken.valid)
        assertEquals("not-a-real-token", badToken.token)

        val unknown = anonymous.get("/rsvp/${Uuid.random()}") {
            header(KeelHeaders.VISIT, "true")
        }.rsvpPage()
        assertFalse(unknown.valid)

        val garbage = anonymous.get("/rsvp/not-a-uuid") {
            header(KeelHeaders.VISIT, "true")
        }.rsvpPage()
        assertFalse(garbage.valid)
        assertEquals("not-a-uuid", garbage.eventId)

        alice.setCalendarAccessMode(calendar.id.value, "signed_in")
        assertEquals(HttpStatusCode.OK, alice.enableOpenRsvp(event.id.value))
        val restricted = anonymous.get("/rsvp/${event.id.value}") {
            header(KeelHeaders.VISIT, "true")
        }.rsvpPage()
        assertFalse(restricted.valid)

        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val signedIn = bob.get("/rsvp/${event.id.value}") {
            header(KeelHeaders.VISIT, "true")
        }.rsvpPage()
        assertTrue(signedIn.valid)
        assertFalse(signedIn.requiresName)
        assertEquals("bob", signedIn.viewer?.username)
        assertEquals("Locked", signedIn.title)
        assertEquals("Private", signedIn.calendarName)
    }

    private companion object {
        val EventStart: Instant = Instant.parse("2026-09-07T14:00:00Z")
        val EventEnd: Instant = Instant.parse("2026-09-07T15:00:00Z")
        val RsvpLinkPattern = Regex("""/rsvp/([0-9a-f-]+)\?token=([A-Za-z0-9_-]+)""")
    }
}

private suspend fun HttpResponse.rsvpPage(): RsvpPage {
    assertEquals(HttpStatusCode.OK, status)
    val seed = KeelJson.codec.decodeFromString(KeelSeed.serializer(), bodyAsText())
    assertEquals("kalendee.rsvp", seed.page)
    return KeelJson.codec.decodeFromJsonElement(RsvpPage.serializer(), seed.data)
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
): Event {
    val response = post("/api/v1/calendars/$calendarId/events") {
        contentType(ContentType.Application.Json)
        setBody(CreateEvent(title = title, start = start, end = end))
    }
    check(response.status == HttpStatusCode.Created) { "create event failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.registerWithEmail(username: String, email: String) {
    val response = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username","password":"password12","email":"$email"}""")
    }
    check(response.status == HttpStatusCode.Created) { "register failed: ${response.status}" }
}

private suspend fun HttpClient.enableOpenRsvp(eventId: String): HttpStatusCode =
    put("/api/v1/events/$eventId/open-rsvp") {
        contentType(ContentType.Application.Json)
        setBody("""{"enabled":true}""")
    }.status

private suspend fun HttpClient.setCalendarAccessMode(calendarId: String, mode: String) {
    val response = put("/api/v1/calendars/$calendarId/availability") {
        contentType(ContentType.Application.Json)
        setBody("""{"requestsEnabled":false,"slotMinutes":60,"accessMode":"$mode","windows":[]}""")
    }
    check(response.status == HttpStatusCode.OK) { "update availability failed: ${response.status}" }
}
