package dev.kolektiv.kalendee.client

import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.calendar.OrganizationId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val connectionJsonHeaders: Headers =
    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private const val CONNECTION_SESSION_COOKIE = "kalendee_session=stored"

private const val CONNECTION_USER_ID = "7d444840-9dc0-11d1-b245-5ffdce74fad2"
private const val CONNECTION_CALENDAR_ID = "b1a2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
private const val CONNECTION_EVENT_ID = "3f0c6e0a-8b1e-4c2e-9a5d-7f6b1c2d3e4f"
private const val CONNECTION_ORG_ID = "6f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d"

private suspend fun HttpRequestData.jsonBody(): JsonObject =
    Json.parseToJsonElement(body.toByteArray().decodeToString()).jsonObject

private fun primitives(body: JsonObject): Map<String, String?> =
    body.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull }

private fun connectionEventJson(
    title: String,
    calendarId: String = CONNECTION_CALENDAR_ID,
): String = """
    {
      "id": "$CONNECTION_EVENT_ID",
      "calendarId": "$calendarId",
      "title": "$title",
      "description": null,
      "location": null,
      "url": null,
      "start": "2026-10-08T09:00:00Z",
      "end": "2026-10-08T09:30:00Z",
      "allDay": false,
      "timeZone": null,
      "status": "CONFIRMED",
      "recurrence": null,
      "etag": "etag-moved",
      "createdAt": "2026-10-01T08:00:00Z",
      "updatedAt": "2026-10-08T09:00:00Z",
      "openRsvp": false,
      "rsvpStatus": null
    }
""".trimIndent()

private fun connectionCalendarJson(organizationId: String? = null): String = """
    {
      "id": "$CONNECTION_CALENDAR_ID",
      "ownerId": "$CONNECTION_USER_ID",
      "displayName": "Moved calendar",
      "description": null,
      "timeZone": "UTC",
      "color": "primary",
      "hidden": false,
      "permission": "owner",
      "publicLinkEnabled": false,
      "publicLinkToken": null,
      "requestsEnabled": false,
      "slotMinutes": 60,
      "accessMode": "inherit",
      "createdAt": "2026-01-01T00:00:00Z",
      "updatedAt": "2026-10-08T09:00:00Z",
      "ownerAvatarVersion": null,
      "organizationId": ${organizationId?.let { "\"$it\"" } ?: "null"}
    }
""".trimIndent()

private fun connectionUserJson(publicAccess: String): String = """
    {
      "id": "$CONNECTION_USER_ID",
      "username": "alice",
      "displayName": "Alice",
      "email": "alice@example.com",
      "emailVerified": true,
      "avatarVersion": null,
      "timeZone": "UTC",
      "accent": "primary",
      "admin": false,
      "publicAccess": "$publicAccess",
      "createdAt": "2026-01-01T00:00:00Z"
    }
""".trimIndent()

private val OAUTH_PROVIDERS_JSON = """
    {
      "providers": [
        {
          "id": "google",
          "displayName": "Google",
          "enabled": true,
          "connectUrl": "https://calendar.example/api/v1/oauth/google/connect",
          "registerUrl": null
        },
        {
          "id": "github",
          "displayName": "GitHub",
          "enabled": false,
          "connectUrl": "https://calendar.example/api/v1/oauth/github/connect",
          "registerUrl": "https://calendar.example/register?provider=github"
        }
      ]
    }
""".trimIndent()

private val CONNECTIONS_JSON = """
    {
      "connections": [
        {
          "id": "conn-1",
          "provider": "google",
          "providerName": "Google",
          "accountEmail": "alice@gmail.com",
          "displayName": "Alice G",
          "status": "connected",
          "lastSyncAt": "2026-10-07T09:00:00Z",
          "lastError": null
        },
        {
          "id": "conn-2",
          "provider": "microsoft",
          "providerName": "Microsoft",
          "accountEmail": null,
          "displayName": null,
          "status": "error",
          "lastSyncAt": null,
          "lastError": "reauth required"
        }
      ]
    }
""".trimIndent()

private val ADMIN_SETTINGS_JSON = """
    {
      "registrationOpen": true,
      "oauthRegistrationOpen": false,
      "emailVerification": "soft",
      "publicAccess": "signed_in"
    }
""".trimIndent()

class ConnectionApiClientTest {
    @Test
    fun transferCalendarPutsBodyAndParsesCalendar() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/api/v1/calendars/$CONNECTION_CALENDAR_ID/transfer", request.url.encodedPath)
            assertEquals(CONNECTION_SESSION_COOKIE, request.headers[HttpHeaders.Cookie])
            assertEquals(
                mapOf("organizationId" to CONNECTION_ORG_ID, "teamId" to "team-1"),
                primitives(request.jsonBody()),
            )
            respond(
                content = connectionCalendarJson(organizationId = CONNECTION_ORG_ID),
                status = HttpStatusCode.OK,
                headers = connectionJsonHeaders,
            )
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { CONNECTION_SESSION_COOKIE },
            engine = engine,
        )
        val calendar = api.transferCalendar(
            CONNECTION_CALENDAR_ID,
            organizationId = CONNECTION_ORG_ID,
            teamId = "team-1",
        )

        assertEquals(CalendarId(CONNECTION_CALENDAR_ID), calendar.id)
        assertEquals(OrganizationId(CONNECTION_ORG_ID), calendar.organizationId)
        assertEquals("Moved calendar", calendar.displayName)
    }

    @Test
    fun transferCalendarSerializesExplicitNulls() = runTest {
        val engine = MockEngine { request ->
            assertEquals(
                """{"organizationId":null,"teamId":null}""",
                request.body.toByteArray().decodeToString(),
            )
            respond(
                content = connectionCalendarJson(),
                status = HttpStatusCode.OK,
                headers = connectionJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val calendar = api.transferCalendar(CONNECTION_CALENDAR_ID, organizationId = null, teamId = null)

        assertNull(calendar.organizationId)
        assertEquals(CalendarId(CONNECTION_CALENDAR_ID), calendar.id)
    }

    @Test
    fun moveEventPostsBodyAndParsesEvents() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/events/$CONNECTION_EVENT_ID/move", request.url.encodedPath)
            assertEquals(CONNECTION_SESSION_COOKIE, request.headers[HttpHeaders.Cookie])
            assertEquals(
                mapOf(
                    "calendarId" to "cal-dest",
                    "scope" to "following",
                    "from" to "2026-10-08T09:00:00Z",
                    "etag" to "etag-1",
                ),
                primitives(request.jsonBody()),
            )
            respond(
                content = """{"events":[${connectionEventJson(title = "Moved", calendarId = "cal-dest")}]}""",
                status = HttpStatusCode.OK,
                headers = connectionJsonHeaders,
            )
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { CONNECTION_SESSION_COOKIE },
            engine = engine,
        )
        val events = api.moveEvent(
            CONNECTION_EVENT_ID,
            calendarId = "cal-dest",
            from = "2026-10-08T09:00:00Z",
            etag = "etag-1",
        )

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(EventId(CONNECTION_EVENT_ID), event.id)
        assertEquals(CalendarId("cal-dest"), event.calendarId)
        assertEquals("Moved", event.title)
        assertEquals(EventStatus.CONFIRMED, event.status)
    }

    @Test
    fun moveEventDefaultsScopeAndKeepsExplicitNulls() = runTest {
        val engine = MockEngine { request ->
            assertEquals(
                """{"calendarId":"cal-dest","scope":"following","from":null,"etag":null}""",
                request.body.toByteArray().decodeToString(),
            )
            respond(content = """{"events":[]}""", status = HttpStatusCode.OK, headers = connectionJsonHeaders)
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)

        assertTrue(api.moveEvent(CONNECTION_EVENT_ID, calendarId = "cal-dest").isEmpty())
    }

    @Test
    fun setMyPublicAccessPutsModeAndParsesUser() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/api/v1/auth/me/public-access", request.url.encodedPath)
            assertEquals(CONNECTION_SESSION_COOKIE, request.headers[HttpHeaders.Cookie])
            assertEquals(mapOf("mode" to "public"), primitives(request.jsonBody()))
            respond(connectionUserJson("public"), HttpStatusCode.OK, connectionJsonHeaders)
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { CONNECTION_SESSION_COOKIE },
            engine = engine,
        )
        val user = api.setMyPublicAccess(mode = "public")

        assertEquals(PublicAccessMode.PUBLIC, user.publicAccess)
        assertEquals("alice", user.username)
    }

    @Test
    fun oauthProvidersUsesPublicRouteWithoutSession() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/oauth/providers", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Cookie])
            respond(OAUTH_PROVIDERS_JSON, HttpStatusCode.OK, connectionJsonHeaders)
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { CONNECTION_SESSION_COOKIE },
            engine = engine,
        )
        val providers = api.oauthProviders()

        assertEquals(2, providers.size)
        val google = providers[0]
        assertEquals("google", google.id)
        assertEquals("Google", google.displayName)
        assertTrue(google.enabled)
        assertEquals("https://calendar.example/api/v1/oauth/google/connect", google.connectUrl)
        assertNull(google.registerUrl)

        val github = providers[1]
        assertEquals("github", github.id)
        assertTrue(!github.enabled)
        assertEquals("https://calendar.example/register?provider=github", github.registerUrl)
    }

    @Test
    fun connectionProvidersSendsSessionCookieAndParses() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/connections/providers", request.url.encodedPath)
            assertEquals(CONNECTION_SESSION_COOKIE, request.headers[HttpHeaders.Cookie])
            respond(OAUTH_PROVIDERS_JSON, HttpStatusCode.OK, connectionJsonHeaders)
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { CONNECTION_SESSION_COOKIE },
            engine = engine,
        )
        val providers = api.connectionProviders()

        assertEquals(listOf("google", "github"), providers.map { it.id })
        assertEquals("GitHub", providers[1].displayName)
    }

    @Test
    fun connectionsParsesServerShape() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/connections", request.url.encodedPath)
            assertEquals(CONNECTION_SESSION_COOKIE, request.headers[HttpHeaders.Cookie])
            respond(CONNECTIONS_JSON, HttpStatusCode.OK, connectionJsonHeaders)
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { CONNECTION_SESSION_COOKIE },
            engine = engine,
        )
        val connections = api.connections()

        assertEquals(2, connections.size)
        val active = connections[0]
        assertEquals("conn-1", active.id)
        assertEquals("google", active.provider)
        assertEquals("Google", active.providerName)
        assertEquals("alice@gmail.com", active.accountEmail)
        assertEquals("Alice G", active.displayName)
        assertEquals("connected", active.status)
        assertEquals("2026-10-07T09:00:00Z", active.lastSyncAt)
        assertNull(active.lastError)

        val broken = connections[1]
        assertEquals("conn-2", broken.id)
        assertEquals("microsoft", broken.provider)
        assertNull(broken.accountEmail)
        assertNull(broken.displayName)
        assertEquals("error", broken.status)
        assertNull(broken.lastSyncAt)
        assertEquals("reauth required", broken.lastError)
    }

    @Test
    fun disconnectConnectionDeletesAndAcceptsNoContent() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/api/v1/connections/conn-1", request.url.encodedPath)
            assertEquals(CONNECTION_SESSION_COOKIE, request.headers[HttpHeaders.Cookie])
            respond(content = "", status = HttpStatusCode.NoContent)
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { CONNECTION_SESSION_COOKIE },
            engine = engine,
        )

        api.disconnectConnection("conn-1")
    }

    @Test
    fun disconnectConnectionNotFoundMapsToTypedException() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"not_found","message":"connection not found"}""",
                status = HttpStatusCode.NotFound,
                headers = connectionJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val failure = assertFailsWith<KalendeeApiException> { api.disconnectConnection("missing") }

        assertEquals(404, failure.status)
        assertEquals("not_found", failure.code)
        assertEquals("connection not found", failure.message)
    }

    @Test
    fun syncConnectionPostsAndParsesResult() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/connections/conn-1/sync", request.url.encodedPath)
            assertEquals(CONNECTION_SESSION_COOKIE, request.headers[HttpHeaders.Cookie])
            respond(
                content = """{"ok":true,"lastSyncAt":"2026-10-07T10:00:00Z","lastError":null}""",
                status = HttpStatusCode.OK,
                headers = connectionJsonHeaders,
            )
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { CONNECTION_SESSION_COOKIE },
            engine = engine,
        )
        val result = api.syncConnection("conn-1")

        assertTrue(result.ok)
        assertEquals("2026-10-07T10:00:00Z", result.lastSyncAt)
        assertNull(result.lastError)
    }

    @Test
    fun syncConnectionConflictMapsToTypedException() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"reauth_required","message":"connection needs reauthentication"}""",
                status = HttpStatusCode.Conflict,
                headers = connectionJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val failure = assertFailsWith<KalendeeApiException> { api.syncConnection("conn-1") }

        assertEquals(409, failure.status)
        assertEquals("reauth_required", failure.code)
        assertEquals("connection needs reauthentication", failure.message)
    }

    @Test
    fun adminSettingsParsesServerShape() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/admin/settings", request.url.encodedPath)
            assertEquals(CONNECTION_SESSION_COOKIE, request.headers[HttpHeaders.Cookie])
            respond(ADMIN_SETTINGS_JSON, HttpStatusCode.OK, connectionJsonHeaders)
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { CONNECTION_SESSION_COOKIE },
            engine = engine,
        )
        val settings = api.adminSettings()

        assertTrue(settings.registrationOpen)
        assertTrue(!settings.oauthRegistrationOpen)
        assertEquals("soft", settings.emailVerification)
        assertEquals("signed_in", settings.publicAccess)
    }

    @Test
    fun updateAdminSettingsPatchesPartialBodyWithExplicitNulls() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Patch, request.method)
            assertEquals("/api/v1/admin/settings", request.url.encodedPath)
            assertEquals(CONNECTION_SESSION_COOKIE, request.headers[HttpHeaders.Cookie])
            assertEquals(
                """{"registrationOpen":false,"oauthRegistrationOpen":null,"emailVerification":null,"publicAccess":null}""",
                request.body.toByteArray().decodeToString(),
            )
            respond(
                content = """
                    {
                      "registrationOpen": false,
                      "oauthRegistrationOpen": false,
                      "emailVerification": "soft",
                      "publicAccess": "signed_in"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = connectionJsonHeaders,
            )
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { CONNECTION_SESSION_COOKIE },
            engine = engine,
        )
        val settings = api.updateAdminSettings(AdminSettingsBody(registrationOpen = false))

        assertTrue(!settings.registrationOpen)
        assertEquals("soft", settings.emailVerification)
    }
}
