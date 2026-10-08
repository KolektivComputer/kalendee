package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.api.PublicRsvpOut
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PublicRsvpApiTest {
    @Test
    fun openRsvpLookupSupportsAnonymousAndSignedInViewers() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")
        val event = alice.createEvent(calendar.id.value, "Open house", EventStart, EventEnd)
        assertEquals(HttpStatusCode.OK, alice.enableOpenRsvp(event.id.value))

        val response = jsonClient().get("/api/v1/public/events/${event.id.value}")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val anonymous = response.body<PublicRsvpOut>()
        assertTrue(anonymous.valid)
        assertEquals(event.id.value, anonymous.eventId)
        assertNull(anonymous.token)
        assertEquals("Open house", anonymous.title)
        assertEquals("Work", anonymous.calendarName)
        assertNull(anonymous.status)
        assertTrue(anonymous.requiresName)
        assertNull(anonymous.viewer)
        assertTrue(anonymous.whenText?.contains("2026-09-07") == true, anonymous.whenText)

        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val signedIn = bob.get("/api/v1/public/events/${event.id.value}").body<PublicRsvpOut>()
        assertTrue(signedIn.valid)
        assertEquals(event.id.value, signedIn.eventId)
        assertFalse(signedIn.requiresName)
        assertEquals("bob", signedIn.viewer?.username)
    }

    @Test
    fun emailInviteTokenLookupAndStaticRouteOrdering() = testApplication {
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

        val anonymous = jsonClient()
        val response = anonymous.get("/api/v1/public/events/rsvp?token=$token")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val lookup = response.body<PublicRsvpOut>()
        assertEquals(event.id.value, lookup.eventId)
        assertEquals(token, lookup.token)
        assertTrue(lookup.valid)
        assertEquals("Offsite", lookup.title)
        assertEquals("Work", lookup.calendarName)
        assertEquals("invited", lookup.status)
        assertFalse(lookup.requiresName)
        assertNull(lookup.viewer)
        assertTrue(lookup.whenText?.contains("2026-09-07") == true)

        // "rsvp" must hit the static route, not GET /{id}: a missing token is a
        // validation error ("missing token"), not an invalid event id.
        val missingToken = anonymous.get("/api/v1/public/events/rsvp")
        assertEquals(HttpStatusCode.BadRequest, missingToken.status)
        val missingBody = missingToken.body<ErrorBody>()
        assertEquals("invalid", missingBody.error)
        assertTrue("token" in missingBody.message, missingBody.message)

        val unknown = anonymous.get("/api/v1/public/events/rsvp?token=not-a-real-token")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("not_found", unknown.body<ErrorBody>().error)
    }

    @Test
    fun closedOrUnknownEventsAreNotFound() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Private")
        val event = alice.createEvent(calendar.id.value, "Locked", EventStart, EventEnd)

        val anonymous = jsonClient()
        val closed = anonymous.get("/api/v1/public/events/${event.id.value}")
        assertEquals(HttpStatusCode.NotFound, closed.status)
        assertEquals("not_found", closed.body<ErrorBody>().error)

        val unknown = anonymous.get("/api/v1/public/events/${Uuid.random()}")
        assertEquals(HttpStatusCode.NotFound, unknown.status)

        val malformed = anonymous.get("/api/v1/public/events/not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        val malformedBody = malformed.body<ErrorBody>()
        assertEquals("invalid", malformedBody.error)
        assertTrue("event id" in malformedBody.message, malformedBody.message)
    }

    @Test
    fun signedInCalendarLookupRequiresSession() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Locked")
        val event = alice.createEvent(calendar.id.value, "Members", EventStart, EventEnd)
        alice.setCalendarAccessMode(calendar.id.value, "signed_in")
        assertEquals(HttpStatusCode.OK, alice.enableOpenRsvp(event.id.value))

        assertEquals(
            HttpStatusCode.NotFound,
            jsonClient().get("/api/v1/public/events/${event.id.value}").status,
        )

        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val signedIn = bob.get("/api/v1/public/events/${event.id.value}")
        assertEquals(HttpStatusCode.OK, signedIn.status, signedIn.bodyAsText())
        val lookup = signedIn.body<PublicRsvpOut>()
        assertTrue(lookup.valid)
        assertEquals("Members", lookup.title)
        assertFalse(lookup.requiresName)
        assertEquals("bob", lookup.viewer?.username)
    }

    private companion object {
        val EventStart: Instant = Instant.parse("2026-09-07T14:00:00Z")
        val EventEnd: Instant = Instant.parse("2026-09-07T15:00:00Z")
        val RsvpLinkPattern = Regex("""/rsvp/([0-9a-f-]+)\?token=([A-Za-z0-9_-]+)""")
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
