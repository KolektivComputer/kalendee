package dev.kolektiv.kalendee.client

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

private val mutationJsonHeaders: Headers =
    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private const val MUTATION_SESSION_COOKIE = "kalendee_session=stored"

private const val EMPTY_FRIENDS_JSON = """{"friends":[],"incoming":[]}"""

private suspend fun HttpRequestData.jsonBody(): JsonObject =
    Json.parseToJsonElement(body.toByteArray().decodeToString()).jsonObject

private fun primitives(body: JsonObject): Map<String, String?> =
    body.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull }

class SocialMutationsApiTest {
    @Test
    fun sendFriendRequestPostsUsernameAndParsesAutoAccept() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/friends/requests", request.url.encodedPath)
            assertEquals(MUTATION_SESSION_COOKIE, request.headers[HttpHeaders.Cookie])
            assertEquals(mapOf("username" to "carol"), primitives(request.jsonBody()))
            respond(
                content = """
                    {
                      "status": "accepted",
                      "friend": {
                        "userId": "u-carol",
                        "username": "carol",
                        "displayName": "Carol",
                        "avatarUrl": "https://cdn.example/avatars/carol.png"
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = mutationJsonHeaders,
            )
        }

        val api = KalendeeApi(
            baseUrl = "https://calendar.example",
            tokenProvider = { MUTATION_SESSION_COOKIE },
            engine = engine,
        )
        val result = api.sendFriendRequest("carol")

        assertEquals("accepted", result.status)
        assertEquals("u-carol", result.friend?.userId)
        assertEquals("carol", result.friend?.username)
        assertEquals("Carol", result.friend?.displayName)
        assertEquals("https://cdn.example/avatars/carol.png", result.friend?.avatarUrl)
    }

    @Test
    fun pendingFriendRequestParsesWithoutFriend() = runTest {
        val engine = MockEngine {
            respond("""{"status":"pending","friend":null}""", HttpStatusCode.OK, mutationJsonHeaders)
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val result = api.sendFriendRequest("dave")

        assertEquals("pending", result.status)
        assertNull(result.friend)
    }

    @Test
    fun acceptAndDeclineFriendRequestsUseExpectedPaths() = runTest {
        val calls = mutableListOf<Pair<HttpMethod, String>>()
        val engine = MockEngine { request ->
            calls += request.method to request.url.encodedPath
            respond(EMPTY_FRIENDS_JSON, HttpStatusCode.OK, mutationJsonHeaders)
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val accepted = api.acceptFriendRequest("req-1")
        val declined = api.declineFriendRequest("req-2")

        assertEquals(
            listOf(
                HttpMethod.Post to "/api/v1/friends/requests/req-1/accept",
                HttpMethod.Post to "/api/v1/friends/requests/req-2/decline",
            ),
            calls,
        )
        assertTrue(accepted.friends.isEmpty())
        assertTrue(declined.incoming.isEmpty())
    }

    @Test
    fun removeFriendDeletesUserPathAndParsesFriends() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/api/v1/friends/u-bob", request.url.encodedPath)
            respond(
                content = """
                    {
                      "friends": [],
                      "incoming": [
                        {
                          "id": "req-3",
                          "user": {"userId": "u-carol", "username": "carol", "displayName": "Carol"},
                          "createdAt": "2026-10-06T09:00:00Z"
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = mutationJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val response = api.removeFriend("u-bob")

        assertTrue(response.friends.isEmpty())
        assertEquals("req-3", response.incoming.single().id)
    }

    @Test
    fun searchUsersEncodesQueryAndParsesRelationships() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/users/search", request.url.encodedPath)
            assertEquals("bob smith & co", request.url.parameters["q"])
            assertEquals("5", request.url.parameters["limit"])
            respond(
                content = """
                    {
                      "results": [
                        {
                          "userId": "u-bob",
                          "username": "bob",
                          "displayName": "Bob",
                          "avatarUrl": "https://cdn.example/avatars/bob.png",
                          "relationship": "friends"
                        },
                        {
                          "userId": "u-carol",
                          "username": "carol",
                          "displayName": "Carol",
                          "relationship": "pending_in"
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = mutationJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val results = api.searchUsers("bob smith & co", limit = 5)

        assertEquals(2, results.size)
        assertEquals("u-bob", results[0].userId)
        assertEquals("friends", results[0].relationship)
        assertEquals("https://cdn.example/avatars/bob.png", results[0].avatarUrl)
        assertEquals("pending_in", results[1].relationship)
        assertNull(results[1].avatarUrl)
    }

    @Test
    fun searchUsersOmitsBlankQueryAndDefaultsRelationship() = runTest {
        val engine = MockEngine {
            respond("""{"results":[{"userId":"u-1","username":"a","displayName":"A"}]}""", HttpStatusCode.OK, mutationJsonHeaders)
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val results = api.searchUsers("")

        assertEquals("none", results.single().relationship)
    }

    @Test
    fun createOrganizationPostsCommand() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/organizations", request.url.encodedPath)
            assertEquals(
                mapOf("slug" to "acme", "displayName" to "Acme Inc", "description" to "Calendars"),
                primitives(request.jsonBody()),
            )
            respond(
                content = """
                    {
                      "id": "org-9",
                      "slug": "acme",
                      "displayName": "Acme Inc",
                      "description": "Calendars",
                      "visibility": "private",
                      "role": "owner",
                      "memberCount": 1
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = mutationJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val organization = api.createOrganization(
            CreateOrganizationBody(slug = "acme", displayName = "Acme Inc", description = "Calendars"),
        )

        assertEquals("org-9", organization.id)
        assertEquals("acme", organization.slug)
        assertEquals("owner", organization.role)
    }

    @Test
    fun setOrganizationMemberRolePatchesRole() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Patch, request.method)
            assertEquals("/api/v1/organizations/org-1/members/u-bob", request.url.encodedPath)
            assertEquals(mapOf("role" to "owner"), primitives(request.jsonBody()))
            respond(
                content = """
                    {
                      "userId": "u-bob",
                      "username": "bob",
                      "displayName": "Bob",
                      "avatarUrl": null,
                      "role": "owner",
                      "isSelf": false
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = mutationJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val member = api.setOrganizationMemberRole("org-1", "u-bob", role = "owner")

        assertEquals("u-bob", member.userId)
        assertEquals("owner", member.role)
        assertEquals(false, member.isSelf)
    }

    @Test
    fun organizationMembersParsesViewerCapabilities() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/organizations/org-1/members", request.url.encodedPath)
            respond(
                content = """
                    {
                      "organizationId": "org-1",
                      "viewerRole": "owner",
                      "canManageMembers": true,
                      "canManageOwners": true,
                      "members": [
                        {
                          "userId": "u-alice",
                          "username": "alice",
                          "displayName": "Alice",
                          "avatarUrl": "https://cdn.example/avatars/alice.png",
                          "role": "owner",
                          "isSelf": true
                        },
                        {
                          "userId": "u-bob",
                          "username": "bob",
                          "displayName": "Bob",
                          "role": "member",
                          "isSelf": false
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = mutationJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val response = api.organizationMembers("org-1")

        assertEquals("org-1", response.organizationId)
        assertEquals("owner", response.viewerRole)
        assertTrue(response.canManageMembers)
        assertTrue(response.canManageOwners)
        assertEquals(2, response.members.size)
        assertTrue(response.members[0].isSelf)
        assertNull(response.members[1].avatarUrl)
    }

    @Test
    fun invitationLifecyclePostsAndParsesInvitations() = runTest {
        var call = 0
        val engine = MockEngine { request ->
            call += 1
            when (call) {
                1 -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("/api/v1/organizations/org-1/invitations", request.url.encodedPath)
                    assertEquals(
                        mapOf("identifier" to "newbie@example.com", "role" to "member"),
                        primitives(request.jsonBody()),
                    )
                    respond(
                        """{"id":"inv-7","status":"pending"}""",
                        HttpStatusCode.OK,
                        mutationJsonHeaders,
                    )
                }
                else -> {
                    assertEquals(HttpMethod.Delete, request.method)
                    assertEquals("/api/v1/organizations/org-1/invitations/inv-7", request.url.encodedPath)
                    respond(
                        """{"id":"inv-7","status":"revoked"}""",
                        HttpStatusCode.OK,
                        mutationJsonHeaders,
                    )
                }
            }
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val invitation = api.inviteToOrganization("org-1", "newbie@example.com")
        assertEquals("inv-7", invitation.id)
        assertEquals("pending", invitation.status)

        val revoked = api.revokeOrganizationInvitation("org-1", "inv-7")
        assertEquals("revoked", revoked.status)
    }

    @Test
    fun organizationInvitationsParsesPendingList() = runTest {
        val engine = MockEngine {
            respond(
                content = """
                    {
                      "organizationId": "org-1",
                      "invitations": [
                        {
                          "id": "inv-1",
                          "email": "newbie@example.com",
                          "userId": null,
                          "username": null,
                          "displayName": null,
                          "role": "member",
                          "status": "pending",
                          "createdAt": "2026-10-01T10:00:00Z",
                          "expiresAt": "2026-10-15T10:00:00Z"
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = mutationJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val response = api.organizationInvitations("org-1")

        assertEquals("org-1", response.organizationId)
        val invitation = response.invitations.single()
        assertEquals("inv-1", invitation.id)
        assertEquals("newbie@example.com", invitation.email)
        assertEquals("2026-10-15T10:00:00Z", invitation.expiresAt)
    }

    @Test
    fun acceptOrganizationInvitationPostsToken() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/organizations/invitations/accept", request.url.encodedPath)
            assertEquals("tok-1", request.jsonBody()["token"]?.jsonPrimitive?.content)
            respond(
                content = """
                    {
                      "organization": {
                        "id": "org-1",
                        "slug": "acme",
                        "displayName": "Acme Inc",
                        "visibility": "private",
                        "memberCount": 3
                      },
                      "role": "member"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = mutationJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val response = api.acceptOrganizationInvitation(token = "tok-1")

        assertEquals("org-1", response.organization.id)
        assertEquals("acme", response.organization.slug)
        assertEquals("member", response.role)
    }

    @Test
    fun createTeamPostsCommandAndGrantCalendarPutsPermission() = runTest {
        var call = 0
        val engine = MockEngine { request ->
            call += 1
            when (call) {
                1 -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("/api/v1/organizations/org-1/teams", request.url.encodedPath)
                    assertEquals(
                        mapOf("slug" to "eng", "name" to "Engineering", "description" to "Core team"),
                        primitives(request.jsonBody()),
                    )
                    respond(TEAM_JSON, HttpStatusCode.OK, mutationJsonHeaders)
                }
                else -> {
                    assertEquals(HttpMethod.Put, request.method)
                    assertEquals("/api/v1/teams/team-1/calendars/cal-1", request.url.encodedPath)
                    assertEquals(mapOf("permission" to "write"), primitives(request.jsonBody()))
                    respond(
                        """
                            {
                              "calendarId": "cal-1",
                              "displayName": "Team calendar",
                              "color": "#ff0000",
                              "permission": "write"
                            }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        mutationJsonHeaders,
                    )
                }
            }
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val team = api.createOrganizationTeam(
            "org-1",
            CreateOrganizationTeamBody(slug = "eng", name = "Engineering", description = "Core team"),
        )

        assertEquals("team-1", team.id)
        assertEquals("eng", team.slug)
        assertEquals(2, team.memberCount)
        assertEquals(2, team.members.size)
        assertEquals("manager", team.members[0].role)
        assertTrue(team.members[0].isSelf)
        assertNull(team.members[1].avatarUrl)
        assertTrue(team.canManageMembers)
        assertTrue(team.canManageGrants)
        assertEquals(1, team.grants.size)
        assertEquals("write", team.grants.single().permission)

        val grant = api.grantCalendarToTeam("team-1", "cal-1", permission = "write")

        assertEquals("cal-1", grant.calendarId)
        assertEquals("#ff0000", grant.color)
        assertEquals("write", grant.permission)
    }

    @Test
    fun organizationTeamsParsesTeamsAndManageableCalendars() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/organizations/org-1/teams", request.url.encodedPath)
            respond(
                content = """
                    {
                      "organizationId": "org-1",
                      "viewerRole": "manager",
                      "canManageTeams": true,
                      "teams": [],
                      "manageableCalendars": [
                        {"id": "cal-1", "displayName": "Team calendar", "color": "#00ff00"}
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = mutationJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val response = api.organizationTeams("org-1")

        assertEquals("org-1", response.organizationId)
        assertEquals("manager", response.viewerRole)
        assertTrue(response.canManageTeams)
        assertTrue(response.teams.isEmpty())
        assertEquals("Team calendar", response.manageableCalendars.single().displayName)
    }

    @Test
    fun teamMembershipMutationsUseExpectedPathsAndBodies() = runTest {
        val calls = mutableListOf<Triple<HttpMethod, String, Map<String, String?>>>()
        val engine = MockEngine { request ->
            calls += Triple(request.method, request.url.encodedPath, primitives(request.jsonBody()))
            respond(
                """
                    {
                      "userId": "u-bob",
                      "username": "bob",
                      "displayName": "Bob",
                      "avatarUrl": null,
                      "role": "member",
                      "isSelf": false
                    }
                """.trimIndent(),
                HttpStatusCode.OK,
                mutationJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        api.addOrganizationTeamMember("team-1", "u-bob")
        api.setOrganizationTeamMemberRole("team-1", "u-bob", role = "lead")

        assertEquals(
            listOf(
                Triple(HttpMethod.Post, "/api/v1/teams/team-1/members", mapOf<String, String?>("userId" to "u-bob")),
                Triple(HttpMethod.Patch, "/api/v1/teams/team-1/members/u-bob", mapOf<String, String?>("role" to "lead")),
            ),
            calls,
        )
    }

    @Test
    fun deleteEndpointsAcceptNoContent() = runTest {
        val calls = mutableListOf<Pair<HttpMethod, String>>()
        val engine = MockEngine { request ->
            calls += request.method to request.url.encodedPath
            respond(content = "", status = HttpStatusCode.NoContent)
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        api.deleteOrganization("org-1")
        api.removeOrganizationMember("org-1", "u-bob")
        api.deleteOrganizationTeam("team-1")
        api.removeOrganizationTeamMember("team-1", "u-bob")
        api.revokeCalendarFromTeam("team-1", "cal-1")
        api.declineOrganizationInvitation("inv-1")

        assertEquals(
            listOf(
                HttpMethod.Delete to "/api/v1/organizations/org-1",
                HttpMethod.Delete to "/api/v1/organizations/org-1/members/u-bob",
                HttpMethod.Delete to "/api/v1/teams/team-1",
                HttpMethod.Delete to "/api/v1/teams/team-1/members/u-bob",
                HttpMethod.Delete to "/api/v1/teams/team-1/calendars/cal-1",
                HttpMethod.Post to "/api/v1/organizations/invitations/decline",
            ),
            calls,
        )
    }

    @Test
    fun forbiddenOrganizationMutationMapsToTypedException() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"forbidden","message":"forbidden"}""",
                status = HttpStatusCode.Forbidden,
                headers = mutationJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val failure = assertFailsWith<KalendeeApiException> {
            api.createOrganization(CreateOrganizationBody(slug = "acme", displayName = "Acme Inc"))
        }

        assertEquals(403, failure.status)
        assertEquals("forbidden", failure.code)
        assertTrue(!failure.isUnauthorized)
    }

    @Test
    fun noContentMutationsThrowTypedExceptionOnFailure() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"error":"not_found","message":"not found"}""",
                status = HttpStatusCode.NotFound,
                headers = mutationJsonHeaders,
            )
        }

        val api = KalendeeApi("https://calendar.example", engine = engine)
        val failure = assertFailsWith<KalendeeApiException> { api.deleteOrganizationTeam("missing") }

        assertEquals(404, failure.status)
        assertEquals("not_found", failure.code)
    }
}

private val TEAM_JSON = """
    {
      "id": "team-1",
      "organizationId": "org-1",
      "slug": "eng",
      "name": "Engineering",
      "description": null,
      "isDefault": false,
      "memberCount": 2,
      "viewerRole": "manager",
      "canManageMembers": true,
      "canManageGrants": true,
      "canDelete": false,
      "members": [
        {
          "userId": "u-alice",
          "username": "alice",
          "displayName": "Alice",
          "avatarUrl": "https://cdn.example/avatars/alice.png",
          "role": "manager",
          "isSelf": true
        },
        {
          "userId": "u-bob",
          "username": "bob",
          "displayName": "Bob",
          "role": "member",
          "isSelf": false
        }
      ],
      "grants": [
        {"calendarId": "cal-1", "displayName": "Team calendar", "color": "#ff0000", "permission": "write"}
      ]
    }
""".trimIndent()
