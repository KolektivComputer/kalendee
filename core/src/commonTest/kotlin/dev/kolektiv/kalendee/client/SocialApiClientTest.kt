package dev.kolektiv.kalendee.client

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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val socialJsonHeaders: Headers =
    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private const val SESSION_COOKIE = "kalendee_session=stored"

private val ORGANIZATIONS_JSON = """
    {
      "organizations": [
        {
          "id": "6f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d",
          "slug": "acme",
          "displayName": "Acme Inc",
          "description": "Calendars for Acme",
          "visibility": "public",
          "avatarUrl": "https://cdn.example/orgs/acme.png",
          "role": "owner",
          "memberCount": 12
        },
        {
          "id": "7a2c3d4e-5f6a-4b7c-9d0e-1f2a3b4c5d6e",
          "slug": "labs",
          "displayName": "Labs",
          "description": null,
          "visibility": "private",
          "avatarUrl": null,
          "role": null,
          "memberCount": 0
        }
      ]
    }
""".trimIndent()

private val FRIENDS_JSON = """
    {
      "friends": [
        {
          "userId": "8b3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f",
          "username": "bob",
          "displayName": "Bob",
          "avatarUrl": null
        }
      ],
      "incoming": [
        {
          "id": "req-1",
          "user": {
            "userId": "9c4e5f6a-7b8c-4d9e-0f1a-2b3c4d5e6f7a",
            "username": "carol",
            "displayName": "Carol",
            "avatarUrl": "https://cdn.example/avatars/carol.png"
          },
          "createdAt": "2026-10-05T12:00:00Z"
        }
      ]
    }
""".trimIndent()

class SocialApiClientTest {
    @Test
    fun organizationsSendsSessionCookieAndParsesSummaries() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/organizations", request.url.encodedPath)
            assertEquals(SESSION_COOKIE, request.headers[HttpHeaders.Cookie])
            respond(ORGANIZATIONS_JSON, HttpStatusCode.OK, socialJsonHeaders)
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { SESSION_COOKIE },
            engine = engine,
        )
        val organizations = api.organizations()

        assertEquals(2, organizations.size)
        val acme = organizations[0]
        assertEquals("6f1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d", acme.id)
        assertEquals("acme", acme.slug)
        assertEquals("Acme Inc", acme.displayName)
        assertEquals("Calendars for Acme", acme.description)
        assertEquals("public", acme.visibility)
        assertEquals("https://cdn.example/orgs/acme.png", acme.avatarUrl)
        assertEquals("owner", acme.role)
        assertEquals(12, acme.memberCount)

        val labs = organizations[1]
        assertEquals("labs", labs.slug)
        assertNull(labs.description)
        assertEquals("private", labs.visibility)
        assertNull(labs.avatarUrl)
        assertNull(labs.role)
        assertEquals(0, labs.memberCount)
    }

    @Test
    fun organizationsParseEmptyList() = runTest {
        val engine = MockEngine {
            respond("""{"organizations":[]}""", HttpStatusCode.OK, socialJsonHeaders)
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)

        assertTrue(api.organizations().isEmpty())
    }

    @Test
    fun friendsSendsSessionCookieAndParsesIncoming() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/friends", request.url.encodedPath)
            assertEquals(SESSION_COOKIE, request.headers[HttpHeaders.Cookie])
            respond(FRIENDS_JSON, HttpStatusCode.OK, socialJsonHeaders)
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { SESSION_COOKIE },
            engine = engine,
        )
        val response = api.friends()

        assertEquals(1, response.friends.size)
        val bob = response.friends.single()
        assertEquals("8b3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f", bob.userId)
        assertEquals("bob", bob.username)
        assertEquals("Bob", bob.displayName)
        assertNull(bob.avatarUrl)

        assertEquals(1, response.incoming.size)
        val request = response.incoming.single()
        assertEquals("req-1", request.id)
        assertEquals("carol", request.user.username)
        assertEquals("Carol", request.user.displayName)
        assertEquals("https://cdn.example/avatars/carol.png", request.user.avatarUrl)
        assertEquals("2026-10-05T12:00:00Z", request.createdAt)
    }

    @Test
    fun friendsParseEmptyLists() = runTest {
        val engine = MockEngine {
            respond("""{"friends":[],"incoming":[]}""", HttpStatusCode.OK, socialJsonHeaders)
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val response = api.friends()

        assertTrue(response.friends.isEmpty())
        assertTrue(response.incoming.isEmpty())
    }

    @Test
    fun unauthorizedOrganizationsMapsToTypedException() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"unauthorized","message":"unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = socialJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val failure = assertFailsWith<KalendeeApiException> { api.organizations() }

        assertEquals(401, failure.status)
        assertEquals("unauthorized", failure.code)
        assertTrue(failure.isUnauthorized)
    }

    @Test
    fun unauthorizedFriendsMapsToTypedException() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"unauthorized","message":"unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = socialJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val failure = assertFailsWith<KalendeeApiException> { api.friends() }

        assertEquals(401, failure.status)
        assertTrue(failure.isUnauthorized)
    }
}
