package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AuthResult
import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.api.OrganizationInvitationResultOut
import dev.kolektiv.kalendee.api.OrganizationInvitationsResponse
import dev.kolektiv.kalendee.api.OrganizationMembersResponse
import dev.kolektiv.kalendee.api.OrganizationMembershipResponse
import dev.kolektiv.kalendee.api.OrganizationSummaryOut
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.koin.ktor.ext.get

class OrganizationApiTest {
    @Test
    fun createUpdateDeleteLifecycle() = testApplication {
        lateinit var orgs: OrganizationService
        installApi(configure = { orgs = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")

        val created = alice.postJson(
            "/api/v1/organizations",
            """{"slug":"acme","displayName":"Acme Inc","description":"A cooperative"}""",
        )
        assertEquals(HttpStatusCode.Created, created.status)
        val summary = created.body<OrganizationSummaryOut>()
        assertEquals("acme", summary.slug)
        assertEquals("Acme Inc", summary.displayName)
        assertEquals("A cooperative", summary.description)
        assertEquals("private", summary.visibility)
        assertEquals("owner", summary.role)
        assertEquals(1, summary.memberCount)
        assertTrue(summary.id.isNotBlank())

        val duplicate = alice.postJson("/api/v1/organizations", """{"slug":"acme","displayName":"Other"}""")
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        val invalid = alice.postJson("/api/v1/organizations", """{"slug":"ab","displayName":"Tiny"}""")
        assertEquals(HttpStatusCode.BadRequest, invalid.status)

        orgs.addMember(aliceUser.id, OrganizationId(summary.id), bobUser.id, OrganizationRole.MEMBER)

        val memberUpdate = bob.patchJson("/api/v1/organizations/${summary.id}", """{"displayName":"Nope"}""")
        assertEquals(HttpStatusCode.Forbidden, memberUpdate.status)

        val updated = alice.patchJson(
            "/api/v1/organizations/${summary.id}",
            """{"displayName":"Acme Team","description":"Updated"}""",
        )
        assertEquals(HttpStatusCode.OK, updated.status)
        val updatedSummary = updated.body<OrganizationSummaryOut>()
        assertEquals("Acme Team", updatedSummary.displayName)
        assertEquals("Updated", updatedSummary.description)
        assertEquals("owner", updatedSummary.role)
        assertEquals(2, updatedSummary.memberCount)

        assertEquals(
            HttpStatusCode.Forbidden,
            bob.patchJson("/api/v1/organizations/${summary.id}", """{"visibility":"public"}""").status,
        )
        val published = alice.patchJson("/api/v1/organizations/${summary.id}", """{"visibility":"public"}""")
        assertEquals("public", published.body<OrganizationSummaryOut>().visibility)

        assertEquals(
            HttpStatusCode.NotFound,
            alice.patchJson(
                "/api/v1/organizations/${Uuid.random()}",
                """{"displayName":"Missing"}""",
            ).status,
        )

        assertEquals(HttpStatusCode.Forbidden, bob.delete("/api/v1/organizations/${summary.id}").status)
        assertEquals(HttpStatusCode.NoContent, alice.delete("/api/v1/organizations/${summary.id}").status)
        assertNull(orgs.byId(OrganizationId(summary.id)))
    }

    @Test
    fun patchTreatsExplicitNullsAsNoChange() = testApplication {
        lateinit var orgs: OrganizationService
        installApi(configure = { orgs = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val org = orgs.create(aliceUser.id, "acme", "Acme", description = "Original")
        val published = alice.patchJson("/api/v1/organizations/${org.id.value}", """{"visibility":"public"}""")
        assertEquals(HttpStatusCode.OK, published.status)

        val patched = alice.patchJson(
            "/api/v1/organizations/${org.id.value}",
            """{"displayName":"Renamed","description":null,"visibility":null}""",
        )
        assertEquals(HttpStatusCode.OK, patched.status)
        val summary = patched.body<OrganizationSummaryOut>()
        assertEquals("Renamed", summary.displayName)
        assertEquals("Original", summary.description)
        assertEquals("public", summary.visibility)
    }

    @Test
    fun membersResponseCarriesViewerFlags() = testApplication {
        lateinit var orgs: OrganizationService
        installApi(configure = { orgs = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")
        val carol = jsonClient()
        val carolUser = carol.registerAndLogin("carol")
        val dave = jsonClient()
        dave.registerAndLogin("dave")
        val org = orgs.create(aliceUser.id, "acme", "Acme")
        orgs.addMember(aliceUser.id, org.id, bobUser.id, OrganizationRole.ADMIN)
        orgs.addMember(aliceUser.id, org.id, carolUser.id, OrganizationRole.MEMBER)

        val ownerView = alice.get("/api/v1/organizations/${org.id.value}/members")
        assertEquals(HttpStatusCode.OK, ownerView.status)
        val owner = ownerView.body<OrganizationMembersResponse>()
        assertEquals(org.id.value, owner.organizationId)
        assertEquals("owner", owner.viewerRole)
        assertTrue(owner.canManageMembers)
        assertTrue(owner.canManageOwners)
        assertEquals(listOf("alice", "bob", "carol"), owner.members.map { it.username })
        assertEquals(listOf("owner", "admin", "member"), owner.members.map { it.role })
        assertEquals(listOf(true, false, false), owner.members.map { it.isSelf })
        assertTrue(owner.members.all { it.avatarUrl == null })

        val adminView = bob.get("/api/v1/organizations/${org.id.value}/members").body<OrganizationMembersResponse>()
        assertEquals("admin", adminView.viewerRole)
        assertTrue(adminView.canManageMembers)
        assertFalse(adminView.canManageOwners)

        val memberView = carol.get("/api/v1/organizations/${org.id.value}/members").body<OrganizationMembersResponse>()
        assertEquals("member", memberView.viewerRole)
        assertFalse(memberView.canManageMembers)
        assertFalse(memberView.canManageOwners)

        assertEquals(HttpStatusCode.Forbidden, dave.get("/api/v1/organizations/${org.id.value}/members").status)
        assertEquals(HttpStatusCode.NotFound, alice.get("/api/v1/organizations/${Uuid.random()}/members").status)
    }

    @Test
    fun memberRoleAndRemoval() = testApplication {
        lateinit var orgs: OrganizationService
        installApi(configure = { orgs = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")
        val org = orgs.create(aliceUser.id, "acme", "Acme")
        orgs.addMember(aliceUser.id, org.id, bobUser.id, OrganizationRole.MEMBER)

        val promoted = alice.patchJson(
            "/api/v1/organizations/${org.id.value}/members/${bobUser.id.value}",
            """{"role":"admin"}""",
        )
        assertEquals(HttpStatusCode.OK, promoted.status)
        val promotedMember = promoted.body<dev.kolektiv.kalendee.api.OrganizationMemberOut>()
        assertEquals(bobUser.id.value, promotedMember.userId)
        assertEquals("bob", promotedMember.username)
        assertEquals("admin", promotedMember.role)
        assertFalse(promotedMember.isSelf)

        assertEquals(
            HttpStatusCode.Forbidden,
            bob.patchJson(
                "/api/v1/organizations/${org.id.value}/members/${bobUser.id.value}",
                """{"role":"member"}""",
            ).status,
        )
        assertEquals(
            HttpStatusCode.OK,
            alice.patchJson(
                "/api/v1/organizations/${org.id.value}/members/${bobUser.id.value}",
                """{"role":"member"}""",
            ).status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            alice.delete("/api/v1/organizations/${org.id.value}/members/${bobUser.id.value}").status,
        )
        assertEquals(HttpStatusCode.Forbidden, bob.get("/api/v1/organizations/${org.id.value}/members").status)
    }

    @Test
    fun invitationsAcceptDeclineRevoke() = testApplication {
        lateinit var orgs: OrganizationService
        installApi(configure = { orgs = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")
        val carol = jsonClient()
        val carolUser = carol.registerAndLogin("carol")
        val org = orgs.create(aliceUser.id, "acme", "Acme")
        val orgId = org.id.value

        val invited = alice.postJson(
            "/api/v1/organizations/$orgId/invitations",
            """{"identifier":"bob","role":"member"}""",
        )
        assertEquals(HttpStatusCode.Created, invited.status)
        val invitation = invited.body<OrganizationInvitationResultOut>()
        assertEquals("pending", invitation.status)

        assertEquals(HttpStatusCode.Forbidden, bob.get("/api/v1/organizations/$orgId/invitations").status)

        val listed = alice.get("/api/v1/organizations/$orgId/invitations").body<OrganizationInvitationsResponse>()
        assertEquals(orgId, listed.organizationId)
        val listedInvitation = listed.invitations.single()
        assertEquals(invitation.id, listedInvitation.id)
        assertEquals("bob", listedInvitation.username)
        assertEquals(bobUser.id.value, listedInvitation.userId)
        assertEquals("member", listedInvitation.role)
        assertEquals("pending", listedInvitation.status)
        assertTrue(listedInvitation.createdAt.isNotBlank() && listedInvitation.expiresAt.isNotBlank())

        val accepted = bob.postJson(
            "/api/v1/organizations/invitations/accept",
            """{"invitationId":"${invitation.id}"}""",
        )
        assertEquals(HttpStatusCode.OK, accepted.status)
        val membership = accepted.body<OrganizationMembershipResponse>()
        assertEquals(orgId, membership.organization.id)
        assertEquals("member", membership.role)
        assertEquals("member", membership.organization.role)
        assertTrue(orgs.isMember(org.id, bobUser.id))

        val declinedInvite = alice.postJson(
            "/api/v1/organizations/$orgId/invitations",
            """{"identifier":"carol","role":"member"}""",
        ).body<OrganizationInvitationResultOut>()
        val declined = carol.postJson(
            "/api/v1/organizations/invitations/decline",
            """{"invitationId":"${declinedInvite.id}"}""",
        )
        assertEquals(HttpStatusCode.NoContent, declined.status)
        assertFalse(orgs.isMember(org.id, carolUser.id))
        assertEquals(
            HttpStatusCode.Conflict,
            carol.postJson(
                "/api/v1/organizations/invitations/accept",
                """{"invitationId":"${declinedInvite.id}"}""",
            ).status,
        )

        val revokedInvite = alice.postJson(
            "/api/v1/organizations/$orgId/invitations",
            """{"identifier":"carol","role":"admin"}""",
        ).body<OrganizationInvitationResultOut>()
        val revoked = alice.delete("/api/v1/organizations/$orgId/invitations/${revokedInvite.id}")
        assertEquals(HttpStatusCode.OK, revoked.status)
        val revokedOut = revoked.body<OrganizationInvitationResultOut>()
        assertEquals(revokedInvite.id, revokedOut.id)
        assertEquals("revoked", revokedOut.status)
    }

    @Test
    fun acceptByTokenAddsMembership() = testApplication {
        lateinit var orgs: OrganizationService
        val mail = RecordingMailer()
        installApi(mailer = mail, configure = { orgs = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val org = orgs.create(aliceUser.id, "acme", "Acme")

        alice.postJson(
            "/api/v1/organizations/${org.id.value}/invitations",
            """{"identifier":"newbie@example.com","role":"member"}""",
        )
        val sent = mail.sent.last { "inviteToken=" in it.text }
        val token = InviteTokenPattern.find(sent.text)?.groupValues?.get(1)
            ?: error("no invite token in mail")

        val newbie = jsonClient()
        val newbieUser = newbie.registerWithEmail("newbie", "newbie@example.com")
        val accepted = newbie.postJson("/api/v1/organizations/invitations/accept", """{"token":"$token"}""")
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals("member", accepted.body<OrganizationMembershipResponse>().role)
        assertTrue(orgs.isMember(org.id, newbieUser.id))
    }

    @Test
    fun staticInvitationRoutesAreNotShadowed() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")

        val accept = alice.postJson("/api/v1/organizations/invitations/accept", "{}")
        assertEquals(HttpStatusCode.BadRequest, accept.status)
        val acceptError = accept.body<ErrorBody>()
        assertEquals("invalid", acceptError.error)
        assertTrue(acceptError.message.contains("invitation id or token is required"))

        val decline = alice.postJson(
            "/api/v1/organizations/invitations/decline",
            """{"invitationId":""}""",
        )
        assertEquals(HttpStatusCode.BadRequest, decline.status)
        assertTrue(decline.body<ErrorBody>().message.contains("invitation id is required"))
    }
}

private suspend fun HttpClient.postJson(path: String, body: String): HttpResponse =
    post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

private suspend fun HttpClient.patchJson(path: String, body: String): HttpResponse =
    patch(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

private suspend fun HttpClient.registerWithEmail(username: String, email: String): User {
    val response = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterUser(username = username, password = "password12", email = email))
    }
    check(response.status == HttpStatusCode.Created) { "register failed: ${response.status}" }
    return response.body<AuthResult>().user ?: error("register did not create a session")
}

private val InviteTokenPattern = Regex("""inviteToken=([A-Za-z0-9_-]+)""")
