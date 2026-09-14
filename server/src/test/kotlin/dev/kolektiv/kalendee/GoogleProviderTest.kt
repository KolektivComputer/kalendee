package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.oauth.Pkce
import dev.kolektiv.kalendee.oauth.ProviderOAuthSettings
import dev.kolektiv.kalendee.oauth.providers.GoogleCalendarApi
import dev.kolektiv.kalendee.oauth.providers.GoogleOAuthClient
import dev.kolektiv.kalendee.oauth.providers.GoogleProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GoogleProviderTest {
    @Test
    fun authorizationUrlIncludesOfflineAccessAndPkce() {
        val client = GoogleOAuthClient(
            settings = ProviderOAuthSettings(clientId = "cid", clientSecret = "secret"),
            http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
        )
        val pkce = Pkce.generate()
        val url = client.authorizationUrl("state-1", pkce, "http://localhost:8080/api/v1/oauth/google/callback")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth"))
        assertTrue("client_id=cid" in url)
        assertTrue("access_type=offline" in url)
        assertTrue("code_challenge=" in url)
        assertTrue("calendar.readonly" in url)
    }

    @Test
    fun calendarListAndEventsMapFromGoogleJson() = runBlocking {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/users/me/calendarList") -> respond(
                    """{"items":[{"id":"primary","summary":"Mey","primary":true,"timeZone":"America/Chicago"}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                path.contains("/calendars/") -> respond(
                    """{"items":[{"id":"evt-1","summary":"Standup","status":"confirmed","etag":"\"1\"","start":{"dateTime":"2026-09-14T14:00:00Z"},"end":{"dateTime":"2026-09-14T14:30:00Z"}}],"nextSyncToken":"sync-2"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val api = GoogleCalendarApi(HttpClient(engine))
        val calendars = api.listCalendars("token")
        assertEquals(1, calendars.size)
        assertEquals("primary", calendars[0].id)
        assertTrue(calendars[0].primary)
        val page = api.listEvents("token", "primary")
        assertEquals("Standup", page.events.single().summary)
        assertEquals("sync-2", page.nextSyncToken)
        assertEquals(false, page.events.single().allDay)
    }

    @Test
    fun expiredSyncTokenReturnsGoneFlag() = runBlocking {
        val api = GoogleCalendarApi(
            HttpClient(
                MockEngine {
                    respond("{}", HttpStatusCode.Gone)
                },
            ),
        )
        val page = api.listEvents("token", "primary", syncToken = "stale")
        assertTrue(page.expiredSyncToken)
        assertTrue(page.events.isEmpty())
    }

    @Test
    fun providerIsDisabledWhenClientIdBlank() {
        val provider = GoogleProvider(
            settings = ProviderOAuthSettings(clientId = "  ", clientSecret = "x"),
            http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
        )
        assertEquals("google", provider.id)
        assertEquals(false, provider.enabled)
    }
}
