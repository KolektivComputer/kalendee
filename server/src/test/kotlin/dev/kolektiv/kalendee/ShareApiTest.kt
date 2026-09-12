package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.NotificationOut
import dev.kolektiv.kalendee.api.PublicCalendarOut
import dev.kolektiv.kalendee.api.PublicLinkBody
import dev.kolektiv.kalendee.api.ShareCalendarBody
import dev.kolektiv.kalendee.api.UpdateShareBody
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.UpdateEvent
import dev.kolektiv.kalendee.web.CalendarSharingOut
import dev.kolektiv.kalendee.web.FollowOut
import dev.kolektiv.keel.Keel
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ShareApiTest {
    @Test
    fun readShareGrantsReadOnlyAccess() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val carol = jsonClient()
        carol.registerAndLogin("carol")

        val calendar = alice.createCalendar("Work")
        val event = alice.createEvent(
            calendar.id.value,
            "Standup",
            Instant.parse("2026-03-01T10:00:00Z"),
            Instant.parse("2026-03-01T10:30:00Z"),
        )

        val shared = alice.post("/api/v1/calendars/${calendar.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody(ShareCalendarBody(username = "bob", permission = "read"))
        }
        assertEquals(HttpStatusCode.Created, shared.status)
        val sharing = shared.body<CalendarSharingOut>()
        assertEquals(1, sharing.shares.size)
        assertEquals("bob", sharing.shares.single().username)
        assertEquals("read", sharing.shares.single().permission)
        assertEquals(0, sharing.followerCount)
        assertNull(sharing.publicLinkToken)

        val bobCalendars = bob.get("/api/v1/calendars").body<List<Calendar>>()
        val sharedCalendar = bobCalendars.single { it.id == calendar.id }
        assertEquals(CalendarPermission.READ, sharedCalendar.permission)
        assertNull(sharedCalendar.publicLinkToken)

        val bobEvents = bob.get("/api/v1/calendars/${calendar.id.value}/events")
        assertEquals(HttpStatusCode.OK, bobEvents.status)
        assertEquals(listOf(event.id), bobEvents.body<List<Event>>().map { it.id })

        assertEquals(
            HttpStatusCode.Forbidden,
            bob.post("/api/v1/calendars/${calendar.id.value}/events") {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateEvent(
                        title = "Hijack",
                        start = Instant.parse("2026-03-02T10:00:00Z"),
                        end = Instant.parse("2026-03-02T10:30:00Z"),
                    ),
                )
            }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            bob.patch("/api/v1/events/${event.id.value}") {
                contentType(ContentType.Application.Json)
                setBody(UpdateEvent(title = "Renamed"))
            }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            bob.delete("/api/v1/events/${event.id.value}").status,
        )

        assertTrue(carol.get("/api/v1/calendars").body<List<Calendar>>().isEmpty())
        assertEquals(
            HttpStatusCode.NotFound,
            carol.get("/api/v1/calendars/${calendar.id.value}").status,
        )

        val home = bob.get("/") {
            url {
                parameters.append("week", "2026-09-07")
                parameters.append("tz", "UTC")
            }
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, home.status)
        assertTrue(home.bodyAsText().contains("\"permission\":\"read\""))
        assertTrue(home.bodyAsText().contains("Work"))
    }

    @Test
    fun writeShareAllowsEventMutations() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")

        val calendar = alice.createCalendar("Work")
        alice.post("/api/v1/calendars/${calendar.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody(ShareCalendarBody(username = "bob", permission = "write"))
        }

        val created = bob.post("/api/v1/calendars/${calendar.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Bob's meeting",
                    start = Instant.parse("2026-03-03T10:00:00Z"),
                    end = Instant.parse("2026-03-03T11:00:00Z"),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val event = created.body<Event>()

        val patched = bob.patch("/api/v1/events/${event.id.value}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateEvent(title = "Bob's meeting v2"))
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        assertEquals("Bob's meeting v2", patched.body<Event>().title)

        assertEquals(
            HttpStatusCode.NoContent,
            bob.delete("/api/v1/events/${event.id.value}").status,
        )
        assertTrue(alice.get("/api/v1/calendars/${calendar.id.value}/events").body<List<Event>>().isEmpty())
    }

    @Test
    fun shareByEmailRejectsOwnerDuplicatesAndRemoveRevokes() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerWithEmail("bob", "bob@example.com")

        val calendar = alice.createCalendar("Work")

        val owner = alice.post("/api/v1/calendars/${calendar.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody(ShareCalendarBody(username = "alice", permission = "read"))
        }
        assertEquals(HttpStatusCode.BadRequest, owner.status)

        val byEmail = alice.post("/api/v1/calendars/${calendar.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody(ShareCalendarBody(username = "BoB@Example.com", permission = "write"))
        }
        assertEquals(HttpStatusCode.Created, byEmail.status)
        val share = byEmail.body<CalendarSharingOut>().shares.single()
        assertEquals("bob", share.username)
        assertEquals("write", share.permission)

        val duplicate = alice.post("/api/v1/calendars/${calendar.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody(ShareCalendarBody(username = "bob", permission = "read"))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)

        val invalidPermission = alice.post("/api/v1/calendars/${calendar.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody(ShareCalendarBody(username = "bob", permission = "admin"))
        }
        assertEquals(HttpStatusCode.BadRequest, invalidPermission.status)

        val updated = alice.patch("/api/v1/calendars/${calendar.id.value}/shares/${share.userId}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateShareBody(permission = "read"))
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals("read", updated.body<CalendarSharingOut>().shares.single().permission)

        val removed = alice.delete("/api/v1/calendars/${calendar.id.value}/shares/${share.userId}")
        assertEquals(HttpStatusCode.OK, removed.status)
        assertTrue(removed.body<CalendarSharingOut>().shares.isEmpty())
        assertTrue(bob.get("/api/v1/calendars").body<List<Calendar>>().isEmpty())
        assertEquals(
            HttpStatusCode.NotFound,
            bob.get("/api/v1/calendars/${calendar.id.value}").status,
        )
    }

    @Test
    fun publicLinkEnableRotateDisable() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")
        val event = alice.createEvent(
            calendar.id.value,
            "Standup",
            Instant.parse("2026-09-07T14:00:00Z"),
            Instant.parse("2026-09-07T14:30:00Z"),
        )

        val enabled = alice.put("/api/v1/calendars/${calendar.id.value}/public") {
            contentType(ContentType.Application.Json)
            setBody(PublicLinkBody(enabled = true))
        }
        assertEquals(HttpStatusCode.OK, enabled.status)
        val enabledBody = enabled.body<CalendarSharingOut>()
        assertTrue(enabledBody.publicLinkEnabled)
        val token = enabledBody.publicLinkToken ?: error("no token after enable")

        val page = jsonClient().get("/c/$token") {
            url {
                parameters.append("week", "2026-09-07")
                parameters.append("tz", "UTC")
            }
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("\"page\":\"kalendee.publicCalendar\""))
        assertTrue(page.bodyAsText().contains("Standup"))
        assertTrue(page.bodyAsText().contains("/rss/$token.xml"))

        val publicApi = jsonClient().get("/api/v1/public/calendars/$token")
        assertEquals(HttpStatusCode.OK, publicApi.status)
        assertEquals(calendar.id, publicApi.body<PublicCalendarOut>().calendar.id)

        val publicEvents = jsonClient().get(
            "/api/v1/public/calendars/$token/events" +
                "?start=2026-09-07T00:00:00Z&end=2026-09-08T00:00:00Z",
        )
        assertEquals(HttpStatusCode.OK, publicEvents.status)
        assertEquals(listOf(event.id), publicEvents.body<List<Event>>().map { it.id })

        val rotated = alice.post("/api/v1/calendars/${calendar.id.value}/public/rotate")
        assertEquals(HttpStatusCode.OK, rotated.status)
        val rotatedToken = rotated.body<CalendarSharingOut>().publicLinkToken ?: error("no rotated token")
        assertTrue(rotatedToken != token)
        assertEquals(
            HttpStatusCode.NotFound,
            jsonClient().get("/c/$token") { header(KeelHeaders.VISIT, "true") }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            jsonClient().get("/c/$rotatedToken") { header(KeelHeaders.VISIT, "true") }.status,
        )

        val disabled = alice.put("/api/v1/calendars/${calendar.id.value}/public") {
            contentType(ContentType.Application.Json)
            setBody(PublicLinkBody(enabled = false))
        }
        assertEquals(HttpStatusCode.OK, disabled.status)
        assertFalse(disabled.body<CalendarSharingOut>().publicLinkEnabled)
        assertEquals(
            HttpStatusCode.NotFound,
            jsonClient().get("/c/$rotatedToken") { header(KeelHeaders.VISIT, "true") }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            jsonClient().get("/api/v1/public/calendars/$rotatedToken").status,
        )

        val reenabled = alice.put("/api/v1/calendars/${calendar.id.value}/public") {
            contentType(ContentType.Application.Json)
            setBody(PublicLinkBody(enabled = true))
        }
        assertEquals(rotatedToken, reenabled.body<CalendarSharingOut>().publicLinkToken)
        assertEquals(
            HttpStatusCode.OK,
            jsonClient().get("/c/$rotatedToken") { header(KeelHeaders.VISIT, "true") }.status,
        )
    }

    @Test
    fun followAndUnfollowViaPublicLink() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val calendar = alice.createCalendar("Work")

        assertEquals(
            HttpStatusCode.NotFound,
            bob.post("/api/v1/public/calendars/unknown/follow").status,
        )

        val token = alice.enablePublicLink(calendar.id.value)

        val followed = bob.post("/api/v1/public/calendars/$token/follow")
        assertEquals(HttpStatusCode.OK, followed.status)
        assertEquals(FollowOut(calendarId = calendar.id.value, following = true), followed.body<FollowOut>())

        val followedCalendar = bob.get("/api/v1/calendars").body<List<Calendar>>().single()
        assertEquals(calendar.id, followedCalendar.id)
        assertEquals(CalendarPermission.FOLLOW, followedCalendar.permission)

        assertEquals(HttpStatusCode.OK, bob.post("/api/v1/public/calendars/$token/follow").status)
        assertEquals(
            1,
            alice.get("/api/v1/calendars/${calendar.id.value}/shares")
                .body<CalendarSharingOut>().followerCount,
        )

        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().post("/api/v1/public/calendars/$token/follow").status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            alice.post("/api/v1/public/calendars/$token/follow").status,
        )

        assertEquals(
            HttpStatusCode.NoContent,
            bob.delete("/api/v1/public/calendars/$token/follow").status,
        )
        assertTrue(bob.get("/api/v1/calendars").body<List<Calendar>>().isEmpty())

        alice.put("/api/v1/calendars/${calendar.id.value}/public") {
            contentType(ContentType.Application.Json)
            setBody(PublicLinkBody(enabled = false))
        }
        assertEquals(
            HttpStatusCode.NotFound,
            bob.post("/api/v1/public/calendars/$token/follow").status,
        )
    }

    @Test
    fun rssFeedListsUpcomingEvents() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work & Play")
        val start = Clock.System.now() + 1.days
        val event = alice.createEvent(calendar.id.value, "Launch <Party>", start, start + 1.hours)

        val token = alice.enablePublicLink(calendar.id.value)
        val feed = jsonClient().get("/rss/$token.xml")
        assertEquals(HttpStatusCode.OK, feed.status)
        assertTrue(feed.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/rss+xml"))
        val xml = feed.bodyAsText()
        assertTrue(xml.contains("<rss version=\"2.0\""))
        assertTrue(xml.contains("Work &amp; Play"))
        assertTrue(xml.contains("Launch &lt;Party&gt;"))
        assertTrue(xml.contains("<guid isPermaLink=\"false\">${event.id.value}</guid>"))
        assertTrue(xml.contains("<pubDate>"))
        assertTrue(xml.contains("<link>https://kalendee.test/c/$token</link>"))
        assertTrue(xml.contains("href=\"https://kalendee.test/rss/$token.xml\""))
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
        assertEquals("rss", document.documentElement.tagName)

        assertEquals(HttpStatusCode.NotFound, jsonClient().get("/rss/unknown.xml").status)
        alice.put("/api/v1/calendars/${calendar.id.value}/public") {
            contentType(ContentType.Application.Json)
            setBody(PublicLinkBody(enabled = false))
        }
        assertEquals(HttpStatusCode.NotFound, jsonClient().get("/rss/$token.xml").status)
    }

    @Test
    fun sharingAndFollowingNotifyAndSendMail() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        val alice = jsonClient()
        alice.registerWithEmail("alice", "alice@example.com")
        val bob = jsonClient()
        bob.registerWithEmail("bob", "bob@example.com")
        mail.clear()

        val calendar = alice.createCalendar("Work")
        alice.post("/api/v1/calendars/${calendar.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody(ShareCalendarBody(username = "bob", permission = "read"))
        }

        assertTrue(
            bob.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "calendar_shared" },
        )
        assertTrue(mail.sent.any { it.to == "bob@example.com" && "shared" in it.subject })
        assertTrue(
            mail.sent.any {
                it.to == "bob@example.com" && "https://kalendee.test/?calendar=${calendar.id.value}" in it.text
            },
        )

        val token = alice.enablePublicLink(calendar.id.value)
        assertEquals(HttpStatusCode.OK, bob.post("/api/v1/public/calendars/$token/follow").status)
        assertTrue(mail.sent.any { it.to == "alice@example.com" && "https://kalendee.test/c/$token" in it.text })
        assertTrue(mail.sent.any { it.to == "bob@example.com" && "https://kalendee.test/c/$token" in it.text })

        assertTrue(
            alice.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "new_follower" },
        )
        assertTrue(
            bob.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "following" },
        )
        assertTrue(mail.sent.any { it.to == "alice@example.com" && "following your calendar" in it.subject })
        assertTrue(mail.sent.any { it.to == "bob@example.com" && "You are following" in it.subject })
    }

    @Test
    fun shareCalendarActionReportsFieldErrors() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")

        val unknownUsername = alice.post("${Keel.ACTION_PATH}/kalendee.shareCalendar") {
            contentType(ContentType.Application.Json)
            setBody("""{"calendarId":"${calendar.id.value}","username":"nobody","permission":"read"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, unknownUsername.status)
        assertTrue(unknownUsername.bodyAsText().contains("\"username\""))

        val unknownEmail = alice.post("${Keel.ACTION_PATH}/kalendee.shareCalendar") {
            contentType(ContentType.Application.Json)
            setBody("""{"calendarId":"${calendar.id.value}","username":"nobody@example.com","permission":"read"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, unknownEmail.status)
        assertTrue(unknownEmail.bodyAsText().contains("\"email\""))

        val badPermission = alice.post("${Keel.ACTION_PATH}/kalendee.shareCalendar") {
            contentType(ContentType.Application.Json)
            setBody("""{"calendarId":"${calendar.id.value}","username":"alice","permission":"admin"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, badPermission.status)
        assertTrue(badPermission.bodyAsText().contains("\"permission\""))
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

private suspend fun HttpClient.enablePublicLink(calendarId: String): String {
    val response = put("/api/v1/calendars/$calendarId/public") {
        contentType(ContentType.Application.Json)
        setBody(PublicLinkBody(enabled = true))
    }
    check(response.status == HttpStatusCode.OK) { "enable public link failed: ${response.status}" }
    return response.body<CalendarSharingOut>().publicLinkToken ?: error("no public token")
}

private suspend fun HttpClient.registerWithEmail(username: String, email: String) {
    val response = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterUser(username = username, password = "password12", email = email))
    }
    check(response.status == HttpStatusCode.Created) { "register failed: ${response.status}" }
}
