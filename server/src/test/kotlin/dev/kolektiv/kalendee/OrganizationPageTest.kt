package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AuthResult
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.web.CalendarSharingOut
import dev.kolektiv.kalendee.web.CalendarSummary
import dev.kolektiv.kalendee.web.CreateCalendarIn
import dev.kolektiv.kalendee.web.CreateOrganizationIn
import dev.kolektiv.kalendee.web.DeletedOut
import dev.kolektiv.kalendee.web.HomePage
import dev.kolektiv.kalendee.web.InviteToOrganizationIn
import dev.kolektiv.kalendee.web.OrganizationInvitationOut
import dev.kolektiv.kalendee.web.OrganizationInvitationsIn
import dev.kolektiv.kalendee.web.OrganizationInvitationsOut
import dev.kolektiv.kalendee.web.OrganizationMembershipOut
import dev.kolektiv.kalendee.web.OrganizationProfilePage
import dev.kolektiv.kalendee.web.OrganizationSettingsPage
import dev.kolektiv.kalendee.web.OrganizationSummary
import dev.kolektiv.kalendee.web.PublicAccessOut
import dev.kolektiv.kalendee.web.PublicDirectoryPage
import dev.kolektiv.kalendee.web.PublicProfilePage
import dev.kolektiv.kalendee.web.RespondOrganizationInvitationIn
import dev.kolektiv.kalendee.web.SetCalendarPublicIn
import dev.kolektiv.kalendee.web.SetPublicAccessIn
import dev.kolektiv.kalendee.web.SetUserPublicAccessIn
import dev.kolektiv.kalendee.web.UpdateOrganizationIn
import dev.kolektiv.kalendee.web.Viewer
import dev.kolektiv.keel.Keel
import dev.kolektiv.keel.KeelJson
import dev.kolektiv.keel.seed.KeelSeed
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

class OrganizationPageTest {
    @Test
    fun organizationLifecycleExposesPageData() = testApplication {
        installApi()
        startApplication()
        val anon = jsonClient()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val carol = jsonClient()
        carol.registerAndLogin("carol")

        val org = alice.createOrganization("acme", "Acme Inc", "A cooperative")
        assertEquals("acme", org.slug)
        assertEquals("private", org.visibility)
        assertEquals("owner", org.role)
        assertEquals(1, org.memberCount)

        val calendar = alice.createCalendar("Team", organizationId = org.id)
        assertEquals(org.id, calendar.organizationId)

        val invitation = alice.invite(org.id, "bob")
        assertEquals("pending", invitation.status)
        assertEquals(invitation.id, alice.invitations(org.id).invitations.single().id)
        val joined = bob.acceptInvitation(invitation.id)
        assertEquals("member", joined.role)
        assertEquals("acme", joined.organization.slug)
        assertEquals(2, joined.organization.memberCount)

        assertEquals(HttpStatusCode.NotFound, anon.visitStatus("/o/acme"))
        assertEquals(HttpStatusCode.NotFound, carol.visitStatus("/o/acme"))

        val alicePage = alice.visit("/o/acme").decodePage(OrganizationProfilePage.serializer())
        assertEquals("owner", alicePage.viewerRole)
        assertEquals(2, alicePage.org.memberCount)
        assertEquals(setOf("alice", "bob"), alicePage.members.map { it.username }.toSet())
        assertTrue(alicePage.members.single { it.username == "alice" }.isSelf)
        assertTrue(alicePage.canManageSettings)

        val bobPage = bob.visit("/o/acme").decodePage(OrganizationProfilePage.serializer())
        assertEquals("member", bobPage.viewerRole)
        assertFalse(bobPage.canManageSettings)

        assertEquals(HttpStatusCode.NotFound, carol.visitStatus("/o/acme/settings"))
        val anonSettings = anon.visit("/o/acme/settings")
        assertEquals("/login", anonSettings.redirect)
        val bobSettings = bob.visit("/o/acme/settings").decodePage(OrganizationSettingsPage.serializer())
        assertEquals("member", bobSettings.viewerRole)
        assertFalse(bobSettings.canManageMembers)
        assertFalse(bobSettings.canManageOwners)
        assertTrue(bobSettings.invitations.isEmpty())
        val aliceSettings = alice.visit("/o/acme/settings").decodePage(OrganizationSettingsPage.serializer())
        assertTrue(aliceSettings.canManageMembers)
        assertTrue(aliceSettings.canManageOwners)
        assertEquals(2, aliceSettings.members.size)

        val homeSeed = alice.visit("/")
        val home = homeSeed.decodePage(HomePage.serializer())
        val homeCalendar = home.calendars.single { it.id == calendar.id }
        assertEquals("Acme Inc", homeCalendar.organizationName)
        assertEquals("acme", homeCalendar.organizationSlug)
        assertEquals("Acme Inc", homeCalendar.ownerName)
        val sharedOrgs = homeSeed.shared?.get("organizations")?.jsonArray.orEmpty()
        assertTrue(
            sharedOrgs.any { it.jsonObject["slug"]?.jsonPrimitive?.content == "acme" },
            "shared organizations should include acme",
        )

        val profile = anon.visit("/u/alice").decodePage(PublicProfilePage.serializer())
        assertEquals("alice", profile.username)
        assertFalse(profile.isSelf)
        assertTrue(profile.organizations.isEmpty(), "private organizations stay off the public profile")
    }

    @Test
    fun publicOrganizationDirectoryAndInstanceAccess() = testApplication {
        installApi()
        startApplication()
        val anon = jsonClient()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val carol = jsonClient()
        carol.registerAndLogin("carol")

        val org = alice.createOrganization("acme", "Acme")
        val calendar = alice.createCalendar("Team", organizationId = org.id)
        val token = assertNotNull(alice.enablePublicLink(calendar.id).publicLinkToken)

        val privateDirectory = anon.visit("/directory").decodePage(PublicDirectoryPage.serializer())
        assertTrue(privateDirectory.orgs.isEmpty(), "private organizations are not listed")
        assertTrue(privateDirectory.calendars.isEmpty(), "private-org calendars are never listed")
        assertTrue(privateDirectory.users.any { it.username == "alice" })
        assertTrue(privateDirectory.users.any { it.username == "bob" })

        val publicOrg = alice.updateOrganizationVisibility(org.id, "public")
        assertEquals("public", publicOrg.visibility)

        val anonOrgPage = anon.visit("/o/acme").decodePage(OrganizationProfilePage.serializer())
        assertEquals("public", anonOrgPage.org.visibility)
        assertEquals(1, anonOrgPage.org.memberCount)
        assertEquals(listOf("alice"), anonOrgPage.members.map { it.username })
        assertEquals(1, anonOrgPage.calendars.size)
        assertEquals("Acme", anonOrgPage.calendars.single().organizationName)
        assertEquals(token, anonOrgPage.calendars.single().publicLinkToken)

        val carolPage = carol.visit("/o/acme").decodePage(OrganizationProfilePage.serializer())
        assertEquals(null, carolPage.viewerRole)
        assertFalse(carolPage.canManageSettings)
        assertEquals(1, carolPage.members.size)

        val publicDirectory = anon.visit("/directory").decodePage(PublicDirectoryPage.serializer())
        assertEquals(listOf("acme"), publicDirectory.orgs.map { it.slug })
        assertEquals(1, publicDirectory.orgs.single().memberCount)
        assertEquals(listOf(calendar.id), publicDirectory.calendars.map { it.id })
        assertEquals("Acme", publicDirectory.calendars.single().organizationName)

        val invited = alice.invite(org.id, "carol")
        val carolInvited = carol.visit("/o/acme").decodePage(OrganizationProfilePage.serializer())
        assertEquals(invited.id, carolInvited.pendingInvitation?.id)
        assertEquals("carol", carolInvited.pendingInvitation?.username)
        carol.declineInvitation(invited.id)
        assertEquals(null, carol.visit("/o/acme").decodePage(OrganizationProfilePage.serializer()).pendingInvitation)

        alice.setInstancePublicAccess("signed_in")
        assertEquals(HttpStatusCode.NotFound, anon.visitStatus("/directory"))
        assertEquals(HttpStatusCode.NotFound, anon.visitStatus("/o/acme"))
        assertEquals(HttpStatusCode.NotFound, anon.visitStatus("/u/alice"))
        val signedInDirectory = carol.visit("/directory").decodePage(PublicDirectoryPage.serializer())
        assertEquals("signed_in", signedInDirectory.publicAccess)
        assertTrue(signedInDirectory.orgs.any { it.slug == "acme" })
        assertTrue(signedInDirectory.calendars.any { it.id == calendar.id })
        assertTrue(signedInDirectory.users.any { it.username == "alice" })
        assertEquals(HttpStatusCode.OK, carol.visitStatus("/o/acme"))
        assertEquals(HttpStatusCode.OK, carol.visitStatus("/u/alice"))
        assertEquals("bob", bob.visit("/u/bob").decodePage(PublicProfilePage.serializer()).username)
    }

    @Test
    fun privateOrgCalendarPublicEntryPoints() = testApplication {
        installApi()
        startApplication()
        val anon = jsonClient()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")

        val org = alice.createOrganization("secret", "Secret")
        val calendar = alice.createCalendar("Hidden", organizationId = org.id)
        val token = assertNotNull(alice.enablePublicLink(calendar.id).publicLinkToken)

        assertEquals(HttpStatusCode.NotFound, anon.visitStatus("/c/$token"))
        assertEquals(HttpStatusCode.NotFound, anon.get("/api/v1/public/calendars/$token").status)
        assertEquals(HttpStatusCode.NotFound, anon.get("/rss/$token.xml").status)
        assertEquals(HttpStatusCode.NotFound, bob.visitStatus("/c/$token"))
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/v1/public/calendars/$token").status)
        assertEquals(HttpStatusCode.NotFound, bob.get("/rss/$token.xml").status)

        assertEquals(HttpStatusCode.OK, alice.visitStatus("/c/$token"))
        assertEquals(HttpStatusCode.OK, alice.get("/api/v1/public/calendars/$token").status)
        assertEquals(HttpStatusCode.OK, alice.get("/rss/$token.xml").status)

        alice.updateOrganizationVisibility(org.id, "public")
        assertEquals(HttpStatusCode.OK, bob.visitStatus("/c/$token"))
        assertEquals(HttpStatusCode.OK, bob.get("/api/v1/public/calendars/$token").status)
        assertEquals(HttpStatusCode.OK, bob.get("/rss/$token.xml").status)
    }

    @Test
    fun invitationTokenGrantsPrivateOrgPageAccess() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        startApplication()
        val anon = jsonClient()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val org = alice.createOrganization("acme", "Acme")
        val calendar = alice.createCalendar("Team", organizationId = org.id)
        assertNotNull(calendar.organizationId)

        assertEquals("pending", alice.invite(org.id, "guest@example.com").status)
        val sent = mail.sent.last { "inviteToken=" in it.text }
        val token = InviteTokenPattern.find(sent.text)?.groupValues?.get(1)
            ?: error("no invite token in mail")

        assertEquals(HttpStatusCode.NotFound, anon.visitStatus("/o/acme?inviteToken=$token"))
        val guest = jsonClient()
        guest.registerWithEmail("guest", "guest@example.com")
        assertEquals(HttpStatusCode.NotFound, guest.visitStatus("/o/acme?inviteToken=bogus"))

        val invitedPage = guest.visit("/o/acme?inviteToken=$token")
            .decodePage(OrganizationProfilePage.serializer())
        assertEquals("guest@example.com", invitedPage.pendingInvitation?.email)
        assertEquals("guest", invitedPage.pendingInvitation?.username)
        assertEquals(token, invitedPage.inviteToken)
        assertTrue(invitedPage.members.isEmpty(), "non-members of private orgs do not see members")
        assertEquals(null, invitedPage.viewerRole)

        val joined = guest.acceptInvitationToken(token)
        assertEquals("member", joined.role)
        val memberPage = guest.visit("/o/acme").decodePage(OrganizationProfilePage.serializer())
        assertEquals("member", memberPage.viewerRole)
        assertEquals(setOf("alice", "guest"), memberPage.members.map { it.username }.toSet())
        assertEquals(2, memberPage.org.memberCount)
    }

    @Test
    fun directoryFiltersUsersAndCalendars() = testApplication {
        installApi()
        startApplication()
        val anon = jsonClient()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val carol = jsonClient()
        carol.registerAndLogin("carol")

        val aliceCalendar = alice.createCalendar("Alice Public")
        assertNotNull(alice.enablePublicLink(aliceCalendar.id).publicLinkToken)
        val bobCalendar = bob.createCalendar("Bob Public")
        assertNotNull(bob.enablePublicLink(bobCalendar.id).publicLinkToken)
        bob.setUserPublicAccess("signed_in")

        val anonDirectory = anon.visit("/directory").decodePage(PublicDirectoryPage.serializer())
        assertTrue(anonDirectory.users.any { it.username == "alice" })
        assertFalse(anonDirectory.users.any { it.username == "bob" })
        assertTrue(anonDirectory.calendars.any { it.id == aliceCalendar.id })
        assertFalse(anonDirectory.calendars.any { it.id == bobCalendar.id })

        assertEquals(HttpStatusCode.NotFound, anon.visitStatus("/u/bob"))
        val signedInDirectory = carol.visit("/directory").decodePage(PublicDirectoryPage.serializer())
        assertTrue(signedInDirectory.users.any { it.username == "bob" })
        assertTrue(signedInDirectory.calendars.any { it.id == bobCalendar.id })
        assertEquals("bob", carol.visit("/u/bob").decodePage(PublicProfilePage.serializer()).username)

        val aliceProfile = anon.visit("/u/alice").decodePage(PublicProfilePage.serializer())
        assertEquals(listOf(aliceCalendar.id), aliceProfile.calendars.map { it.id })
    }

    private suspend fun HttpClient.visit(path: String): KeelSeed {
        val response = get(path) { header(KeelHeaders.VISIT, "true") }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return KeelJson.codec.decodeFromString(KeelSeed.serializer(), response.bodyAsText())
    }

    private suspend fun HttpClient.visitStatus(path: String): HttpStatusCode =
        get(path) { header(KeelHeaders.VISIT, "true") }.status

    private suspend fun HttpClient.createOrganization(
        slug: String,
        displayName: String,
        description: String? = null,
    ): OrganizationSummary = action(
        "kalendee.createOrganization",
        CreateOrganizationIn(slug = slug, displayName = displayName, description = description),
    )

    private suspend fun HttpClient.updateOrganizationVisibility(
        organizationId: String,
        visibility: String,
    ): OrganizationSummary = action(
        "kalendee.updateOrganization",
        UpdateOrganizationIn(organizationId = organizationId, visibility = visibility),
    )

    private suspend fun HttpClient.createCalendar(
        displayName: String,
        organizationId: String? = null,
    ): CalendarSummary = action(
        "kalendee.createCalendar",
        CreateCalendarIn(displayName = displayName, organizationId = organizationId),
    )

    private suspend fun HttpClient.enablePublicLink(calendarId: String): CalendarSharingOut = action(
        "kalendee.setCalendarPublic",
        SetCalendarPublicIn(calendarId = calendarId, enabled = true),
    )

    private suspend fun HttpClient.invite(
        organizationId: String,
        identifier: String,
        role: String = "member",
    ): OrganizationInvitationOut = action(
        "kalendee.inviteToOrganization",
        InviteToOrganizationIn(organizationId = organizationId, identifier = identifier, role = role),
    )

    private suspend fun HttpClient.invitations(organizationId: String): OrganizationInvitationsOut = action(
        "kalendee.organizationInvitations",
        OrganizationInvitationsIn(organizationId = organizationId),
    )

    private suspend fun HttpClient.acceptInvitation(invitationId: String): OrganizationMembershipOut = action(
        "kalendee.acceptOrganizationInvitation",
        RespondOrganizationInvitationIn(invitationId = invitationId),
    )

    private suspend fun HttpClient.acceptInvitationToken(token: String): OrganizationMembershipOut = action(
        "kalendee.acceptOrganizationInvitation",
        RespondOrganizationInvitationIn(token = token),
    )

    private suspend fun HttpClient.declineInvitation(invitationId: String): DeletedOut = action(
        "kalendee.declineOrganizationInvitation",
        RespondOrganizationInvitationIn(invitationId = invitationId),
    )

    private suspend fun HttpClient.setUserPublicAccess(mode: String): Viewer = action(
        "kalendee.setUserPublicAccess",
        SetUserPublicAccessIn(mode = mode),
    )

    private suspend fun HttpClient.setInstancePublicAccess(mode: String): PublicAccessOut = action(
        "kalendee.setPublicAccess",
        SetPublicAccessIn(mode = mode),
    )

    private suspend inline fun <reified In : Any, reified Out : Any> HttpClient.action(id: String, input: In): Out {
        val response = post("${Keel.ACTION_PATH}/$id") {
            contentType(ContentType.Application.Json)
            setBody(input)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val root = KeelJson.codec.parseToJsonElement(response.bodyAsText()).jsonObject
        return KeelJson.codec.decodeFromJsonElement(serializer<Out>(), root.getValue("data"))
    }

    private fun <T> KeelSeed.decodePage(serializer: KSerializer<T>): T =
        KeelJson.codec.decodeFromJsonElement(serializer, data)

    private suspend fun HttpClient.registerWithEmail(username: String, email: String): User {
        val response = post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = username, password = "password12", email = email))
        }
        check(response.status == HttpStatusCode.Created) { "register failed: ${response.status}" }
        return response.body<AuthResult>().user ?: error("register did not create a session")
    }

    private companion object {
        val InviteTokenPattern = Regex("""inviteToken=([A-Za-z0-9_-]+)""")
    }
}
