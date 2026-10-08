package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AuthResult
import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.api.OrganizationInvitationResultOut
import dev.kolektiv.kalendee.api.OrganizationSummaryOut
import dev.kolektiv.kalendee.api.PublicCalendarOut
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.web.CalendarSharingOut
import dev.kolektiv.kalendee.web.OrganizationProfilePage
import dev.kolektiv.kalendee.web.PublicDirectoryPage
import dev.kolektiv.kalendee.web.PublicProfilePage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicReadApiTest {
    @Test
    fun unknownProfileAndOrganizationAreNotFound() = testApplication {
        installApi()
        val anonymous = jsonClient()

        val profile = anonymous.get("/api/v1/public/users/ghost")
        assertEquals(HttpStatusCode.NotFound, profile.status, profile.bodyAsText())
        assertEquals("not_found", profile.body<ErrorBody>().error)

        val organization = anonymous.get("/api/v1/public/orgs/ghost")
        assertEquals(HttpStatusCode.NotFound, organization.status, organization.bodyAsText())
        assertEquals("not_found", organization.body<ErrorBody>().error)
    }

    @Test
    fun publicProfileFollowsInstanceAccessAndSelfFlags() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val anonymous = jsonClient()

        val calendar = bob.newCalendar("Bob Public")
        val token = assertNotNull(bob.enablePublicLink(calendar.id.value).publicLinkToken)

        val public = anonymous.get("/api/v1/public/users/bob")
        assertEquals(HttpStatusCode.OK, public.status, public.bodyAsText())
        val publicProfile = public.body<PublicProfilePage>()
        assertEquals("bob", publicProfile.username)
        assertFalse(publicProfile.isSelf)
        assertNull(publicProfile.viewer)
        assertEquals(listOf(calendar.id.value), publicProfile.calendars.map { it.id })
        assertEquals(token, publicProfile.calendars.single().publicLinkToken)

        alice.setInstancePublicAccess("signed_in")
        assertEquals(HttpStatusCode.NotFound, anonymous.get("/api/v1/public/users/bob").status)

        val self = bob.get("/api/v1/public/users/bob")
        assertEquals(HttpStatusCode.OK, self.status, self.bodyAsText())
        val selfProfile = self.body<PublicProfilePage>()
        assertTrue(selfProfile.isSelf)
        assertEquals("bob", selfProfile.viewer?.username)
        assertEquals(listOf(calendar.id.value), selfProfile.calendars.map { it.id })

        val other = alice.get("/api/v1/public/users/bob")
        assertEquals(HttpStatusCode.OK, other.status, other.bodyAsText())
        val otherProfile = other.body<PublicProfilePage>()
        assertFalse(otherProfile.isSelf)
        assertEquals("alice", otherProfile.viewer?.username)
    }

    @Test
    fun organizationProfileRespectsVisibilityAndInvitations() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val guest = jsonClient()
        val anonymous = jsonClient()

        val organization = alice.newOrganization("acme", "Acme")
        val calendar = alice.newCalendar("Team", organizationId = organization.id)
        val token = assertNotNull(alice.enablePublicLink(calendar.id.value).publicLinkToken)

        // Private organization: anonymous and signed-in outsiders are not found.
        assertEquals(HttpStatusCode.NotFound, anonymous.get("/api/v1/public/orgs/acme").status)
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/v1/public/orgs/acme").status)

        val owner = alice.get("/api/v1/public/orgs/acme")
        assertEquals(HttpStatusCode.OK, owner.status, owner.bodyAsText())
        val ownerPage = owner.body<OrganizationProfilePage>()
        assertEquals("owner", ownerPage.viewerRole)
        assertTrue(ownerPage.canManageSettings)
        assertEquals(listOf("alice"), ownerPage.members.map { it.username })
        assertEquals(listOf(calendar.id.value), ownerPage.calendars.map { it.id })
        assertEquals(token, ownerPage.calendars.single().publicLinkToken)

        // Public organization: anonymous access with the member list, no viewer role.
        alice.updateOrganizationVisibility(organization.id, "public")
        val public = anonymous.get("/api/v1/public/orgs/acme")
        assertEquals(HttpStatusCode.OK, public.status, public.bodyAsText())
        val publicPage = public.body<OrganizationProfilePage>()
        assertEquals("public", publicPage.org.visibility)
        assertEquals(listOf("alice"), publicPage.members.map { it.username })
        assertEquals(listOf(calendar.id.value), publicPage.calendars.map { it.id })
        assertNull(publicPage.viewer)
        assertNull(publicPage.viewerRole)

        // Private again: a pending invitation grants session access, membership
        // then exposes the members list.
        alice.updateOrganizationVisibility(organization.id, "private")
        val invitation = alice.inviteToOrganization(organization.id, "bob")
        assertEquals("pending", invitation.status)

        val invited = bob.get("/api/v1/public/orgs/acme")
        assertEquals(HttpStatusCode.OK, invited.status, invited.bodyAsText())
        val invitedPage = invited.body<OrganizationProfilePage>()
        assertEquals(invitation.id, invitedPage.pendingInvitation?.id)
        assertEquals(null, invitedPage.viewerRole)
        assertTrue(invitedPage.members.isEmpty(), "non-members of private orgs do not see members")

        bob.acceptInvitation(invitation.id)
        val member = bob.get("/api/v1/public/orgs/acme").body<OrganizationProfilePage>()
        assertEquals("member", member.viewerRole)
        assertEquals(setOf("alice", "bob"), member.members.map { it.username }.toSet())

        // Email invite token: anonymous is still not found, the matching signed-in
        // invitee sees the pending invitation and gets the token echoed back.
        mail.clear()
        val emailInvitation = alice.inviteToOrganization(organization.id, "guest@example.com")
        val sent = mail.sent.last { "inviteToken=" in it.text }
        val inviteToken = assertNotNull(
            OrgInviteTokenPattern.find(sent.text)?.groupValues?.get(1),
        )
        assertEquals(
            HttpStatusCode.NotFound,
            anonymous.get("/api/v1/public/orgs/acme?invite=$inviteToken").status,
        )

        guest.registerWithEmail("guest", "guest@example.com")
        assertEquals(
            HttpStatusCode.NotFound,
            guest.get("/api/v1/public/orgs/acme?invite=bogus").status,
        )
        val invitedByToken = guest.get("/api/v1/public/orgs/acme?invite=$inviteToken")
        assertEquals(HttpStatusCode.OK, invitedByToken.status, invitedByToken.bodyAsText())
        val invitedByTokenPage = invitedByToken.body<OrganizationProfilePage>()
        assertEquals(emailInvitation.id, invitedByTokenPage.pendingInvitation?.id)
        assertEquals("guest@example.com", invitedByTokenPage.pendingInvitation?.email)
        assertEquals(inviteToken, invitedByTokenPage.inviteToken)
        assertEquals(null, invitedByTokenPage.viewerRole)
    }

    @Test
    fun directoryFollowsInstancePublicAccess() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val anonymous = jsonClient()

        val organization = alice.newOrganization("acme", "Acme")
        alice.updateOrganizationVisibility(organization.id, "public")
        val orgCalendar = alice.newCalendar("Team", organizationId = organization.id)
        assertNotNull(alice.enablePublicLink(orgCalendar.id.value).publicLinkToken)
        val bobCalendar = bob.newCalendar("Bob Public")
        assertNotNull(bob.enablePublicLink(bobCalendar.id.value).publicLinkToken)

        val public = anonymous.get("/api/v1/public/directory")
        assertEquals(HttpStatusCode.OK, public.status, public.bodyAsText())
        val publicDirectory = public.body<PublicDirectoryPage>()
        assertEquals("public", publicDirectory.publicAccess)
        assertEquals(listOf("acme"), publicDirectory.orgs.map { it.slug })
        assertTrue(publicDirectory.users.any { it.username == "alice" })
        assertTrue(publicDirectory.users.any { it.username == "bob" })
        assertTrue(publicDirectory.calendars.any { it.id == orgCalendar.id.value })
        assertTrue(publicDirectory.calendars.any { it.id == bobCalendar.id.value })
        assertEquals(
            "Acme",
            publicDirectory.calendars.single { it.id == orgCalendar.id.value }.organizationName,
        )

        alice.setInstancePublicAccess("signed_in")
        assertEquals(HttpStatusCode.NotFound, anonymous.get("/api/v1/public/directory").status)

        val signedIn = bob.get("/api/v1/public/directory")
        assertEquals(HttpStatusCode.OK, signedIn.status, signedIn.bodyAsText())
        val signedInDirectory = signedIn.body<PublicDirectoryPage>()
        assertEquals("signed_in", signedInDirectory.publicAccess)
        assertEquals(listOf("acme"), signedInDirectory.orgs.map { it.slug })
        assertTrue(signedInDirectory.calendars.any { it.id == orgCalendar.id.value })
        assertTrue(signedInDirectory.calendars.any { it.id == bobCalendar.id.value })
    }

    @Test
    fun publicProfileRouteDoesNotShadowNeighborRoutes() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val anonymous = jsonClient()

        val calendar = alice.newCalendar("Neighbor")
        val token = assertNotNull(alice.enablePublicLink(calendar.id.value).publicLinkToken)

        val publicCalendar = anonymous.get("/api/v1/public/calendars/$token")
        assertEquals(HttpStatusCode.OK, publicCalendar.status, publicCalendar.bodyAsText())
        assertEquals(calendar.id.value, publicCalendar.body<PublicCalendarOut>().calendar.id.value)

        val rsvpLookup = anonymous.get("/api/v1/public/events/rsvp")
        assertEquals(HttpStatusCode.BadRequest, rsvpLookup.status)
        assertEquals("invalid", rsvpLookup.body<ErrorBody>().error)

        assertEquals(HttpStatusCode.NotFound, anonymous.get("/api/v1/public/users/$token").status)
    }

    private companion object {
        val OrgInviteTokenPattern = Regex("""inviteToken=([A-Za-z0-9_-]+)""")
    }
}

private suspend fun HttpClient.newOrganization(slug: String, displayName: String): OrganizationSummaryOut {
    val response = post("/api/v1/organizations") {
        contentType(ContentType.Application.Json)
        setBody("""{"slug":"$slug","displayName":"$displayName"}""")
    }
    check(response.status == HttpStatusCode.Created) {
        "create organization failed: ${response.status} ${response.bodyAsText()}"
    }
    return response.body()
}

private suspend fun HttpClient.updateOrganizationVisibility(
    organizationId: String,
    visibility: String,
): OrganizationSummaryOut {
    val response = patch("/api/v1/organizations/$organizationId") {
        contentType(ContentType.Application.Json)
        setBody("""{"visibility":"$visibility"}""")
    }
    check(response.status == HttpStatusCode.OK) {
        "update organization failed: ${response.status} ${response.bodyAsText()}"
    }
    return response.body()
}

private suspend fun HttpClient.inviteToOrganization(
    organizationId: String,
    identifier: String,
): OrganizationInvitationResultOut {
    val response = post("/api/v1/organizations/$organizationId/invitations") {
        contentType(ContentType.Application.Json)
        setBody("""{"identifier":"$identifier"}""")
    }
    check(response.status == HttpStatusCode.Created) {
        "invite failed: ${response.status} ${response.bodyAsText()}"
    }
    return response.body()
}

private suspend fun HttpClient.acceptInvitation(invitationId: String) {
    val response = post("/api/v1/organizations/invitations/accept") {
        contentType(ContentType.Application.Json)
        setBody("""{"invitationId":"$invitationId"}""")
    }
    check(response.status == HttpStatusCode.OK) {
        "accept invitation failed: ${response.status} ${response.bodyAsText()}"
    }
}

private suspend fun HttpClient.newCalendar(
    displayName: String,
    organizationId: String? = null,
): Calendar {
    val organization = organizationId?.let { ""","organizationId":"$it"""" }.orEmpty()
    val response = post("/api/v1/calendars") {
        contentType(ContentType.Application.Json)
        setBody("""{"displayName":"$displayName"$organization}""")
    }
    check(response.status == HttpStatusCode.Created) {
        "create calendar failed: ${response.status} ${response.bodyAsText()}"
    }
    return response.body()
}

private suspend fun HttpClient.enablePublicLink(calendarId: String): CalendarSharingOut {
    val response = put("/api/v1/calendars/$calendarId/public") {
        contentType(ContentType.Application.Json)
        setBody("""{"enabled":true}""")
    }
    check(response.status == HttpStatusCode.OK) {
        "enable public link failed: ${response.status} ${response.bodyAsText()}"
    }
    return response.body()
}

private suspend fun HttpClient.setInstancePublicAccess(mode: String) {
    val response = patch("/api/v1/admin/settings") {
        contentType(ContentType.Application.Json)
        setBody("""{"publicAccess":"$mode"}""")
    }
    check(response.status == HttpStatusCode.OK) {
        "set public access failed: ${response.status} ${response.bodyAsText()}"
    }
}

private suspend fun HttpClient.registerWithEmail(username: String, email: String): User {
    val response = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterUser(username = username, password = "password12", email = email))
    }
    check(response.status == HttpStatusCode.Created) {
        "register failed: ${response.status} ${response.bodyAsText()}"
    }
    return response.body<AuthResult>().user ?: error("register did not create a session")
}
