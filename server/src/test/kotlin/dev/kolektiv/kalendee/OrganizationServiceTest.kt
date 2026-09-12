package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AuthResult
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.calendar.OrganizationVisibility
import dev.kolektiv.kalendee.calendar.UpdateCalendar
import dev.kolektiv.kalendee.db.OrganizationInvitationsTable
import dev.kolektiv.kalendee.notifications.NotificationService
import dev.kolektiv.kalendee.organizations.OrganizationInvitation
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.ktor.ext.get

class OrganizationServiceTest {
    @Test
    fun createValidatesSlugAndCreatorIsOwner() = testApplication {
        lateinit var orgs: OrganizationService
        installApi(configure = { orgs = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")

        val org = orgs.create(alice.id, "Acme", "Acme Inc", "A cooperative")
        assertEquals("acme", org.slug)
        assertEquals("Acme Inc", org.displayName)
        assertEquals("A cooperative", org.description)
        assertEquals(OrganizationVisibility.PRIVATE, org.visibility)
        assertEquals(OrganizationRole.OWNER, orgs.role(org.id, alice.id))
        assertTrue(orgs.isMember(org.id, alice.id))
        val membership = orgs.listFor(alice.id).single()
        assertEquals(org.id, membership.organization.id)
        assertEquals(OrganizationRole.OWNER, membership.role)
        assertEquals(org.id, orgs.byId(org.id)?.id)
        assertEquals(org.id, orgs.bySlug("ACME")?.id)

        assertFailsWith<CalendarException.Conflict> { orgs.create(alice.id, "acme", "Other") }
        assertFailsWith<CalendarException.Invalid> { orgs.create(alice.id, "ab", "Too short") }
        assertFailsWith<CalendarException.Invalid> { orgs.create(alice.id, "Bad Slug", "Bad") }
        assertFailsWith<CalendarException.Invalid> { orgs.create(alice.id, "x".repeat(33), "Long") }
        assertFailsWith<CalendarException.Conflict> { orgs.create(alice.id, "admin", "Reserved") }
    }

    @Test
    fun memberRolesAndOwnerInvariants() = testApplication {
        lateinit var orgs: OrganizationService
        installApi(configure = { orgs = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val carol = jsonClient().registerAndLogin("carol")
        val dave = jsonClient().registerAndLogin("dave")
        val org = orgs.create(alice.id, "acme", "Acme")

        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.ADMIN)
        orgs.addMember(alice.id, org.id, carol.id, OrganizationRole.MEMBER)
        assertEquals(OrganizationRole.ADMIN, orgs.role(org.id, bob.id))
        assertEquals(3, orgs.members(alice.id, org.id).size)

        orgs.addMember(bob.id, org.id, dave.id, OrganizationRole.MEMBER)
        assertTrue(orgs.removeMember(bob.id, org.id, dave.id))
        assertFailsWith<CalendarException.Forbidden> {
            orgs.addMember(bob.id, org.id, dave.id, OrganizationRole.ADMIN)
        }
        assertFailsWith<CalendarException.Forbidden> {
            orgs.updateMemberRole(bob.id, org.id, alice.id, OrganizationRole.MEMBER)
        }
        assertFailsWith<CalendarException.Forbidden> {
            orgs.updateMemberRole(bob.id, org.id, bob.id, OrganizationRole.MEMBER)
        }
        assertFailsWith<CalendarException.Forbidden> {
            orgs.removeMember(bob.id, org.id, alice.id)
        }
        assertFailsWith<CalendarException.Forbidden> { orgs.members(dave.id, org.id) }
        assertFailsWith<CalendarException.Forbidden> { orgs.invitations(carol.id, org.id) }

        val promoted = orgs.updateMemberRole(alice.id, org.id, bob.id, OrganizationRole.OWNER)
        assertEquals(OrganizationRole.OWNER, promoted.role)
        orgs.updateMemberRole(alice.id, org.id, alice.id, OrganizationRole.MEMBER)
        assertFailsWith<CalendarException.Forbidden> {
            orgs.updateMemberRole(bob.id, org.id, bob.id, OrganizationRole.MEMBER)
        }
        assertFailsWith<CalendarException.Forbidden> { orgs.removeMember(bob.id, org.id, bob.id) }

        orgs.updateMemberRole(bob.id, org.id, alice.id, OrganizationRole.OWNER)
        assertTrue(orgs.removeMember(alice.id, org.id, alice.id))
        assertEquals(OrganizationRole.OWNER, orgs.role(org.id, bob.id))
    }

    @Test
    fun inviteByUsernameCreatesNotificationAndAcceptAddsMembership() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var notifications: NotificationService
        installApi(configure = { orgs = get(); notifications = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val org = orgs.create(alice.id, "acme", "Acme")

        val result = orgs.invite(alice.id, org.id, "bob", OrganizationRole.MEMBER)
        assertEquals(OrganizationInvitation.PENDING, result.status)
        val invitation = orgs.invitations(alice.id, org.id).single()
        assertEquals(bob.id, invitation.inviteeUserId)
        assertEquals(OrganizationInvitation.PENDING, invitation.status)
        assertTrue(notifications.list(bob.id).any { it.kind == "org.invite" })
        assertFalse(orgs.isMember(org.id, bob.id))

        val accepted = orgs.acceptInvitation(bob.id, invitation.id.toString())
        assertEquals(OrganizationRole.MEMBER, accepted.role)
        assertTrue(orgs.isMember(org.id, bob.id))

        val carol = jsonClient().registerAndLogin("carol")
        val first = orgs.invite(alice.id, org.id, "carol", OrganizationRole.MEMBER)
        val second = orgs.invite(alice.id, org.id, "carol", OrganizationRole.ADMIN)
        assertEquals(first.invitationId, second.invitationId)
        assertEquals(
            1,
            orgs.invitations(alice.id, org.id)
                .count { it.inviteeUserId == carol.id && it.status == OrganizationInvitation.PENDING },
        )
        assertEquals(
            OrganizationRole.ADMIN,
            orgs.invitations(alice.id, org.id).single { it.inviteeUserId == carol.id }.role,
        )

        val dave = jsonClient().registerAndLogin("dave")
        val daveInvite = orgs.invite(alice.id, org.id, "dave", OrganizationRole.MEMBER)
        val declined = orgs.declineInvitation(dave.id, daveInvite.invitationId.toString())
        assertEquals(OrganizationInvitation.DECLINED, declined.status)
        assertFalse(orgs.isMember(org.id, dave.id))

        orgs.updateMemberRole(alice.id, org.id, bob.id, OrganizationRole.ADMIN)
        assertFailsWith<CalendarException.Forbidden> {
            orgs.revokeInvitation(carol.id, second.invitationId.toString())
        }
        val revoked = orgs.revokeInvitation(bob.id, second.invitationId.toString())
        assertEquals(OrganizationInvitation.REVOKED, revoked.status)
    }

    @Test
    fun inviteByUnknownEmailSendsTokenMailAndTokenAcceptAddsMembership() = testApplication {
        lateinit var orgs: OrganizationService
        val mail = RecordingMailer()
        installApi(mailer = mail, configure = { orgs = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val org = orgs.create(alice.id, "acme", "Acme")

        val result = orgs.invite(alice.id, org.id, "newbie@example.com", OrganizationRole.MEMBER)
        assertEquals(OrganizationInvitation.PENDING, result.status)
        val invitation = orgs.invitations(alice.id, org.id).single()
        assertNull(invitation.inviteeUserId)
        assertEquals("newbie@example.com", invitation.email)

        val sent = mail.sent.last { "inviteToken=" in it.text }
        assertTrue(sent.text.contains("https://kalendee.test/o/acme?inviteToken="))
        val token = InviteTokenPattern.find(sent.text)?.groupValues?.get(1)
            ?: error("no invite token in mail")

        val mallory = jsonClient().registerWithEmail("mallory", "mallory@example.com")
        assertFailsWith<CalendarException.Forbidden> {
            orgs.acceptInvitationByToken(mallory.id, token)
        }

        val newbie = jsonClient().registerWithEmail("newbie", "newbie@example.com")
        val accepted = orgs.acceptInvitationByToken(newbie.id, token)
        assertEquals(OrganizationRole.MEMBER, accepted.role)
        assertTrue(orgs.isMember(org.id, newbie.id))
        assertFailsWith<CalendarException.NotFound> {
            orgs.invite(alice.id, org.id, "nobody", OrganizationRole.MEMBER)
        }
    }

    @Test
    fun expiredInvitationIsRejected() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var database: Database
        val mail = RecordingMailer()
        installApi(mailer = mail, configure = { orgs = get(); database = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val org = orgs.create(alice.id, "acme", "Acme")

        val result = orgs.invite(alice.id, org.id, "slow@example.com", OrganizationRole.MEMBER)
        val sent = mail.sent.last { "inviteToken=" in it.text }
        val token = InviteTokenPattern.find(sent.text)?.groupValues?.get(1)
            ?: error("no invite token in mail")

        suspendTransaction(database) {
            OrganizationInvitationsTable.update({ OrganizationInvitationsTable.id eq result.invitationId }) {
                it[OrganizationInvitationsTable.expiresAt] = Clock.System.now() - 1.hours
            }
        }

        val slow = jsonClient().registerWithEmail("slow", "slow@example.com")
        assertFailsWith<CalendarException.Forbidden> {
            orgs.acceptInvitationByToken(slow.id, token)
        }
        assertFalse(orgs.isMember(org.id, slow.id))
    }

    @Test
    fun organizationCalendarPermissions() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var store: CalendarStore
        installApi(configure = { orgs = get(); store = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val carol = jsonClient().registerAndLogin("carol")
        val dave = jsonClient().registerAndLogin("dave")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        orgs.addMember(alice.id, org.id, dave.id, OrganizationRole.ADMIN)

        val calendar = store.createCalendar(
            alice.id,
            CreateCalendar(displayName = "Team", organizationId = org.id),
        )
        assertEquals(org.id, calendar.organizationId)
        assertEquals(CalendarPermission.WRITE, store.getCalendar(calendar.id, bob.id)?.permission)
        assertEquals(CalendarPermission.OWNER, store.getCalendar(calendar.id, alice.id)?.permission)
        assertEquals(CalendarPermission.OWNER, store.getCalendar(calendar.id, dave.id)?.permission)
        assertNull(store.getCalendar(calendar.id, carol.id))
        assertEquals(
            CalendarPermission.WRITE,
            store.listCalendars(bob.id).single { it.id == calendar.id }.permission,
        )
        assertTrue(store.listCalendars(alice.id).any { it.id == calendar.id })
        assertTrue(store.listCalendars(carol.id).none { it.id == calendar.id })

        val bobCalendar = store.createCalendar(
            bob.id,
            CreateCalendar(displayName = "Bob's Team", organizationId = org.id),
        )
        assertEquals(CalendarPermission.OWNER, store.getCalendar(bobCalendar.id, bob.id)?.permission)
        assertEquals(CalendarPermission.OWNER, store.getCalendar(bobCalendar.id, alice.id)?.permission)

        val event = store.createEvent(
            calendar.id,
            bob.id,
            CreateEvent(
                title = "Standup",
                start = Instant.parse("2026-03-01T10:00:00Z"),
                end = Instant.parse("2026-03-01T10:30:00Z"),
            ),
        )
        assertEquals(calendar.id, event.calendarId)

        assertNull(store.updateCalendar(calendar.id, bob.id, UpdateCalendar(displayName = "Renamed")))
        assertFalse(store.deleteCalendar(calendar.id, bob.id))
        assertTrue(store.listShares(calendar.id, bob.id).isEmpty())
        assertFailsWith<CalendarException.NotFound> {
            store.addShare(calendar.id, bob.id, carol.id, CalendarPermission.READ)
        }
        assertNull(store.setPublicLink(calendar.id, bob.id, true))

        val renamed = store.updateCalendar(calendar.id, dave.id, UpdateCalendar(displayName = "Team Calendar"))
        assertEquals("Team Calendar", renamed?.displayName)
        assertNotNull(store.setPublicLink(calendar.id, alice.id, true))

        assertFailsWith<CalendarException.Forbidden> {
            store.createCalendar(carol.id, CreateCalendar(displayName = "Sneaky", organizationId = org.id))
        }
        assertFailsWith<CalendarException.NotFound> {
            store.createCalendar(alice.id, CreateCalendar(displayName = "Missing", organizationId = MissingOrganization))
        }
    }

    @Test
    fun privateOrganizationVisibilityGatesPublicViews() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        installApi(configure = { orgs = get(); store = get(); auth = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val dave = jsonClient().registerAndLogin("dave")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, dave.id, OrganizationRole.ADMIN)

        val calendar = store.createCalendar(
            alice.id,
            CreateCalendar(displayName = "Team", organizationId = org.id),
        )
        assertNotNull(store.setPublicLink(calendar.id, alice.id, true))
        val privateCalendar = store.getCalendar(calendar.id, alice.id) ?: error("calendar missing")

        assertFalse(auth.canViewPublic(privateCalendar, null))
        assertFalse(auth.canViewPublic(privateCalendar, bob.id))
        assertTrue(auth.canViewPublic(privateCalendar, alice.id))
        assertTrue(auth.canViewPublic(privateCalendar, dave.id))
        assertEquals(PublicAccessMode.SIGNED_IN, auth.effectivePublicAccess(privateCalendar))

        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        assertTrue(auth.canViewPublic(privateCalendar, bob.id))

        orgs.update(dave.id, org.id, displayName = "Acme Team")
        assertFailsWith<CalendarException.Forbidden> {
            orgs.update(dave.id, org.id, visibility = OrganizationVisibility.PUBLIC)
        }
        orgs.update(alice.id, org.id, visibility = OrganizationVisibility.PUBLIC)
        val publicCalendar = store.getCalendar(calendar.id, alice.id) ?: error("calendar missing")
        assertTrue(auth.canViewPublic(publicCalendar, null))
        assertEquals(PublicAccessMode.PUBLIC, auth.effectivePublicAccess(publicCalendar))
    }

    @Test
    fun deleteOrganizationCascadesMembersAndCalendars() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var store: CalendarStore
        installApi(configure = { orgs = get(); store = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.ADMIN)
        val calendar = store.createCalendar(
            bob.id,
            CreateCalendar(displayName = "Team", organizationId = org.id),
        )

        assertFailsWith<CalendarException.Forbidden> { orgs.delete(bob.id, org.id) }
        assertTrue(orgs.delete(alice.id, org.id))
        assertNull(orgs.byId(org.id))
        assertNull(orgs.role(org.id, alice.id))
        assertNull(store.getCalendar(calendar.id, bob.id))
    }

    private suspend fun HttpClient.registerWithEmail(username: String, email: String): User {
        val response = post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = username, password = "password12", email = email))
        }
        check(response.status == HttpStatusCode.Created) { "register failed: ${response.status}" }
        return response.body<AuthResult>().user ?: error("register did not create a session")
    }

    private companion object {
        val MissingOrganization = OrganizationId("00000000-0000-0000-0000-000000000001")
        val InviteTokenPattern = Regex("""inviteToken=([A-Za-z0-9_-]+)""")
    }
}
