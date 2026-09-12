package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.OrganizationTeamId
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import dev.kolektiv.kalendee.organizations.OrganizationTeamRole
import dev.kolektiv.kalendee.organizations.OrganizationTeamService
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.koin.ktor.ext.get

class OrganizationTeamServiceTest {
    @Test
    fun teamCrudPermissionMatrix() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        installApi(configure = { orgs = get(); teams = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val carol = jsonClient().registerAndLogin("carol")
        val dave = jsonClient().registerAndLogin("dave")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        orgs.addMember(alice.id, org.id, carol.id, OrganizationRole.ADMIN)

        val defaultTeam = teams.defaultTeamId(org.id) ?: error("default team missing")
        val design = teams.create(alice.id, org.id, "Design", "Design", "Product designers")
        assertEquals("design", design.slug)
        assertEquals("Design", design.name)
        assertEquals("Product designers", design.description)
        assertEquals(org.id, design.organizationId)
        assertEquals(setOf(defaultTeam, design.id), teams.teamsFor(alice.id, org.id).map { it.id }.toSet())
        assertEquals(setOf(defaultTeam, design.id), teams.teamsFor(carol.id, org.id).map { it.id }.toSet())
        assertEquals(setOf(defaultTeam), teams.teamsFor(bob.id, org.id).map { it.id }.toSet())
        assertEquals(design.id, teams.team(alice.id, design.id)?.id)
        assertNull(teams.team(bob.id, design.id))
        assertFailsWith<CalendarException.Forbidden> { teams.teamsFor(dave.id, org.id) }

        val ops = teams.create(carol.id, org.id, "ops", "Ops")
        assertEquals("ops", ops.slug)
        assertFailsWith<CalendarException.Forbidden> { teams.create(bob.id, org.id, "bobs", "Bobs") }
        assertFailsWith<CalendarException.Forbidden> { teams.create(dave.id, org.id, "daves", "Daves") }
        assertFailsWith<CalendarException.Conflict> { teams.create(alice.id, org.id, "design", "Duplicate") }
        assertFailsWith<CalendarException.Conflict> { teams.create(alice.id, org.id, "all", "Default") }
        assertFailsWith<CalendarException.Invalid> { teams.create(alice.id, org.id, "Bad Slug", "Bad") }
        assertFailsWith<CalendarException.Invalid> { teams.create(alice.id, org.id, "ab", "Too short") }
        assertFailsWith<CalendarException.Invalid> { teams.create(alice.id, org.id, "team", " ") }

        val renamed = teams.update(alice.id, design.id, name = "Product", description = "Updated")
        assertEquals("Product", renamed.name)
        assertEquals("Updated", renamed.description)
        assertEquals("Product Team", teams.update(carol.id, design.id, name = "Product Team").name)
        assertNull(teams.update(alice.id, design.id, description = "").description)
        assertFailsWith<CalendarException.Forbidden> { teams.update(bob.id, design.id, name = "Nope") }
        assertFailsWith<CalendarException.Forbidden> { teams.delete(bob.id, design.id) }
        assertFailsWith<CalendarException.Forbidden> { teams.update(dave.id, design.id, name = "Nope") }

        assertTrue(teams.delete(alice.id, ops.id))
        assertNull(teams.team(alice.id, ops.id))
        assertFailsWith<CalendarException.NotFound> { teams.update(alice.id, ops.id, name = "Gone") }
        assertFailsWith<CalendarException.NotFound> {
            teams.delete(alice.id, OrganizationTeamId.parse(Uuid.random().toString()))
        }
    }

    @Test
    fun rosterRequiresMaintainerAndOrgMembership() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        installApi(configure = { orgs = get(); teams = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val carol = jsonClient().registerAndLogin("carol")
        val dave = jsonClient().registerAndLogin("dave")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        orgs.addMember(alice.id, org.id, carol.id, OrganizationRole.MEMBER)
        val team = teams.create(alice.id, org.id, "design", "Design")

        assertFailsWith<CalendarException.Forbidden> { teams.members(carol.id, team.id) }
        assertFailsWith<CalendarException.Forbidden> { teams.grants(carol.id, team.id) }
        assertNull(teams.team(carol.id, team.id))

        val bobMember = teams.addMember(alice.id, team.id, bob.id)
        assertEquals(bob.id, bobMember.userId)
        assertEquals(OrganizationTeamRole.MEMBER, bobMember.role)
        assertEquals(org.id, bobMember.organizationId)
        assertFailsWith<CalendarException.Conflict> { teams.addMember(alice.id, team.id, bob.id) }
        assertFailsWith<CalendarException.Invalid> { teams.addMember(alice.id, team.id, dave.id) }
        assertFailsWith<CalendarException.Forbidden> { teams.addMember(bob.id, team.id, carol.id) }

        val maintainer = teams.setMemberRole(alice.id, team.id, bob.id, OrganizationTeamRole.MAINTAINER)
        assertEquals(OrganizationTeamRole.MAINTAINER, maintainer.role)
        assertEquals(setOf(bob.id), teams.members(bob.id, team.id).map { it.userId }.toSet())
        assertEquals(team.id, teams.team(bob.id, team.id)?.id)

        val carolMember = teams.addMember(bob.id, team.id, carol.id)
        assertEquals(OrganizationTeamRole.MEMBER, carolMember.role)
        assertEquals(team.id, teams.team(carol.id, team.id)?.id)
        assertFailsWith<CalendarException.Forbidden> { teams.update(bob.id, team.id, name = "Nope") }
        assertFailsWith<CalendarException.Forbidden> { teams.delete(bob.id, team.id) }

        assertTrue(teams.removeMember(bob.id, team.id, carol.id))
        assertFalse(teams.removeMember(bob.id, team.id, carol.id))
        assertFailsWith<CalendarException.NotFound> {
            teams.setMemberRole(alice.id, team.id, carol.id, OrganizationTeamRole.MAINTAINER)
        }
        assertEquals(OrganizationTeamRole.parse("member"), OrganizationTeamRole.MEMBER)
        assertEquals(OrganizationTeamRole.parse("MAINTAINER"), OrganizationTeamRole.MAINTAINER)
        assertFailsWith<CalendarException.Invalid> { OrganizationTeamRole.parse("owner") }
    }

    @Test
    fun grantsRequireSameOrgAndCalendarOwnership() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        lateinit var store: CalendarStore
        installApi(configure = { orgs = get(); teams = get(); store = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val carol = jsonClient().registerAndLogin("carol")
        val dave = jsonClient().registerAndLogin("dave")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        val calendar = store.createCalendar(alice.id, CreateCalendar("Team", organizationId = org.id))
        val team = teams.create(alice.id, org.id, "design", "Design")
        teams.addMember(alice.id, team.id, bob.id)
        teams.setMemberRole(alice.id, team.id, bob.id, OrganizationTeamRole.MAINTAINER)

        val readGrant = teams.grant(alice.id, calendar.id, team.id, CalendarPermission.READ)
        assertEquals(CalendarPermission.READ, readGrant.permission)
        assertEquals(calendar.id, readGrant.calendarId)
        assertEquals("Team", readGrant.calendarDisplayName)
        assertEquals(calendar.color, readGrant.calendarColor)
        val writeGrant = teams.grant(alice.id, calendar.id, team.id, CalendarPermission.WRITE)
        assertEquals(CalendarPermission.WRITE, writeGrant.permission)
        assertEquals(1, teams.grants(alice.id, team.id).size)
        assertEquals(
            mapOf(Uuid.parse(calendar.id.value) to CalendarPermission.WRITE),
            teams.teamCalendarsFor(bob.id),
        )
        assertFailsWith<CalendarException.Invalid> {
            teams.grant(alice.id, calendar.id, team.id, CalendarPermission.OWNER)
        }
        assertFailsWith<CalendarException.Invalid> {
            teams.grant(alice.id, calendar.id, team.id, CalendarPermission.FOLLOW)
        }

        assertFailsWith<CalendarException.Forbidden> {
            teams.grant(bob.id, calendar.id, team.id, CalendarPermission.READ)
        }
        assertFailsWith<CalendarException.Forbidden> {
            teams.grant(dave.id, calendar.id, team.id, CalendarPermission.READ)
        }

        val otherOrg = orgs.create(carol.id, "other", "Other")
        val otherCalendar = store.createCalendar(carol.id, CreateCalendar("Other", organizationId = otherOrg.id))
        assertFailsWith<CalendarException.Invalid> {
            teams.grant(alice.id, otherCalendar.id, team.id, CalendarPermission.READ)
        }

        assertTrue(teams.revoke(alice.id, calendar.id, team.id))
        assertFalse(teams.revoke(alice.id, calendar.id, team.id))
        assertTrue(teams.grants(alice.id, team.id).isEmpty())
        assertFailsWith<CalendarException.NotFound> {
            teams.grant(alice.id, MissingCalendar, team.id, CalendarPermission.READ)
        }
    }

    private companion object {
        val MissingCalendar = CalendarId("00000000-0000-0000-0000-000000000001")
    }
}
