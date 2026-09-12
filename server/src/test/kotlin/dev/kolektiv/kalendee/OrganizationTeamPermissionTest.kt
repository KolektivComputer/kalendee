package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.InstantRange
import dev.kolektiv.kalendee.calendar.UpdateCalendar
import dev.kolektiv.kalendee.calendar.UpdateEvent
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import dev.kolektiv.kalendee.organizations.OrganizationTeamService
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.koin.ktor.ext.get

class OrganizationTeamPermissionTest {
    @Test
    fun defaultTeamPreservesPreTeamsBehavior() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        lateinit var store: CalendarStore
        installApi(configure = { orgs = get(); teams = get(); store = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val carol = jsonClient().registerAndLogin("carol")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)
        orgs.addMember(alice.id, org.id, carol.id, OrganizationRole.MEMBER)

        val defaultTeam = teams.defaultTeamId(org.id) ?: error("default team missing")
        assertEquals("all", teams.team(alice.id, defaultTeam)?.slug)
        assertEquals("Everyone", teams.team(alice.id, defaultTeam)?.name)
        assertEquals(
            setOf(alice.id, bob.id, carol.id),
            teams.members(alice.id, defaultTeam).map { it.userId }.toSet(),
        )

        val calendar = store.createCalendar(alice.id, CreateCalendar("Team", organizationId = org.id))
        val grants = teams.grants(alice.id, defaultTeam)
        assertEquals(1, grants.size)
        assertEquals(calendar.id, grants.single().calendarId)
        assertEquals(CalendarPermission.WRITE, grants.single().permission)

        assertEquals(CalendarPermission.WRITE, store.getCalendar(calendar.id, bob.id)?.permission)
        assertEquals(
            CalendarPermission.WRITE,
            store.listCalendars(bob.id).single { it.id == calendar.id }.permission,
        )

        val event = store.createEvent(
            calendar.id,
            bob.id,
            CreateEvent(
                title = "Standup",
                start = Instant.parse("2026-03-01T10:00:00Z"),
                end = Instant.parse("2026-03-01T10:30:00Z"),
            ),
        )
        assertEquals(
            "Standup v2",
            store.updateEvent(event.id, bob.id, UpdateEvent(title = "Standup v2"))?.title,
        )
        assertTrue(store.deleteEvent(event.id, bob.id))

        assertNull(store.updateCalendar(calendar.id, bob.id, UpdateCalendar(displayName = "Nope")))
        assertFalse(store.deleteCalendar(calendar.id, bob.id))
        assertTrue(store.listShares(calendar.id, bob.id).isEmpty())
        assertFailsWith<CalendarException.NotFound> {
            store.addShare(calendar.id, bob.id, carol.id, CalendarPermission.READ)
        }
        assertNull(store.setPublicLink(calendar.id, bob.id, true))

        val memberCalendar = store.createCalendar(
            bob.id,
            CreateCalendar("Bob's Team", organizationId = org.id),
        )
        assertTrue(
            teams.grants(alice.id, defaultTeam)
                .any { it.calendarId == memberCalendar.id && it.permission == CalendarPermission.WRITE },
        )
        assertEquals(CalendarPermission.WRITE, store.getCalendar(memberCalendar.id, carol.id)?.permission)
    }

    @Test
    fun teamReadGrantIsReadOnly() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        lateinit var store: CalendarStore
        installApi(configure = { orgs = get(); teams = get(); store = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val carol = jsonClient().registerAndLogin("carol")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)

        val defaultTeam = teams.defaultTeamId(org.id) ?: error("default team missing")
        assertTrue(teams.removeMember(alice.id, defaultTeam, bob.id))

        val calendar = store.createCalendar(alice.id, CreateCalendar("Team", organizationId = org.id))
        assertNull(store.getCalendar(calendar.id, bob.id))
        assertTrue(store.listCalendars(bob.id).none { it.id == calendar.id })

        val design = teams.create(alice.id, org.id, "design", "Design")
        teams.addMember(alice.id, design.id, bob.id)
        teams.grant(alice.id, calendar.id, design.id, CalendarPermission.READ)

        assertEquals(CalendarPermission.READ, store.getCalendar(calendar.id, bob.id)?.permission)
        assertEquals(
            CalendarPermission.READ,
            store.listCalendars(bob.id).single { it.id == calendar.id }.permission,
        )

        val event = store.createEvent(
            calendar.id,
            alice.id,
            CreateEvent(
                title = "Standup",
                start = Instant.parse("2026-03-01T10:00:00Z"),
                end = Instant.parse("2026-03-01T10:30:00Z"),
            ),
        )
        assertEquals(event.id, store.getEvent(event.id, bob.id)?.id)
        val day = InstantRange(
            Instant.parse("2026-03-01T00:00:00Z"),
            Instant.parse("2026-03-02T00:00:00Z"),
        )
        assertEquals(listOf(event.id), store.listEvents(bob.id, day).map { it.id })

        assertFailsWith<CalendarException.Forbidden> {
            store.createEvent(
                calendar.id,
                bob.id,
                CreateEvent(
                    title = "Nope",
                    start = Instant.parse("2026-03-02T10:00:00Z"),
                    end = Instant.parse("2026-03-02T10:30:00Z"),
                ),
            )
        }
        assertFailsWith<CalendarException.Forbidden> {
            store.updateEvent(event.id, bob.id, UpdateEvent(title = "Nope"))
        }
        assertFailsWith<CalendarException.Forbidden> { store.deleteEvent(event.id, bob.id) }

        assertNull(store.updateCalendar(calendar.id, bob.id, UpdateCalendar(displayName = "Nope")))
        assertTrue(store.listShares(calendar.id, bob.id).isEmpty())
        assertFailsWith<CalendarException.NotFound> {
            store.addShare(calendar.id, bob.id, carol.id, CalendarPermission.READ)
        }
        assertNull(store.setPublicLink(calendar.id, bob.id, true))
        assertFalse(store.deleteCalendar(calendar.id, bob.id))
    }

    @Test
    fun strongestPermissionWins() = testApplication {
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
        orgs.addMember(alice.id, org.id, carol.id, OrganizationRole.ADMIN)
        orgs.addMember(alice.id, org.id, dave.id, OrganizationRole.MEMBER)

        val defaultTeam = teams.defaultTeamId(org.id) ?: error("default team missing")
        assertTrue(teams.removeMember(alice.id, defaultTeam, bob.id))
        assertTrue(teams.removeMember(alice.id, defaultTeam, dave.id))
        val design = teams.create(alice.id, org.id, "design", "Design")
        teams.addMember(alice.id, design.id, bob.id)
        teams.addMember(alice.id, design.id, carol.id)
        teams.addMember(alice.id, design.id, dave.id)

        val calendarA = store.createCalendar(alice.id, CreateCalendar("A", organizationId = org.id))
        val calendarB = store.createCalendar(alice.id, CreateCalendar("B", organizationId = org.id))
        teams.grant(alice.id, calendarA.id, design.id, CalendarPermission.READ)
        teams.grant(alice.id, calendarB.id, design.id, CalendarPermission.WRITE)
        store.addShare(calendarA.id, alice.id, bob.id, CalendarPermission.WRITE)
        store.addShare(calendarB.id, alice.id, dave.id, CalendarPermission.READ)

        assertEquals(CalendarPermission.WRITE, store.getCalendar(calendarA.id, bob.id)?.permission)
        assertEquals(CalendarPermission.WRITE, store.getCalendar(calendarB.id, dave.id)?.permission)
        assertEquals(
            CalendarPermission.WRITE,
            store.listCalendars(bob.id).single { it.id == calendarA.id }.permission,
        )
        assertEquals(1, store.listCalendars(bob.id).count { it.id == calendarA.id })

        assertEquals(CalendarPermission.OWNER, store.getCalendar(calendarA.id, carol.id)?.permission)
        assertEquals(CalendarPermission.OWNER, store.getCalendar(calendarB.id, carol.id)?.permission)
    }

    @Test
    fun revokingGrantOrMembershipRemovesAccess() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        lateinit var store: CalendarStore
        installApi(configure = { orgs = get(); teams = get(); store = get() })
        startApplication()
        val alice = jsonClient().registerAndLogin("alice")
        val bob = jsonClient().registerAndLogin("bob")
        val org = orgs.create(alice.id, "acme", "Acme")
        orgs.addMember(alice.id, org.id, bob.id, OrganizationRole.MEMBER)

        val defaultTeam = teams.defaultTeamId(org.id) ?: error("default team missing")
        val calendar = store.createCalendar(alice.id, CreateCalendar("Team", organizationId = org.id))
        assertEquals(CalendarPermission.WRITE, store.getCalendar(calendar.id, bob.id)?.permission)

        assertTrue(teams.revoke(alice.id, calendar.id, defaultTeam))
        assertNull(store.getCalendar(calendar.id, bob.id))
        assertTrue(store.listCalendars(bob.id).none { it.id == calendar.id })
        assertTrue(teams.teamCalendarsFor(bob.id).isEmpty())

        val regranted = teams.grant(alice.id, calendar.id, defaultTeam, CalendarPermission.WRITE)
        assertEquals(CalendarPermission.WRITE, regranted.permission)
        assertEquals(CalendarPermission.WRITE, store.getCalendar(calendar.id, bob.id)?.permission)
        assertTrue(teams.removeMember(alice.id, defaultTeam, bob.id))
        assertNull(store.getCalendar(calendar.id, bob.id))

        val design = teams.create(alice.id, org.id, "design", "Design")
        teams.addMember(alice.id, design.id, bob.id)
        teams.grant(alice.id, calendar.id, design.id, CalendarPermission.WRITE)
        assertEquals(CalendarPermission.WRITE, store.getCalendar(calendar.id, bob.id)?.permission)
        assertTrue(teams.revoke(alice.id, calendar.id, design.id))
        assertNull(store.getCalendar(calendar.id, bob.id))
        val designGrant = teams.grant(alice.id, calendar.id, design.id, CalendarPermission.WRITE)
        assertEquals(CalendarPermission.WRITE, designGrant.permission)
        assertTrue(teams.removeMember(alice.id, design.id, bob.id))
        assertNull(store.getCalendar(calendar.id, bob.id))
    }
}
