package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.NotificationOut
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.web.CalendarSharingOut
import dev.kolektiv.kalendee.web.FriendRequestOut
import dev.kolektiv.kalendee.web.FriendsOut
import dev.kolektiv.kalendee.web.UserSearchOut
import dev.kolektiv.keel.Keel
import dev.kolektiv.keel.KeelJson
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

class FriendshipApiTest {
    @Test
    fun sendAcceptLifecycle() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")

        val sent = alice.sendFriendRequest("bob")
        assertEquals(HttpStatusCode.OK, sent.status)
        val pending = sent.actionData<FriendRequestOut>()
        assertEquals("pending", pending.status)
        assertEquals("bob", pending.friend?.username)

        val bobState = bob.friendsState()
        assertTrue(bobState.friends.isEmpty())
        assertEquals(listOf("alice"), bobState.incoming.map { it.user.username })
        assertTrue(bobState.incoming.single().createdAt.isNotBlank())

        val aliceState = alice.friendsState()
        assertTrue(aliceState.friends.isEmpty())
        assertTrue(aliceState.incoming.isEmpty())

        val accepted = bob.respond("kalendee.acceptFriendRequest", bobState.incoming.single().id)
        assertEquals(HttpStatusCode.OK, accepted.status)
        val afterAccept = accepted.actionData<FriendsOut>()
        assertTrue(afterAccept.incoming.isEmpty())
        assertEquals(listOf("alice"), afterAccept.friends.map { it.username })

        val aliceAfter = alice.friendsState()
        assertEquals(listOf("bob"), aliceAfter.friends.map { it.username })
        assertTrue(aliceAfter.incoming.isEmpty())
    }

    @Test
    fun reversePendingRequestAutoAccepts() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")

        bob.sendFriendRequest("alice")
        val reverse = alice.sendFriendRequest("bob")
        assertEquals(HttpStatusCode.OK, reverse.status)
        val out = reverse.actionData<FriendRequestOut>()
        assertEquals("accepted", out.status)
        assertEquals("bob", out.friend?.username)

        assertEquals(listOf("bob"), alice.friendsState().friends.map { it.username })
        assertEquals(listOf("alice"), bob.friendsState().friends.map { it.username })
        assertTrue(bob.friendsState().incoming.isEmpty())
    }

    @Test
    fun selfDuplicateAndAlreadyFriendsAreRejected() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")

        val self = alice.sendFriendRequest("alice")
        assertEquals(HttpStatusCode.UnprocessableEntity, self.status)
        assertTrue(self.bodyAsText().contains("username"))
        assertTrue(self.bodyAsText().contains("you cannot add yourself"))

        assertEquals(HttpStatusCode.OK, alice.sendFriendRequest("bob").status)
        val duplicate = alice.sendFriendRequest("bob")
        assertEquals(HttpStatusCode.UnprocessableEntity, duplicate.status)
        assertTrue(duplicate.bodyAsText().contains("request already sent"))

        val request = bob.friendsState().incoming.single()
        bob.respond("kalendee.acceptFriendRequest", request.id)

        val alreadyFriends = alice.sendFriendRequest("bob")
        assertEquals(HttpStatusCode.UnprocessableEntity, alreadyFriends.status)
        assertTrue(alreadyFriends.bodyAsText().contains("already friends"))

        val unknown = alice.sendFriendRequest("nobody")
        assertEquals(HttpStatusCode.UnprocessableEntity, unknown.status)
        assertTrue(unknown.bodyAsText().contains("username"))
    }

    @Test
    fun declineRemovesRequest() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")

        alice.sendFriendRequest("bob")
        val request = bob.friendsState().incoming.single()
        val declined = bob.respond("kalendee.declineFriendRequest", request.id)
        assertEquals(HttpStatusCode.OK, declined.status)
        assertTrue(declined.actionData<FriendsOut>().incoming.isEmpty())

        assertTrue(bob.friendsState().incoming.isEmpty())
        assertTrue(alice.friendsState().friends.isEmpty())

        val again = bob.respond("kalendee.acceptFriendRequest", request.id)
        assertEquals(HttpStatusCode.UnprocessableEntity, again.status)
    }

    @Test
    fun removeDeletesFriendship() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")

        alice.sendFriendRequest("bob")
        bob.respond("kalendee.acceptFriendRequest", bob.friendsState().incoming.single().id)

        val removed = alice.post("${Keel.ACTION_PATH}/kalendee.removeFriend") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"${bobUser.id.value}"}""")
        }
        assertEquals(HttpStatusCode.OK, removed.status)
        assertTrue(removed.actionData<FriendsOut>().friends.isEmpty())
        assertTrue(alice.friendsState().friends.isEmpty())
        assertTrue(bob.friendsState().friends.isEmpty())
    }

    @Test
    fun searchReportsRelationshipStates() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")
        val carol = jsonClient()
        carol.registerAndLogin("carol")

        val initial = alice.searchUsers("bo")
        assertEquals(listOf("bob"), initial.results.map { it.username })
        assertEquals("none", initial.results.single().relationship)

        assertTrue(alice.searchUsers("alice").results.isEmpty())

        alice.sendFriendRequest("bob")
        val outgoing = alice.searchUsers("bo").results.single()
        assertEquals("pending_out", outgoing.relationship)
        val incoming = bob.searchUsers("ali").results.single()
        assertEquals("pending_in", incoming.relationship)

        bob.respond("kalendee.acceptFriendRequest", bob.friendsState().incoming.single().id)
        val friends = alice.searchUsers("bo").results.single()
        assertEquals("friends", friends.relationship)
        assertEquals(bobUser.id.value, friends.userId)
    }

    @Test
    fun shareDialogSuggestsAcceptedFriendsOnly() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val carol = jsonClient()
        carol.registerAndLogin("carol")

        alice.sendFriendRequest("bob")
        bob.respond("kalendee.acceptFriendRequest", bob.friendsState().incoming.single().id)

        val calendar = alice.createCalendar("Work")
        alice.post("/api/v1/calendars/${calendar.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"carol","permission":"read"}""")
        }

        val sharing = alice.post("${Keel.ACTION_PATH}/kalendee.getCalendarSharing") {
            contentType(ContentType.Application.Json)
            setBody("""{"calendarId":"${calendar.id.value}"}""")
        }
        assertEquals(HttpStatusCode.OK, sharing.status)
        val out = sharing.actionData<CalendarSharingOut>()
        assertEquals(listOf("bob"), out.friends.map { it.username })
        assertEquals(listOf("carol"), out.shares.map { it.username })
    }

    @Test
    fun friendRequestAndAcceptanceNotifyAndSendMail() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        val alice = jsonClient()
        alice.registerWithEmail("alice", "alice@example.com")
        val bob = jsonClient()
        bob.registerWithEmail("bob", "bob@example.com")
        mail.clear()

        alice.sendFriendRequest("bob")

        assertTrue(
            bob.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "friend.request" && it.title == "alice wants to be friends" },
        )
        assertTrue(mail.sent.any { it.to == "bob@example.com" && "alice wants to be friends" in it.subject })
        assertTrue(mail.sent.any { it.to == "bob@example.com" && "https://kalendee.test/" in it.text })

        val request = bob.friendsState().incoming.single()
        bob.respond("kalendee.acceptFriendRequest", request.id)

        assertTrue(
            alice.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "friend.accepted" && it.title == "bob accepted your friend request" },
        )
        assertTrue(mail.sent.any { it.to == "alice@example.com" && "bob accepted your friend request" in it.subject })
        assertTrue(mail.sent.any { it.to == "alice@example.com" && "https://kalendee.test/" in it.text })
    }

    @Test
    fun sharedCalendarSummaryExposesOwnerAvatarUrl() = testApplication {
        installApi()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")

        val calendar = alice.createCalendar("Work")
        alice.post("/api/v1/calendars/${calendar.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"bob","permission":"read"}""")
        }

        val before = bob.get("/") { header(KeelHeaders.VISIT, "true") }
        assertEquals(HttpStatusCode.OK, before.status)
        assertFalse(before.bodyAsText().contains("/api/v1/users/${aliceUser.id.value}/avatar"))

        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03)
        val upload = alice.post("/api/v1/auth/me/avatar") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "image/png")
                                append(HttpHeaders.ContentDisposition, "filename=\"avatar.png\"")
                            },
                        )
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, upload.status)

        val after = bob.get("/") { header(KeelHeaders.VISIT, "true") }
        assertEquals(HttpStatusCode.OK, after.status)
        assertTrue(after.bodyAsText().contains("/api/v1/users/${aliceUser.id.value}/avatar?v="))
    }
}

private suspend inline fun <reified T> HttpResponse.actionData(): T =
    KeelJson.codec.decodeFromJsonElement(
        KeelJson.codec.parseToJsonElement(bodyAsText()).jsonObject.getValue("data"),
    )

private suspend fun HttpClient.friendsState(): FriendsOut {
    val response = post("${Keel.ACTION_PATH}/kalendee.friends") {
        contentType(ContentType.Application.Json)
        setBody("{}")
    }
    check(response.status == HttpStatusCode.OK) { "friends failed: ${response.status}" }
    return response.actionData()
}

private suspend fun HttpClient.sendFriendRequest(username: String): HttpResponse =
    post("${Keel.ACTION_PATH}/kalendee.sendFriendRequest") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username"}""")
    }

private suspend fun HttpClient.respond(action: String, id: String): HttpResponse =
    post("${Keel.ACTION_PATH}/$action") {
        contentType(ContentType.Application.Json)
        setBody("""{"id":"$id"}""")
    }

private suspend fun HttpClient.searchUsers(query: String): UserSearchOut {
    val response = post("${Keel.ACTION_PATH}/kalendee.searchUsers") {
        contentType(ContentType.Application.Json)
        setBody("""{"query":"$query"}""")
    }
    check(response.status == HttpStatusCode.OK) { "search failed: ${response.status}" }
    return response.actionData()
}

private suspend fun HttpClient.createCalendar(name: String): Calendar {
    val response = post("/api/v1/calendars") {
        contentType(ContentType.Application.Json)
        setBody(CreateCalendar(displayName = name))
    }
    check(response.status == HttpStatusCode.Created) { "create calendar failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.registerWithEmail(username: String, email: String) {
    val response = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterUser(username = username, password = "password12", email = email))
    }
    check(response.status == HttpStatusCode.Created) { "register failed: ${response.status}" }
}
