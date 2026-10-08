package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AuthResult
import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.api.FriendRequestResultOut
import dev.kolektiv.kalendee.api.FriendsResponse
import dev.kolektiv.kalendee.api.NotificationOut
import dev.kolektiv.kalendee.api.UserSearchResponse
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FriendApiTest {
    @Test
    fun anonymousRequestsAreUnauthorized() = testApplication {
        installApi()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/friends").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.postJson("/api/v1/friends/requests", """{"username":"bob"}""").status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/users/search?q=bo").status)
    }

    @Test
    fun sendIncomingAcceptRemoveLifecycle() = testApplication {
        installApi()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")

        val sent = alice.postJson("/api/v1/friends/requests", """{"username":"bob"}""")
        assertEquals(HttpStatusCode.Created, sent.status)
        val result = sent.body<FriendRequestResultOut>()
        assertEquals("pending", result.status)
        assertEquals(bobUser.id.value, result.friend?.userId)
        assertEquals("bob", result.friend?.username)

        val bobState = bob.get("/api/v1/friends").body<FriendsResponse>()
        assertTrue(bobState.friends.isEmpty())
        assertEquals(listOf("alice"), bobState.incoming.map { it.user.username })
        assertEquals(aliceUser.id.value, bobState.incoming.single().user.userId)

        val requestId = bobState.incoming.single().id
        val accepted = bob.post("/api/v1/friends/requests/$requestId/accept")
        assertEquals(HttpStatusCode.OK, accepted.status)
        val afterAccept = accepted.body<FriendsResponse>()
        assertEquals(listOf("alice"), afterAccept.friends.map { it.username })
        assertTrue(afterAccept.incoming.isEmpty())

        val aliceState = alice.get("/api/v1/friends").body<FriendsResponse>()
        assertEquals(listOf("bob"), aliceState.friends.map { it.username })
        assertTrue(aliceState.incoming.isEmpty())

        val removed = alice.delete("/api/v1/friends/${bobUser.id.value}")
        assertEquals(HttpStatusCode.OK, removed.status)
        assertTrue(removed.body<FriendsResponse>().friends.isEmpty())
        assertTrue(bob.get("/api/v1/friends").body<FriendsResponse>().friends.isEmpty())
    }

    @Test
    fun reverseRequestAutoAccepts() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")

        assertEquals(
            HttpStatusCode.Created,
            bob.postJson("/api/v1/friends/requests", """{"username":"alice"}""").status,
        )
        val reverse = alice.postJson("/api/v1/friends/requests", """{"username":"bob"}""")
        assertEquals(HttpStatusCode.Created, reverse.status)
        val out = reverse.body<FriendRequestResultOut>()
        assertEquals("accepted", out.status)
        assertEquals("bob", out.friend?.username)

        assertEquals(listOf("bob"), alice.get("/api/v1/friends").body<FriendsResponse>().friends.map { it.username })
        assertEquals(listOf("alice"), bob.get("/api/v1/friends").body<FriendsResponse>().friends.map { it.username })
        assertTrue(bob.get("/api/v1/friends").body<FriendsResponse>().incoming.isEmpty())
    }

    @Test
    fun declineRemovesRequest() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")

        alice.postJson("/api/v1/friends/requests", """{"username":"bob"}""")
        val requestId = bob.get("/api/v1/friends").body<FriendsResponse>().incoming.single().id

        val declined = bob.post("/api/v1/friends/requests/$requestId/decline")
        assertEquals(HttpStatusCode.OK, declined.status)
        assertTrue(declined.body<FriendsResponse>().incoming.isEmpty())
        assertTrue(alice.get("/api/v1/friends").body<FriendsResponse>().friends.isEmpty())

        val again = bob.post("/api/v1/friends/requests/$requestId/accept")
        assertEquals(HttpStatusCode.UnprocessableEntity, again.status)
    }

    @Test
    fun domainErrorsAreUnprocessable() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")

        val self = alice.postJson("/api/v1/friends/requests", """{"username":"alice"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, self.status)
        val selfError = self.body<ErrorBody>()
        assertEquals("invalid", selfError.error)
        assertTrue(selfError.message.contains("you cannot add yourself"))

        val unknown = alice.postJson("/api/v1/friends/requests", """{"username":"nobody"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, unknown.status)
        val unknownError = unknown.body<ErrorBody>()
        assertEquals("not_found", unknownError.error)

        alice.postJson("/api/v1/friends/requests", """{"username":"bob"}""")
        val duplicate = alice.postJson("/api/v1/friends/requests", """{"username":"bob"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, duplicate.status)
        assertEquals("conflict", duplicate.body<ErrorBody>().error)

        val missing = Uuid.random().toString()
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            bob.post("/api/v1/friends/requests/$missing/accept").status,
        )
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            bob.post("/api/v1/friends/requests/not-a-uuid/accept").status,
        )
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            bob.post("/api/v1/friends/requests/$missing/decline").status,
        )
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            alice.delete("/api/v1/friends/not-a-uuid").status,
        )
    }

    @Test
    fun searchReportsRelationshipStatesAndExcludesSelf() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")
        val carol = jsonClient()
        carol.registerAndLogin("carol")

        assertTrue(alice.search("ali").results.isEmpty())
        assertEquals("none", alice.search("bo").results.single().relationship)

        alice.postJson("/api/v1/friends/requests", """{"username":"bob"}""")
        assertEquals("pending_out", alice.search("bo").results.single().relationship)
        assertEquals("pending_in", bob.search("ali").results.single().relationship)
        assertEquals("none", bob.search("car").results.single().relationship)

        bob.post(
            "/api/v1/friends/requests/${bob.get("/api/v1/friends").body<FriendsResponse>().incoming.single().id}/accept",
        )
        val friends = alice.search("bo").results.single()
        assertEquals("friends", friends.relationship)
        assertEquals(bobUser.id.value, friends.userId)

        assertEquals(1, alice.search("o", limit = 1).results.size)
    }

    @Test
    fun jsonRequestsNotifyAndSendMail() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        val alice = jsonClient()
        alice.registerWithEmail("alice", "alice@example.com")
        val bob = jsonClient()
        bob.registerWithEmail("bob", "bob@example.com")
        mail.clear()

        alice.postJson("/api/v1/friends/requests", """{"username":"bob"}""")

        assertTrue(
            bob.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "friend.request" && it.title == "alice wants to be friends" },
        )
        assertTrue(mail.sent.any { it.to == "bob@example.com" && "alice wants to be friends" in it.subject })

        val requestId = bob.get("/api/v1/friends").body<FriendsResponse>().incoming.single().id
        bob.post("/api/v1/friends/requests/$requestId/accept")

        assertTrue(
            alice.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "friend.accepted" && it.title == "bob accepted your friend request" },
        )
        assertTrue(mail.sent.any { it.to == "alice@example.com" && "bob accepted your friend request" in it.subject })
    }
}

private suspend fun HttpClient.postJson(path: String, body: String): HttpResponse =
    post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

private suspend fun HttpClient.search(query: String, limit: Int = 10): UserSearchResponse =
    get("/api/v1/users/search?q=$query&limit=$limit").body()

private suspend fun HttpClient.registerWithEmail(username: String, email: String): User {
    val response = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterUser(username = username, password = "password12", email = email))
    }
    check(response.status == HttpStatusCode.Created) { "register failed: ${response.status}" }
    return response.body<AuthResult>().user ?: error("register did not create a session")
}
