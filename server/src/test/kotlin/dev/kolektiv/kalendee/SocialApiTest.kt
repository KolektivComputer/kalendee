package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.FriendsResponse
import dev.kolektiv.kalendee.api.OrganizationsResponse
import dev.kolektiv.kalendee.friends.FriendshipService
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import org.koin.ktor.ext.get

class SocialApiTest {
    @Test
    fun anonymousRequestsAreUnauthorized() = testApplication {
        installApi()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/organizations").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/friends").status)
    }

    @Test
    fun organizationsListOnlyMembershipsWithRoleAndMemberCount() = testApplication {
        lateinit var orgs: OrganizationService
        installApi(configure = { orgs = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")
        val acme = orgs.create(aliceUser.id, "acme", "Acme Inc", "A cooperative")
        orgs.addMember(aliceUser.id, acme.id, bobUser.id, OrganizationRole.ADMIN)
        orgs.create(aliceUser.id, "private-team", "Private Team")

        val bobResponse = bob.get("/api/v1/organizations")
        assertEquals(HttpStatusCode.OK, bobResponse.status)
        val bobOrgs = bobResponse.body<OrganizationsResponse>().organizations
        assertEquals(1, bobOrgs.size)
        val summary = bobOrgs.single()
        assertEquals(acme.id.value, summary.id)
        assertEquals("acme", summary.slug)
        assertEquals("Acme Inc", summary.displayName)
        assertEquals("A cooperative", summary.description)
        assertEquals("private", summary.visibility)
        assertNull(summary.avatarUrl)
        assertEquals("admin", summary.role)
        assertEquals(2, summary.memberCount)
        assertTrue(bobResponse.bodyAsText().contains("\"avatarUrl\":null"))

        val aliceResponse = alice.get("/api/v1/organizations")
        val aliceOrgs = aliceResponse.body<OrganizationsResponse>().organizations
        assertEquals(listOf("acme", "private-team"), aliceOrgs.map { it.slug })
        assertEquals(listOf("owner", "owner"), aliceOrgs.map { it.role })
        assertEquals(listOf(2, 1), aliceOrgs.map { it.memberCount })
        assertNull(aliceOrgs[1].description)
        assertTrue(aliceResponse.bodyAsText().contains("\"description\":null"))
    }

    @Test
    fun friendsFlowExposesIncomingShapeAndAvatarUrl() = testApplication {
        lateinit var friendships: FriendshipService
        installApi(configure = { friendships = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")

        assertEquals("pending", friendships.sendRequest(aliceUser.id, "bob").status)

        val pending = bob.get("/api/v1/friends").body<FriendsResponse>()
        assertTrue(pending.friends.isEmpty())
        val incoming = pending.incoming.single()
        assertEquals(aliceUser.id.value, incoming.user.userId)
        assertEquals("alice", incoming.user.username)
        assertEquals("alice", incoming.user.displayName)
        assertNull(incoming.user.avatarUrl)
        assertTrue(Instant.parse(incoming.createdAt) <= Clock.System.now())

        friendships.accept(bobUser.id, friendships.incomingRequests(bobUser.id).single().id)

        val aliceFriends = alice.get("/api/v1/friends").body<FriendsResponse>()
        assertEquals(listOf("bob"), aliceFriends.friends.map { it.username })
        assertTrue(aliceFriends.incoming.isEmpty())

        uploadAvatar(alice)

        val bobFriends = bob.get("/api/v1/friends").body<FriendsResponse>()
        val friend = bobFriends.friends.single()
        assertEquals("alice", friend.username)
        val avatarUrl = friend.avatarUrl
        assertTrue(avatarUrl != null && avatarUrl.startsWith("/api/v1/users/${aliceUser.id.value}/avatar?v="))
    }

    @Test
    fun emptyListsSerializeAsArrays() = testApplication {
        installApi()
        val carol = jsonClient()
        carol.registerAndLogin("carol")

        assertEquals("""{"organizations":[]}""", carol.get("/api/v1/organizations").bodyAsText())
        assertEquals("""{"friends":[],"incoming":[]}""", carol.get("/api/v1/friends").bodyAsText())
    }
}

private suspend fun uploadAvatar(client: HttpClient) {
    val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03)
    val response = client.post("/api/v1/auth/me/avatar") {
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
    check(response.status == HttpStatusCode.OK) { "avatar upload failed: ${response.status}" }
}
