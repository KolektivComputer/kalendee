package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.db.OrganizationTeamsTable
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import dev.kolektiv.kalendee.organizations.OrganizationTeamService
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.ktor.ext.get

class OrganizationTeamBackfillTest {
    @Test
    fun ensureDefaultsBackfillsOrganizationsWithoutTeams() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        lateinit var store: CalendarStore
        lateinit var database: Database
        installApi(configure = { orgs = get(); teams = get(); store = get(); database = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val org = orgs.create(alice.id, "legacy", "Legacy")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        val calendar = store.createCalendar(alice.id, CreateCalendar("Team", organizationId = org.id))
        assertEquals(CalendarPermission.WRITE, store.getCalendar(calendar.id, bob.id)?.permission)

        suspendTransaction(database) {
            OrganizationTeamsTable.deleteWhere {
                OrganizationTeamsTable.organizationId eq Uuid.parse(org.id.value)
            }
        }
        assertNull(teams.defaultTeamId(org.id))
        assertNull(store.getCalendar(calendar.id, bob.id))

        assertEquals(1, teams.ensureDefaults())
        val defaultTeam = teams.defaultTeamId(org.id) ?: error("default team missing")
        assertEquals("all", teams.team(alice.id, defaultTeam)?.slug)
        assertEquals(
            setOf(alice.id, bob.id),
            teams.members(alice.id, defaultTeam).map { it.userId }.toSet(),
        )
        val grants = teams.grants(alice.id, defaultTeam)
        assertEquals(1, grants.size)
        assertEquals(calendar.id, grants.single().calendarId)
        assertEquals(CalendarPermission.WRITE, grants.single().permission)
        assertEquals(CalendarPermission.WRITE, store.getCalendar(calendar.id, bob.id)?.permission)

        assertEquals(0, teams.ensureDefaults())
        assertEquals(1, teams.teamsFor(alice.id, org.id).size)
    }

    @Test
    fun ensureDefaultsIsIdempotentAndLeavesExistingTeamsAlone() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        installApi(configure = { orgs = get(); teams = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        val design = teams.create(alice.id, org.id, "design", "Design")
        teams.addMember(alice.id, design.id, bob.id)

        val defaultTeam = teams.defaultTeamId(org.id) ?: error("default team missing")
        assertEquals(0, teams.ensureDefaults())
        assertEquals(
            setOf(design.id, defaultTeam),
            teams.teamsFor(alice.id, org.id).map { it.id }.toSet(),
        )
        assertTrue(teams.removeMember(alice.id, design.id, bob.id))
        assertEquals(0, teams.ensureDefaults())
        assertTrue(teams.members(alice.id, design.id).none { it.userId == bob.id })
    }

    @Test
    fun removingOrgMemberRemovesAllTeamMemberships() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        installApi(configure = { orgs = get(); teams = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        val design = teams.create(alice.id, org.id, "design", "Design")
        teams.addMember(alice.id, design.id, bob.id)
        val defaultTeam = teams.defaultTeamId(org.id) ?: error("default team missing")

        assertTrue(orgs.removeMember(alice.id, org.id, bob.id))
        assertTrue(teams.members(alice.id, defaultTeam).none { it.userId == bob.id })
        assertTrue(teams.members(alice.id, design.id).none { it.userId == bob.id })
        assertFailsWith<CalendarException.Forbidden> { teams.teamsFor(bob.id, org.id) }
    }
}
