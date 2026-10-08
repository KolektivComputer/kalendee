package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.OrganizationTeamsResponse
import dev.kolektiv.kalendee.api.TeamCalendarOut
import dev.kolektiv.kalendee.api.TeamMemberOut
import dev.kolektiv.kalendee.api.TeamOut
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import org.koin.ktor.ext.get

class OrganizationTeamApiTest {
    @Test
    fun teamsBoardCreateUpdateDelete() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var store: CalendarStore
        installApi(configure = { orgs = get(); store = get() })
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
        orgs.addMember(aliceUser.id, org.id, bobUser.id, OrganizationRole.MEMBER)
        orgs.addMember(aliceUser.id, org.id, carolUser.id, OrganizationRole.MEMBER)
        val calendar = store.createCalendar(aliceUser.id, CreateCalendar("Team", organizationId = org.id))
        val orgId = org.id.value

        val ownerBoard = alice.get("/api/v1/organizations/$orgId/teams")
        assertEquals(HttpStatusCode.OK, ownerBoard.status)
        val owner = ownerBoard.body<OrganizationTeamsResponse>()
        assertEquals(orgId, owner.organizationId)
        assertEquals("owner", owner.viewerRole)
        assertTrue(owner.canManageTeams)
        assertEquals(listOf("all"), owner.teams.map { it.slug })
        val defaultTeam = owner.teams.single()
        assertTrue(defaultTeam.isDefault)
        assertEquals(3, defaultTeam.memberCount)
        assertEquals("member", defaultTeam.viewerRole)
        assertTrue(defaultTeam.canManageMembers && defaultTeam.canManageGrants && defaultTeam.canDelete)
        assertEquals(
            listOf("alice", "bob", "carol"),
            defaultTeam.members.map { it.username },
        )
        assertTrue(defaultTeam.members.single { it.username == "alice" }.isSelf)
        assertEquals(
            listOf(calendar.id.value),
            owner.manageableCalendars.map { it.id },
        )
        assertEquals("Team", owner.manageableCalendars.single().displayName)
        assertEquals(calendar.color, owner.manageableCalendars.single().color)

        val created = alice.postJson(
            "/api/v1/organizations/$orgId/teams",
            """{"slug":"design","name":"Design","description":"Product designers"}""",
        )
        assertEquals(HttpStatusCode.Created, created.status)
        val design = created.body<TeamOut>()
        assertEquals("design", design.slug)
        assertEquals("Design", design.name)
        assertEquals("Product designers", design.description)
        assertEquals(orgId, design.organizationId)
        assertFalse(design.isDefault)
        assertEquals(0, design.memberCount)
        assertNull(design.viewerRole)
        assertTrue(design.canManageMembers && design.canManageGrants && design.canDelete)
        assertTrue(design.members.isEmpty() && design.grants.isEmpty())

        assertEquals(
            HttpStatusCode.Conflict,
            alice.postJson("/api/v1/organizations/$orgId/teams", """{"slug":"design","name":"Dup"}""").status,
        )
        assertEquals(
            HttpStatusCode.Conflict,
            alice.postJson("/api/v1/organizations/$orgId/teams", """{"slug":"all","name":"Reserved"}""").status,
        )

        val memberBoard = bob.get("/api/v1/organizations/$orgId/teams").body<OrganizationTeamsResponse>()
        assertEquals("member", memberBoard.viewerRole)
        assertFalse(memberBoard.canManageTeams)
        assertEquals(listOf("all"), memberBoard.teams.map { it.slug })
        assertFalse(memberBoard.teams.single().canDelete)
        assertTrue(memberBoard.manageableCalendars.isEmpty())
        assertEquals(HttpStatusCode.Forbidden, dave.get("/api/v1/organizations/$orgId/teams").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            bob.postJson("/api/v1/organizations/$orgId/teams", """{"slug":"secret","name":"Secret"}""").status,
        )

        val updated = alice.patchJson("/api/v1/teams/${design.id}", """{"name":"Product","description":"Updated"}""")
        assertEquals(HttpStatusCode.OK, updated.status)
        assertEquals("Product", updated.body<TeamOut>().name)
        assertEquals("Updated", updated.body<TeamOut>().description)
        assertEquals(
            HttpStatusCode.Forbidden,
            bob.patchJson("/api/v1/teams/${design.id}", """{"name":"Nope"}""").status,
        )

        assertEquals(HttpStatusCode.NoContent, alice.delete("/api/v1/teams/${design.id}").status)
        assertEquals(
            listOf("all"),
            alice.get("/api/v1/organizations/$orgId/teams").body<OrganizationTeamsResponse>().teams.map { it.slug },
        )
    }

    @Test
    fun teamMembersAndRoles() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var store: CalendarStore
        installApi(configure = { orgs = get(); store = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")
        val carol = jsonClient()
        val carolUser = carol.registerAndLogin("carol")
        val dave = jsonClient()
        val daveUser = dave.registerAndLogin("dave")
        val org = orgs.create(aliceUser.id, "acme", "Acme")
        orgs.addMember(aliceUser.id, org.id, bobUser.id, OrganizationRole.MEMBER)
        orgs.addMember(aliceUser.id, org.id, carolUser.id, OrganizationRole.MEMBER)
        val teamId = alice.postJson(
            "/api/v1/organizations/${org.id.value}/teams",
            """{"slug":"design","name":"Design"}""",
        ).body<TeamOut>().id

        val added = alice.postJson("/api/v1/teams/$teamId/members", """{"userId":"${bobUser.id.value}"}""")
        assertEquals(HttpStatusCode.OK, added.status)
        val bobMember = added.body<TeamMemberOut>()
        assertEquals(bobUser.id.value, bobMember.userId)
        assertEquals("bob", bobMember.username)
        assertEquals("member", bobMember.role)
        assertFalse(bobMember.isSelf)
        assertNull(bobMember.avatarUrl)

        assertEquals(
            HttpStatusCode.Conflict,
            alice.postJson("/api/v1/teams/$teamId/members", """{"userId":"${bobUser.id.value}"}""").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            alice.postJson("/api/v1/teams/$teamId/members", """{"userId":"${daveUser.id.value}"}""").status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            bob.postJson("/api/v1/teams/$teamId/members", """{"userId":"${carolUser.id.value}"}""").status,
        )

        val promoted = alice.patchJson(
            "/api/v1/teams/$teamId/members/${bobUser.id.value}",
            """{"role":"maintainer"}""",
        )
        assertEquals(HttpStatusCode.OK, promoted.status)
        assertEquals("maintainer", promoted.body<TeamMemberOut>().role)

        val bobAddedCarol = bob.postJson(
            "/api/v1/teams/$teamId/members",
            """{"userId":"${carolUser.id.value}"}""",
        )
        assertEquals(HttpStatusCode.OK, bobAddedCarol.status)
        assertEquals("carol", bobAddedCarol.body<TeamMemberOut>().username)

        assertEquals(
            HttpStatusCode.BadRequest,
            alice.patchJson(
                "/api/v1/teams/$teamId/members/${bobUser.id.value}",
                """{"role":"owner"}""",
            ).status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            alice.patchJson(
                "/api/v1/teams/$teamId/members/${daveUser.id.value}",
                """{"role":"member"}""",
            ).status,
        )

        assertEquals(HttpStatusCode.NoContent, bob.delete("/api/v1/teams/$teamId/members/${carolUser.id.value}").status)
        assertEquals(HttpStatusCode.NoContent, alice.delete("/api/v1/teams/$teamId/members/${carolUser.id.value}").status)
        assertEquals(HttpStatusCode.Forbidden, bob.delete("/api/v1/teams/$teamId").status)
    }

    @Test
    fun calendarGrants() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var store: CalendarStore
        installApi(configure = { orgs = get(); store = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")
        val org = orgs.create(aliceUser.id, "acme", "Acme")
        orgs.addMember(aliceUser.id, org.id, bobUser.id, OrganizationRole.MEMBER)
        val calendar = store.createCalendar(aliceUser.id, CreateCalendar("Team", organizationId = org.id))
        val teamId = alice.postJson(
            "/api/v1/organizations/${org.id.value}/teams",
            """{"slug":"design","name":"Design"}""",
        ).body<TeamOut>().id
        alice.postJson("/api/v1/teams/$teamId/members", """{"userId":"${bobUser.id.value}"}""")

        val granted = alice.putJson(
            "/api/v1/teams/$teamId/calendars/${calendar.id.value}",
            """{"permission":"read"}""",
        )
        assertEquals(HttpStatusCode.OK, granted.status)
        val grant = granted.body<TeamCalendarOut>()
        assertEquals(calendar.id.value, grant.calendarId)
        assertEquals("Team", grant.displayName)
        assertEquals(calendar.color, grant.color)
        assertEquals("read", grant.permission)

        val upgraded = alice.putJson(
            "/api/v1/teams/$teamId/calendars/${calendar.id.value}",
            """{"permission":"write"}""",
        )
        assertEquals("write", upgraded.body<TeamCalendarOut>().permission)

        val board = alice.get("/api/v1/organizations/${org.id.value}/teams").body<OrganizationTeamsResponse>()
        val design = board.teams.single { it.id == teamId }
        assertEquals(1, design.grants.size)
        assertEquals("write", design.grants.single().permission)

        assertEquals(
            HttpStatusCode.Forbidden,
            bob.putJson("/api/v1/teams/$teamId/calendars/${calendar.id.value}", """{"permission":"read"}""").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            alice.putJson("/api/v1/teams/$teamId/calendars/${calendar.id.value}", """{"permission":"owner"}""").status,
        )

        assertEquals(
            HttpStatusCode.NoContent,
            alice.delete("/api/v1/teams/$teamId/calendars/${calendar.id.value}").status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            alice.delete("/api/v1/teams/$teamId/calendars/${calendar.id.value}").status,
        )
        val cleared = alice.get("/api/v1/organizations/${org.id.value}/teams").body<OrganizationTeamsResponse>()
        assertTrue(cleared.teams.single { it.id == teamId }.grants.isEmpty())
    }

    @Test
    fun memberSeesOnlyOwnTeams() = testApplication {
        lateinit var orgs: OrganizationService
        installApi(configure = { orgs = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")
        val org = orgs.create(aliceUser.id, "acme", "Acme")
        orgs.addMember(aliceUser.id, org.id, bobUser.id, OrganizationRole.MEMBER)
        val orgId = org.id.value
        val teamA = alice.postJson(
            "/api/v1/organizations/$orgId/teams",
            """{"slug":"alpha","name":"Alpha"}""",
        ).body<TeamOut>()
        alice.postJson("/api/v1/organizations/$orgId/teams", """{"slug":"beta","name":"Beta"}""")
        alice.postJson("/api/v1/teams/${teamA.id}/members", """{"userId":"${bobUser.id.value}"}""")

        val aliceBoard = alice.get("/api/v1/organizations/$orgId/teams").body<OrganizationTeamsResponse>()
        assertEquals(listOf("all", "alpha", "beta"), aliceBoard.teams.map { it.slug }.sorted())

        val bobBoard = bob.get("/api/v1/organizations/$orgId/teams").body<OrganizationTeamsResponse>()
        assertEquals(listOf("all", "alpha"), bobBoard.teams.map { it.slug }.sorted())
        val alpha = bobBoard.teams.single { it.slug == "alpha" }
        assertEquals("member", alpha.viewerRole)
        assertFalse(alpha.canManageMembers)
        assertFalse(alpha.canManageGrants)
        assertFalse(alpha.canDelete)
        assertEquals(1, alpha.memberCount)

        alice.patchJson("/api/v1/teams/${teamA.id}/members/${bobUser.id.value}", """{"role":"maintainer"}""")
        val maintained = bob.get("/api/v1/organizations/$orgId/teams").body<OrganizationTeamsResponse>()
            .teams.single { it.slug == "alpha" }
        assertEquals("maintainer", maintained.viewerRole)
        assertTrue(maintained.canManageMembers)
        assertTrue(maintained.canManageGrants)
        assertFalse(maintained.canDelete)
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

private suspend fun HttpClient.putJson(path: String, body: String): HttpResponse =
    put(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
