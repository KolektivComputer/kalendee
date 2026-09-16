package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.db.CalendarConnectionsTable
import dev.kolektiv.kalendee.db.CalendarsTable
import dev.kolektiv.kalendee.db.ExternalCalendarsTable
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import dev.kolektiv.kalendee.organizations.OrganizationTeamRole
import dev.kolektiv.kalendee.organizations.OrganizationTeamService
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.ktor.ext.get

class TransferCalendarStoreTest {
    @Test
    fun personalCalendarMovesIntoOrganization() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        lateinit var orgs: OrganizationService
        installApi(configure = { store = get(); auth = get(); orgs = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)

        val aliceCalendar = store.createCalendar(alice.id, CreateCalendar(displayName = "Alice Personal"))
        val movedByOwner = requireNotNull(store.transferCalendar(aliceCalendar.id, alice.id, org.id))
        assertEquals(org.id, movedByOwner.organizationId)
        assertEquals(alice.id, movedByOwner.ownerId)
        assertEquals(CalendarPermission.OWNER, movedByOwner.permission)
        assertEquals(org.id, store.getCalendar(aliceCalendar.id, alice.id)?.organizationId)

        val bobCalendar = store.createCalendar(bob.id, CreateCalendar(displayName = "Bob Personal"))
        val movedByMember = requireNotNull(store.transferCalendar(bobCalendar.id, bob.id, org.id))
        assertEquals(org.id, movedByMember.organizationId)
        assertEquals(bob.id, movedByMember.ownerId)
        assertEquals(CalendarPermission.OWNER, movedByMember.permission)
    }

    @Test
    fun organizationManagersAndOwnersCanMoveToPersonal() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        lateinit var orgs: OrganizationService
        installApi(configure = { store = get(); auth = get(); orgs = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val dave = registerUser(auth, "dave")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        orgs.addMember(alice.id, org.id, dave.id, OrganizationRole.ADMIN)

        val bobCalendar = store.createCalendar(
            bob.id,
            CreateCalendar(displayName = "Bob Team", organizationId = org.id),
        )
        val movedByOwner = requireNotNull(store.transferCalendar(bobCalendar.id, alice.id, null))
        assertNull(movedByOwner.organizationId)
        assertEquals(alice.id, movedByOwner.ownerId)
        assertEquals(CalendarPermission.OWNER, movedByOwner.permission)

        val adminTarget = store.createCalendar(
            bob.id,
            CreateCalendar(displayName = "Admin Target", organizationId = org.id),
        )
        val movedByAdmin = requireNotNull(store.transferCalendar(adminTarget.id, dave.id, null))
        assertNull(movedByAdmin.organizationId)
        assertEquals(dave.id, movedByAdmin.ownerId)

        val ownCalendar = store.createCalendar(
            bob.id,
            CreateCalendar(displayName = "Bob Own", organizationId = org.id),
        )
        val movedByMember = requireNotNull(store.transferCalendar(ownCalendar.id, bob.id, null))
        assertNull(movedByMember.organizationId)
        assertEquals(bob.id, movedByMember.ownerId)

        val aliceCalendar = store.createCalendar(
            alice.id,
            CreateCalendar(displayName = "Alice Team", organizationId = org.id),
        )
        assertFailsWith<CalendarException.Forbidden> {
            store.transferCalendar(aliceCalendar.id, bob.id, null)
        }
        assertEquals(org.id, store.getCalendar(aliceCalendar.id, alice.id)?.organizationId)
    }

    @Test
    fun destinationOrganizationMustExistAndActorMustBeMember() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        lateinit var orgs: OrganizationService
        installApi(configure = { store = get(); auth = get(); orgs = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val carol = registerUser(auth, "carol")
        orgs.create(alice.id, "acme", "Acme")
        val carolOrg = orgs.create(carol.id, "other", "Other")
        val calendar = store.createCalendar(alice.id, CreateCalendar(displayName = "Personal"))

        assertFailsWith<CalendarException.NotFound> {
            store.transferCalendar(calendar.id, alice.id, MissingOrganization)
        }
        assertFailsWith<CalendarException.Forbidden> {
            store.transferCalendar(calendar.id, alice.id, carolOrg.id)
        }
        assertNull(store.transferCalendar(CalendarId.generate(), alice.id, carolOrg.id))
    }

    @Test
    fun teamWriteMemberCannotTransfer() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        installApi(configure = { store = get(); auth = get(); orgs = get(); teams = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        val calendar = store.createCalendar(
            alice.id,
            CreateCalendar(displayName = "Team", organizationId = org.id),
        )
        val design = teams.create(alice.id, org.id, "design", "Design")
        teams.addMember(alice.id, design.id, bob.id)
        teams.grant(alice.id, calendar.id, design.id, CalendarPermission.WRITE)

        assertEquals(CalendarPermission.WRITE, store.getCalendar(calendar.id, bob.id)?.permission)
        assertFailsWith<CalendarException.Forbidden> {
            store.transferCalendar(calendar.id, bob.id, null)
        }
    }

    @Test
    fun teamDestinationReplacesGrantsAndEnforcesManagers() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        installApi(configure = { store = get(); auth = get(); orgs = get(); teams = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        val design = teams.create(alice.id, org.id, "design", "Design")
        val product = teams.create(alice.id, org.id, "product", "Product")
        val calendar = store.createCalendar(
            alice.id,
            CreateCalendar(displayName = "Team", organizationId = org.id),
        )
        teams.grant(alice.id, calendar.id, design.id, CalendarPermission.WRITE)

        val moved = requireNotNull(store.transferCalendar(calendar.id, alice.id, org.id, product.id))
        assertEquals(org.id, moved.organizationId)
        assertEquals(alice.id, moved.ownerId)
        assertEquals(CalendarPermission.OWNER, moved.permission)
        assertTrue(teams.grants(alice.id, design.id).none { it.calendarId == calendar.id })
        val grant = teams.grants(alice.id, product.id).single { it.calendarId == calendar.id }
        assertEquals(CalendarPermission.WRITE, grant.permission)

        val bobCalendar = store.createCalendar(bob.id, CreateCalendar(displayName = "Bob Personal"))
        assertFailsWith<CalendarException.Forbidden> {
            store.transferCalendar(bobCalendar.id, bob.id, org.id, product.id)
        }

        teams.addMember(alice.id, product.id, bob.id)
        teams.setMemberRole(alice.id, product.id, bob.id, OrganizationTeamRole.MAINTAINER)
        val movedByMaintainer = requireNotNull(store.transferCalendar(bobCalendar.id, bob.id, org.id, product.id))
        assertEquals(org.id, movedByMaintainer.organizationId)

        val other = orgs.create(alice.id, "other", "Other")
        val otherTeam = teams.create(alice.id, other.id, "sales", "Sales")
        val second = store.createCalendar(alice.id, CreateCalendar(displayName = "Second"))
        assertFailsWith<CalendarException.Invalid> {
            store.transferCalendar(second.id, alice.id, org.id, otherTeam.id)
        }
    }

    @Test
    fun orgHeaderTransferGrantsDefaultTeamWrite() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        installApi(configure = { store = get(); auth = get(); orgs = get(); teams = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        val design = teams.create(alice.id, org.id, "design", "Design")
        val calendar = store.createCalendar(alice.id, CreateCalendar(displayName = "Personal"))

        val movedToTeam = requireNotNull(store.transferCalendar(calendar.id, alice.id, org.id, design.id))
        assertEquals(org.id, movedToTeam.organizationId)
        assertEquals(1, teams.grants(alice.id, design.id).count { it.calendarId == calendar.id })

        val movedToHeader = requireNotNull(store.transferCalendar(calendar.id, alice.id, org.id))
        assertEquals(org.id, movedToHeader.organizationId)
        assertEquals(alice.id, movedToHeader.ownerId)

        val defaultTeamId = requireNotNull(teams.defaultTeamId(org.id))
        val defaultGrant = teams.grants(alice.id, defaultTeamId).single { it.calendarId == calendar.id }
        assertEquals(CalendarPermission.WRITE, defaultGrant.permission)
        assertTrue(teams.grants(alice.id, design.id).none { it.calendarId == calendar.id })
        assertEquals(CalendarPermission.WRITE, store.getCalendar(calendar.id, bob.id)?.permission)
    }

    @Test
    fun orgToOrgHeaderTransferReplacesTeamGrants() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        installApi(configure = { store = get(); auth = get(); orgs = get(); teams = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val source = orgs.create(alice.id, "acme", "Acme")
        val destination = orgs.create(alice.id, "beta", "Beta")
        orgs.addMember(alice.id, destination.id, bob.id, OrganizationRole.MEMBER)
        val design = teams.create(alice.id, source.id, "design", "Design")
        val calendar = store.createCalendar(
            alice.id,
            CreateCalendar(displayName = "Team", organizationId = source.id),
        )
        teams.grant(alice.id, calendar.id, design.id, CalendarPermission.WRITE)

        val moved = requireNotNull(store.transferCalendar(calendar.id, alice.id, destination.id))
        assertEquals(destination.id, moved.organizationId)
        assertEquals(alice.id, moved.ownerId)

        assertTrue(teams.grants(alice.id, design.id).none { it.calendarId == calendar.id })
        val defaultTeamId = requireNotNull(teams.defaultTeamId(destination.id))
        val grant = teams.grants(alice.id, defaultTeamId).single { it.calendarId == calendar.id }
        assertEquals(CalendarPermission.WRITE, grant.permission)
        assertEquals(CalendarPermission.WRITE, store.getCalendar(calendar.id, bob.id)?.permission)
    }

    @Test
    fun orgHeaderTransferRecreatesMissingDefaultTeam() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        installApi(configure = { store = get(); auth = get(); orgs = get(); teams = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        val defaultTeamId = requireNotNull(teams.defaultTeamId(org.id))
        assertTrue(teams.delete(alice.id, defaultTeamId))
        assertNull(teams.defaultTeamId(org.id))

        val calendar = store.createCalendar(alice.id, CreateCalendar(displayName = "Personal"))
        val moved = requireNotNull(store.transferCalendar(calendar.id, alice.id, org.id))
        assertEquals(org.id, moved.organizationId)

        val recreated = requireNotNull(teams.defaultTeamId(org.id))
        val grant = teams.grants(alice.id, recreated).single { it.calendarId == calendar.id }
        assertEquals(CalendarPermission.WRITE, grant.permission)
        assertEquals(CalendarPermission.WRITE, store.getCalendar(calendar.id, bob.id)?.permission)
    }

    @Test
    fun transferPreservesCalendarChildData() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        lateinit var orgs: OrganizationService
        lateinit var database: Database
        installApi(configure = { store = get(); auth = get(); orgs = get(); database = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val org = orgs.create(alice.id, "acme", "Acme")
        val calendar = store.createCalendar(alice.id, CreateCalendar(displayName = "Personal"))
        val event = store.createEvent(
            calendar.id,
            alice.id,
            CreateEvent(title = "Standup", start = start, end = end),
        )
        store.addShare(calendar.id, alice.id, bob.id, CalendarPermission.READ)
        val shared = requireNotNull(store.setPublicLink(calendar.id, alice.id, true))
        val token = requireNotNull(shared.publicLinkToken)
        assertNotNull(store.follow(token, bob.id))
        assertEquals(1, store.countFollowers(calendar.id))
        assertNotNull(store.setCalendarHidden(calendar.id, bob.id, true))
        suspendTransaction(database) {
            CalendarsTable.update({ CalendarsTable.id eq Uuid.parse(calendar.id.value) }) {
                it[requestsEnabled] = true
                it[slotMinutes] = 30
                it[accessMode] = PublicAccessMode.PUBLIC.wire
            }
        }

        val moved = requireNotNull(store.transferCalendar(calendar.id, alice.id, org.id))
        assertEquals(org.id, moved.organizationId)
        assertTrue(moved.publicLinkEnabled)
        assertEquals(token, moved.publicLinkToken)
        assertTrue(moved.requestsEnabled)
        assertEquals(30, moved.slotMinutes)
        assertEquals(PublicAccessMode.PUBLIC, moved.accessMode)

        val refreshed = requireNotNull(store.getCalendar(calendar.id, alice.id))
        assertTrue(refreshed.publicLinkEnabled)
        assertEquals(token, refreshed.publicLinkToken)
        assertTrue(refreshed.requestsEnabled)
        assertEquals(30, refreshed.slotMinutes)
        assertEquals(PublicAccessMode.PUBLIC, refreshed.accessMode)
        assertEquals(listOf(event.id), store.listEvents(calendar.id, alice.id).map { it.id })
        assertNotNull(
            store.createEvent(calendar.id, alice.id, CreateEvent(title = "Second", start = start, end = end)),
        )
        assertEquals(listOf(bob.id), store.listShares(calendar.id, alice.id).map { it.userId })
        assertEquals(1, store.countFollowers(calendar.id))
        assertNotNull(store.publicCalendar(token))
        assertEquals(true, store.getCalendar(calendar.id, bob.id)?.hidden)
    }

    @Test
    fun connectionOwnerCanTransferSyncedCalendarIntoOrganization() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        lateinit var orgs: OrganizationService
        lateinit var database: Database
        installApi(configure = { store = get(); auth = get(); orgs = get(); database = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        val synced = store.createCalendar(alice.id, CreateCalendar(displayName = "Synced"))
        seedSync(database, alice, synced)

        val moved = requireNotNull(store.transferCalendar(synced.id, alice.id, org.id))
        assertEquals(org.id, moved.organizationId)
        assertEquals(alice.id, moved.ownerId)
        assertEquals(CalendarPermission.OWNER, moved.permission)
        assertEquals(org.id, store.getCalendar(synced.id, alice.id)?.organizationId)
        assertEquals(CalendarPermission.WRITE, store.getCalendar(synced.id, bob.id)?.permission)
        suspendTransaction(database) {
            val mapping = ExternalCalendarsTable.selectAll()
                .where { ExternalCalendarsTable.calendarId eq Uuid.parse(synced.id.value) }
                .single()
            assertNotNull(
                CalendarConnectionsTable.selectAll()
                    .where {
                        CalendarConnectionsTable.id eq mapping[ExternalCalendarsTable.connectionId]
                    }
                    .singleOrNull(),
            )
        }
    }

    @Test
    fun orgAdminWhoDoesNotOwnConnectionCannotTransferSyncedCalendar() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        lateinit var orgs: OrganizationService
        lateinit var database: Database
        installApi(configure = { store = get(); auth = get(); orgs = get(); database = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.ADMIN)
        val synced = store.createCalendar(
            alice.id,
            CreateCalendar(displayName = "Synced", organizationId = org.id),
        )
        seedSync(database, alice, synced)

        assertFailsWith<CalendarException.Forbidden> {
            store.transferCalendar(synced.id, bob.id, null)
        }
        val unchanged = requireNotNull(store.getCalendar(synced.id, alice.id))
        assertEquals(org.id, unchanged.organizationId)
        assertEquals(alice.id, unchanged.ownerId)
        suspendTransaction(database) {
            assertEquals(
                1,
                ExternalCalendarsTable.selectAll()
                    .where { ExternalCalendarsTable.calendarId eq Uuid.parse(synced.id.value) }
                    .count(),
            )
        }
    }

    @Test
    fun onlyConnectionOwnerCanDeleteSyncedCalendar() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        lateinit var orgs: OrganizationService
        lateinit var database: Database
        installApi(configure = { store = get(); auth = get(); orgs = get(); database = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.ADMIN)
        val synced = store.createCalendar(
            alice.id,
            CreateCalendar(displayName = "Synced", organizationId = org.id),
        )
        seedSync(database, alice, synced)

        assertFailsWith<CalendarException.Forbidden> {
            store.deleteCalendar(synced.id, bob.id)
        }
        assertNotNull(store.getCalendar(synced.id, alice.id))

        assertTrue(store.deleteCalendar(synced.id, alice.id))
        assertNull(store.getCalendar(synced.id, alice.id))
        suspendTransaction(database) {
            assertEquals(
                0,
                ExternalCalendarsTable.selectAll()
                    .where { ExternalCalendarsTable.calendarId eq Uuid.parse(synced.id.value) }
                    .count(),
            )
            assertEquals(1, CalendarConnectionsTable.selectAll().count())
        }
    }

    private suspend fun seedSync(database: Database, owner: User, calendar: Calendar) {
        suspendTransaction(database) {
            val connectionId = Uuid.random()
            val now = Clock.System.now()
            CalendarConnectionsTable.insert {
                it[id] = connectionId
                it[CalendarConnectionsTable.userId] = Uuid.parse(owner.id.value)
                it[provider] = "discord"
                it[externalAccountId] = "discord-account"
                it[accessTokenCiphertext] = "sealed"
                it[accessTokenNonce] = "nonce"
                it[tokenKeyVersion] = 1
                it[status] = "active"
                it[createdAt] = now
                it[updatedAt] = now
            }
            ExternalCalendarsTable.insert {
                it[id] = Uuid.random()
                it[ExternalCalendarsTable.connectionId] = connectionId
                it[ExternalCalendarsTable.externalId] = "guild-1"
                it[ExternalCalendarsTable.calendarId] = Uuid.parse(calendar.id.value)
                it[externalName] = "Synced"
                it[syncDirection] = "both"
                it[enabled] = true
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    private suspend fun registerUser(auth: AuthService, username: String): User =
        auth.register(RegisterUser(username = username, password = "password12")).session?.user
            ?: error("registration did not create a session")

    private companion object {
        val MissingOrganization = OrganizationId("00000000-0000-0000-0000-000000000001")
        val start: Instant = Instant.parse("2026-09-07T10:00:00Z")
        val end: Instant = Instant.parse("2026-09-07T10:30:00Z")
    }
}
