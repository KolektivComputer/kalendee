package dev.kolektiv.kalendee.client

import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.calendar.UpdateEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

private const val USER_ID = "7d444840-9dc0-11d1-b245-5ffdce74fad2"
private const val EVENT_ID = "3f0c6e0a-8b1e-4c2e-9a5d-7f6b1c2d3e4f"
private const val CALENDAR_ID = "b1a2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"

private val jsonHeaders: Headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private val USER_JSON = """
    {
      "id": "$USER_ID",
      "username": "alice",
      "displayName": "Alice",
      "email": "alice@example.com",
      "emailVerified": true,
      "avatarVersion": null,
      "timeZone": "Europe/Amsterdam",
      "accent": "primary",
      "admin": false,
      "publicAccess": "inherit",
      "createdAt": "2026-01-01T00:00:00Z"
    }
""".trimIndent()

private val AUTH_RESULT_JSON = """
    {
      "user": $USER_JSON,
      "verificationRequired": false,
      "email": "alice@example.com"
    }
""".trimIndent()

private val REGISTER_RESULT_JSON = """
    {
      "user": null,
      "verificationRequired": true,
      "email": "bob@example.com"
    }
""".trimIndent()

private fun eventJson(title: String, etag: String, updatedAt: String): String = """
    {
      "id": "$EVENT_ID",
      "calendarId": "$CALENDAR_ID",
      "title": "$title",
      "description": null,
      "location": null,
      "url": null,
      "start": "2026-10-06T09:00:00Z",
      "end": "2026-10-06T09:30:00Z",
      "allDay": false,
      "timeZone": null,
      "status": "CONFIRMED",
      "recurrence": null,
      "etag": "$etag",
      "createdAt": "2026-10-01T08:00:00Z",
      "updatedAt": "$updatedAt",
      "openRsvp": false,
      "rsvpStatus": null
    }
""".trimIndent()

private val EVENTS_JSON = "[${eventJson(title = "Standup", etag = "etag-1", updatedAt = "2026-10-01T08:00:00Z")}]"

private val UPDATED_EVENT_JSON = eventJson(title = "Standup v2", etag = "etag-2", updatedAt = "2026-10-06T10:00:00Z")

private val REMINDERS_JSON = """
    [
      {
        "eventId": "$EVENT_ID",
        "calendarId": "$CALENDAR_ID",
        "calendarName": "Work",
        "calendarColor": "#3b82f6",
        "title": "Standup",
        "start": "2026-10-06T09:00:00Z",
        "allDay": false,
        "offsetSeconds": 600,
        "remindAt": "2026-10-06T08:50:00Z"
      }
    ]
""".trimIndent()

private val NOTIFICATIONS_JSON = """
    [
      {
        "id": "n-1",
        "kind": "event_invite",
        "title": "New invite",
        "body": null,
        "href": null,
        "read": false,
        "createdAt": "2026-10-06T07:00:00Z"
      }
    ]
""".trimIndent()

class KalendeeApiTest {
    @Test
    fun loginParsesSessionCookieAndSendsItOnMe() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    respond(
                        content = AUTH_RESULT_JSON,
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                            HttpHeaders.SetCookie to listOf("kalendee_session=token-123; Path=/; HttpOnly; SameSite=Lax"),
                        ),
                    )
                }
                "/api/v1/auth/me" -> {
                    assertEquals("kalendee_session=token-123", request.headers[HttpHeaders.Cookie])
                    respond(USER_JSON, HttpStatusCode.OK, jsonHeaders)
                }
                else -> error("unexpected request ${request.url}")
            }
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val session = api.login("alice", "secret")

        assertEquals("alice", session.user?.username)
        assertEquals("kalendee_session=token-123", session.token)
        assertFalse(session.verificationRequired)
        assertEquals("alice", api.me().username)
    }

    @Test
    fun registerWithoutCookieLeavesSessionTokenUnset() = runTest {
        var meRequests = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/register" -> respond(REGISTER_RESULT_JSON, HttpStatusCode.Created, jsonHeaders)
                "/api/v1/auth/me" -> {
                    meRequests += 1
                    assertNull(request.headers[HttpHeaders.Cookie])
                    respond(USER_JSON, HttpStatusCode.OK, jsonHeaders)
                }
                else -> error("unexpected request ${request.url}")
            }
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val result = api.register("bob", "secret", email = "bob@example.com")

        assertNull(result.user)
        assertTrue(result.verificationRequired)
        assertEquals("bob@example.com", result.email)

        api.me()
        assertEquals(1, meRequests)
    }

    @Test
    fun tokenProviderTokenIsSentOnRequests() = runTest {
        val engine = MockEngine { request ->
            assertEquals("kalendee_session=stored", request.headers[HttpHeaders.Cookie])
            respond(USER_JSON, HttpStatusCode.OK, jsonHeaders)
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { "kalendee_session=stored" },
            engine = engine,
        )

        assertEquals("alice", api.me().username)
    }

    @Test
    fun eventsSendsRangeAndParsesCoreEvents() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/events", request.url.encodedPath)
            assertEquals("2026-10-01T00:00:00Z", request.url.parameters["from"])
            assertEquals("2026-10-08T00:00:00Z", request.url.parameters["to"])
            respond(EVENTS_JSON, HttpStatusCode.OK, jsonHeaders)
        }

        val api = KalendeeApi("https://calendar.example/", engine = engine)
        val events = api.events(
            from = Instant.parse("2026-10-01T00:00:00Z"),
            to = Instant.parse("2026-10-08T00:00:00Z"),
        )

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(EventId(EVENT_ID), event.id)
        assertEquals(CalendarId(CALENDAR_ID), event.calendarId)
        assertEquals("Standup", event.title)
        assertEquals(Instant.parse("2026-10-06T09:00:00Z"), event.start)
        assertEquals(Instant.parse("2026-10-06T09:30:00Z"), event.end)
        assertEquals(EventStatus.CONFIRMED, event.status)
        assertEquals("etag-1", event.etag)
    }

    @Test
    fun updateEventSendsIfMatchAndReturnsNewEtag() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Patch, request.method)
            assertEquals("/api/v1/events/$EVENT_ID", request.url.encodedPath)
            assertEquals("\"etag-1\"", request.headers[HttpHeaders.IfMatch])
            respond(
                content = UPDATED_EVENT_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.ETag to listOf("\"etag-2\""),
                ),
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val tagged = api.updateEvent(EVENT_ID, UpdateEvent(title = "Standup v2"), etag = "etag-1")

        assertEquals("Standup v2", tagged.event.title)
        assertEquals("etag-2", tagged.etag)
    }

    @Test
    fun updateEventWithoutEtagOmitsIfMatch() = runTest {
        val engine = MockEngine { request ->
            assertNull(request.headers[HttpHeaders.IfMatch])
            respond(UPDATED_EVENT_JSON, HttpStatusCode.OK, jsonHeaders)
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val tagged = api.updateEvent(EVENT_ID, UpdateEvent(title = "Standup v2"), etag = null)

        assertEquals("etag-2", tagged.etag)
    }

    @Test
    fun preconditionFailedMapsToTypedException() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"precondition_failed","message":"etag mismatch"}""",
                status = HttpStatusCode.PreconditionFailed,
                headers = jsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val failure = assertFailsWith<KalendeeApiException> {
            api.updateEvent(EVENT_ID, UpdateEvent(title = "Standup v2"), etag = "etag-stale")
        }

        assertEquals(412, failure.status)
        assertEquals("precondition_failed", failure.code)
        assertEquals("etag mismatch", failure.message)
        assertTrue(failure.isPreconditionFailed)
        assertFalse(failure.isUnauthorized)
    }

    @Test
    fun unauthorizedMapsToTypedException() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"unauthorized","message":"unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = jsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val failure = assertFailsWith<KalendeeApiException> { api.me() }

        assertEquals(401, failure.status)
        assertEquals("unauthorized", failure.code)
        assertEquals("unauthorized", failure.message)
        assertTrue(failure.isUnauthorized)
    }

    @Test
    fun upcomingRemindersParseServerShape() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/v1/reminders/upcoming", request.url.encodedPath)
            assertEquals("24", request.url.parameters["hours"])
            respond(REMINDERS_JSON, HttpStatusCode.OK, jsonHeaders)
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val reminders = api.upcomingReminders(hours = 24)

        assertEquals(1, reminders.size)
        val reminder = reminders.single()
        assertEquals(EVENT_ID, reminder.eventId)
        assertEquals(CALENDAR_ID, reminder.calendarId)
        assertEquals("Work", reminder.calendarName)
        assertEquals("#3b82f6", reminder.calendarColor)
        assertEquals("Standup", reminder.title)
        assertEquals("2026-10-06T09:00:00Z", reminder.start)
        assertFalse(reminder.allDay)
        assertEquals(600, reminder.offsetSeconds)
        assertEquals("2026-10-06T08:50:00Z", reminder.remindAt)
    }

    @Test
    fun notificationsParseServerShape() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/notifications" -> respond(NOTIFICATIONS_JSON, HttpStatusCode.OK, jsonHeaders)
                "/api/v1/notifications/read-all" -> respond(
                    content = """{"unreadCount":0}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
                else -> error("unexpected request ${request.url}")
            }
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val notifications = api.notifications()

        assertEquals(1, notifications.size)
        val notification = notifications.single()
        assertEquals("n-1", notification.id)
        assertEquals("event_invite", notification.kind)
        assertEquals("New invite", notification.title)
        assertNull(notification.body)
        assertNull(notification.href)
        assertFalse(notification.read)
        assertEquals("2026-10-06T07:00:00Z", notification.createdAt)

        assertEquals(0, api.markAllNotificationsRead().unreadCount)
    }
}
