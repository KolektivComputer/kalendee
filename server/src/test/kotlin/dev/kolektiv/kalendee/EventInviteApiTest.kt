package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.NotificationOut
import dev.kolektiv.kalendee.api.PublicLinkBody
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.web.CalendarSharingOut
import dev.kolektiv.kalendee.web.EventAttendeesOut
import dev.kolektiv.kalendee.web.EventSummary
import dev.kolektiv.kalendee.web.RsvpOut
import dev.kolektiv.keel.Keel
import dev.kolektiv.keel.KeelJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
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

class EventInviteApiTest {
    @Test
    fun inviteFriendSeesEventAndResponds() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        val alice = jsonClient()
        alice.registerWithEmail("alice", "alice@example.com")
        val bob = jsonClient()
        bob.registerWithEmail("bob", "bob@example.com")
        mail.clear()

        val calendar = alice.createCalendar("Work")
        val event = alice.createEvent(calendar.id.value, "Planning", EventStart, EventEnd)

        val invited = alice.inviteByUsername(event.id.value, "bob")
        assertEquals(HttpStatusCode.OK, invited.status)
        val attendees = invited.actionData<EventAttendeesOut>()
        assertEquals(listOf("bob"), attendees.attendees.map { it.username })
        assertEquals("invited", attendees.attendees.single().status)
        assertFalse(attendees.openRsvp)

        val duplicate = alice.inviteByUsername(event.id.value, "bob")
        assertEquals(HttpStatusCode.UnprocessableEntity, duplicate.status)
        assertTrue(duplicate.bodyAsText().contains("already invited"))

        val self = alice.inviteByUsername(event.id.value, "alice")
        assertEquals(HttpStatusCode.UnprocessableEntity, self.status)
        assertTrue(self.bodyAsText().contains("cannot invite yourself"))

        val unknown = alice.inviteByUsername(event.id.value, "nobody")
        assertEquals(HttpStatusCode.UnprocessableEntity, unknown.status)
        assertTrue(unknown.bodyAsText().contains("username"))

        assertTrue(
            bob.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "event.invite" && it.title == "alice invited you to Planning" },
        )
        assertTrue(mail.sent.any { it.to == "bob@example.com" && "alice invited you to Planning" in it.subject })
        assertTrue(mail.sent.any { it.to == "bob@example.com" && "https://kalendee.test/" in it.text })

        val ranged = bob.rangeEvents()
        assertEquals(listOf(event.id), ranged.map { it.id })
        assertEquals("invited", ranged.single().rsvpStatus)
        assertFalse(ranged.single().openRsvp)

        assertEquals(HttpStatusCode.OK, bob.get("/api/v1/events/${event.id.value}").status)
        val listed = bob.get("/api/v1/events/${event.id.value}/attendees")
        assertEquals(HttpStatusCode.OK, listed.status)
        assertEquals(listOf("bob"), listed.body<EventAttendeesOut>().attendees.map { it.username })

        val responded = bob.respondToInvite(event.id.value, "yes")
        assertEquals(HttpStatusCode.OK, responded.status)
        assertEquals("yes", responded.actionData<RsvpOut>().status)
        assertEquals("yes", bob.get("/api/v1/events/${event.id.value}").body<Event>().rsvpStatus)

        assertTrue(
            alice.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "event.rsvp" && it.body != null },
        )
        assertTrue(mail.sent.any { it.to == "alice@example.com" && "bob responded" in it.subject })

        val invalid = bob.respondToInvite(event.id.value, "sometimes")
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        assertTrue(invalid.bodyAsText().contains("status"))
    }

    @Test
    fun emailInviteStoresTokenAndRespondsByToken() = testApplication {
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
        val attendee = invited.body<EventAttendeesOut>().attendees.single()
        assertEquals("guest@example.com", attendee.email)
        assertEquals("Guest", attendee.name)
        assertNull(attendee.userId)

        val inviteMail = mail.sent.single { it.to == "guest@example.com" }
        val match = RsvpLinkPattern.find(inviteMail.text)
            ?: error("no rsvp link in invite mail: ${inviteMail.text}")
        assertEquals(event.id.value, match.groupValues[1])
        val token = match.groupValues[2]

        val anonymous = jsonClient()
        val rsvp = anonymous.rsvpByToken(token, "maybe")
        assertEquals(HttpStatusCode.OK, rsvp.status)
        assertEquals("maybe", rsvp.body<RsvpOut>().status)
        assertTrue(
            alice.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "event.rsvp" && it.title.contains("Guest") },
        )
        assertTrue(mail.sent.any { it.to == "alice@example.com" && "Guest responded" in it.subject })

        assertEquals(
            HttpStatusCode.NotFound,
            anonymous.rsvpByToken("not-a-real-token", "yes").status,
        )

        val duplicate = alice.post("/api/v1/events/${event.id.value}/attendees") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"GUEST@example.com"}""")
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals(1, alice.attendees(event.id.value).attendees.size)

        val missing = alice.post("/api/v1/events/${event.id.value}/attendees") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"nobody"}""")
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun nonAttendeeCannotSeeInvitedEvent() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val carol = jsonClient()
        carol.registerAndLogin("carol")

        val calendar = alice.createCalendar("Work")
        val event = alice.createEvent(calendar.id.value, "Planning", EventStart, EventEnd)
        assertEquals(HttpStatusCode.OK, alice.inviteByUsername(event.id.value, "bob").status)

        assertEquals(HttpStatusCode.NotFound, carol.get("/api/v1/events/${event.id.value}").status)
        assertTrue(carol.rangeEvents().isEmpty())
        assertEquals(
            HttpStatusCode.NotFound,
            carol.get("/api/v1/events/${event.id.value}/attendees").status,
        )
        val respond = carol.post("/api/v1/events/${event.id.value}/rsvp") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"yes"}""")
        }
        assertEquals(HttpStatusCode.NotFound, respond.status)
    }

    @Test
    fun ownerAndWriterCanRemoveAttendees() = testApplication {
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
        val event = alice.createEvent(calendar.id.value, "Planning", EventStart, EventEnd)
        alice.share(calendar.id.value, "bob", "write")
        alice.share(calendar.id.value, "dave", "read")
        assertEquals(HttpStatusCode.OK, bob.inviteByUsername(event.id.value, "carol").status)

        val attendees = bob.attendees(event.id.value)
        val carolAttendee = attendees.attendees.single { it.username == "carol" }
        assertEquals("invited", carolAttendee.status)

        val daveInvite = dave.inviteByUsername(event.id.value, "carol")
        assertEquals(HttpStatusCode.UnprocessableEntity, daveInvite.status)
        val daveResponse = dave.respondToInvite(event.id.value, "yes")
        assertEquals(HttpStatusCode.OK, daveResponse.status)
        assertEquals("yes", daveResponse.actionData<RsvpOut>().status)
        val daveRemove = dave.delete("/api/v1/events/${event.id.value}/attendees/${carolAttendee.id}")
        assertEquals(HttpStatusCode.Forbidden, daveRemove.status)

        val removed = alice.delete("/api/v1/events/${event.id.value}/attendees/${carolAttendee.id}")
        assertEquals(HttpStatusCode.OK, removed.status)
        assertTrue(removed.body<EventAttendeesOut>().attendees.none { it.username == "carol" })
        assertEquals(HttpStatusCode.NotFound, carol.get("/api/v1/events/${event.id.value}").status)
        assertEquals(
            HttpStatusCode.NotFound,
            alice.delete("/api/v1/events/${event.id.value}/attendees/${carolAttendee.id}").status,
        )
    }

    @Test
    fun readShareAndFollowerViewersCanRsvp() = testApplication {
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
        val event = alice.createEvent(calendar.id.value, "Planning", EventStart, EventEnd)
        alice.share(calendar.id.value, "bob", "read")

        val respondBob = bob.respondToInvite(event.id.value, "yes")
        assertEquals(HttpStatusCode.OK, respondBob.status)
        assertEquals("yes", respondBob.actionData<RsvpOut>().status)
        val repeat = bob.respondToInvite(event.id.value, "maybe")
        assertEquals(HttpStatusCode.OK, repeat.status)
        assertEquals("maybe", repeat.actionData<RsvpOut>().status)
        assertEquals("maybe", bob.get("/api/v1/events/${event.id.value}").body<Event>().rsvpStatus)
        val bobAttendees = alice.attendees(event.id.value).attendees
        assertEquals(1, bobAttendees.count { it.username == "bob" })

        val publicToken = alice.enablePublicLink(calendar.id.value)
        assertEquals(
            HttpStatusCode.OK,
            dave.post("/api/v1/public/calendars/$publicToken/follow").status,
        )

        val respondDave = dave.respondToInvite(event.id.value, "no")
        assertEquals(HttpStatusCode.OK, respondDave.status)
        assertEquals("no", respondDave.actionData<RsvpOut>().status)

        val respondCarol = carol.post("/api/v1/events/${event.id.value}/rsvp") {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"yes"}""")
        }
        assertEquals(HttpStatusCode.NotFound, respondCarol.status)

        assertTrue(
            alice.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "event.rsvp" && it.title.contains("bob") },
        )
    }

    @Test
    fun openRsvpOnPublicCalendarAcceptsAnonymousResponses() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        val alice = jsonClient()
        alice.registerWithEmail("alice", "alice@example.com")
        val calendar = alice.createCalendar("Work")
        val event = alice.createEvent(calendar.id.value, "Open house", EventStart, EventEnd)
        mail.clear()

        val anonymous = jsonClient()
        assertEquals(
            HttpStatusCode.Forbidden,
            anonymous.publicRsvp(event.id.value, "Guest", "guest@example.com", "yes").status,
        )

        val enabled = alice.put("/api/v1/events/${event.id.value}/open-rsvp") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":true}""")
        }
        assertEquals(HttpStatusCode.OK, enabled.status)
        assertTrue(enabled.body<EventSummary>().openRsvp)

        assertEquals(
            HttpStatusCode.BadRequest,
            anonymous.publicRsvp(event.id.value, "Guest", "guest@example.com", "sometimes").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            anonymous.publicRsvp(event.id.value, "  ", "guest@example.com", "yes").status,
        )

        val first = anonymous.publicRsvp(event.id.value, "Guest", "guest@example.com", "yes")
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals("yes", first.body<RsvpOut>().status)

        assertTrue(
            alice.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "event.rsvp" && it.title.contains("Guest") },
        )
        assertTrue(mail.sent.any { it.to == "alice@example.com" && "Guest responded" in it.subject })

        val again = anonymous.publicRsvp(event.id.value, "Guest Two", "GUEST@example.com", "maybe")
        assertEquals(HttpStatusCode.OK, again.status)
        val attendees = alice.attendees(event.id.value)
        assertTrue(attendees.openRsvp)
        assertEquals(1, attendees.attendees.size)
        assertEquals("Guest Two", attendees.attendees.single().name)
        assertEquals("maybe", attendees.attendees.single().status)

        assertEquals(
            HttpStatusCode.OK,
            anonymous.publicRsvp(event.id.value, "Anon", null, "no").status,
        )
        assertEquals(2, alice.attendees(event.id.value).attendees.size)
    }

    @Test
    fun signedInCalendarRejectsAnonymousRsvp() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Private")
        val event = alice.createEvent(calendar.id.value, "Locked", EventStart, EventEnd)

        val updated = alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"requestsEnabled":false,"slotMinutes":60,"accessMode":"signed_in","windows":[]}""",
            )
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals(
            HttpStatusCode.OK,
            alice.put("/api/v1/events/${event.id.value}/open-rsvp") {
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":true}""")
            }.status,
        )

        assertEquals(
            HttpStatusCode.NotFound,
            jsonClient().publicRsvp(event.id.value, "Guest", null, "yes").status,
        )

        val signedIn = jsonClient()
        signedIn.registerAndLogin("bob")
        val allowed = signedIn.publicRsvp(event.id.value, "Bob", "bob@example.com", "yes")
        assertEquals(HttpStatusCode.OK, allowed.status)
        assertEquals("yes", allowed.body<RsvpOut>().status)
    }

    private companion object {
        val EventStart: Instant = Instant.parse("2026-09-07T14:00:00Z")
        val EventEnd: Instant = Instant.parse("2026-09-07T15:00:00Z")
        val RsvpLinkPattern = Regex("""/rsvp/([0-9a-f-]+)\?token=([A-Za-z0-9_-]+)""")
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

private suspend fun HttpClient.registerWithEmail(username: String, email: String) {
    val response = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterUser(username = username, password = "password12", email = email))
    }
    check(response.status == HttpStatusCode.Created) { "register failed: ${response.status}" }
}

private suspend fun HttpClient.rangeEvents(): List<Event> =
    get("/api/v1/events?from=2026-09-07T00:00:00Z&to=2026-09-08T00:00:00Z").body()

private suspend fun HttpClient.inviteByUsername(eventId: String, username: String): HttpResponse =
    post("${Keel.ACTION_PATH}/kalendee.inviteToEvent") {
        contentType(ContentType.Application.Json)
        setBody("""{"eventId":"$eventId","username":"$username"}""")
    }

private suspend fun HttpClient.respondToInvite(eventId: String, status: String): HttpResponse =
    post("${Keel.ACTION_PATH}/kalendee.respondEventInvite") {
        contentType(ContentType.Application.Json)
        setBody("""{"eventId":"$eventId","status":"$status"}""")
    }

private suspend fun HttpClient.attendees(eventId: String): EventAttendeesOut {
    val response = get("/api/v1/events/$eventId/attendees")
    check(response.status == HttpStatusCode.OK) { "attendees failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.publicRsvp(
    eventId: String,
    name: String,
    email: String?,
    status: String,
): HttpResponse = post("/api/v1/public/events/$eventId/rsvp") {
    contentType(ContentType.Application.Json)
    setBody(
        buildJsonObject {
            put("name", name)
            if (email != null) put("email", email)
            put("status", status)
        }.toString(),
    )
}

private suspend fun HttpClient.rsvpByToken(token: String, status: String): HttpResponse =
    post("/api/v1/public/events/rsvp") {
        contentType(ContentType.Application.Json)
        setBody("""{"token":"$token","status":"$status"}""")
    }

private suspend fun HttpClient.share(calendarId: String, username: String, permission: String) {
    val response = post("/api/v1/calendars/$calendarId/shares") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username","permission":"$permission"}""")
    }
    check(response.status == HttpStatusCode.Created) { "share failed: ${response.status}" }
}

private suspend fun HttpClient.enablePublicLink(calendarId: String): String {
    val response = put("/api/v1/calendars/$calendarId/public") {
        contentType(ContentType.Application.Json)
        setBody(PublicLinkBody(enabled = true))
    }
    check(response.status == HttpStatusCode.OK) { "enable public link failed: ${response.status}" }
    return response.body<CalendarSharingOut>().publicLinkToken ?: error("no public token")
}
