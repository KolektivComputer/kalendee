package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.calendar.OrganizationTeamId
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import dev.kolektiv.kalendee.organizations.OrganizationTeamService
import dev.kolektiv.kalendee.web.AddOrganizationTeamMemberIn
import dev.kolektiv.kalendee.web.CalendarSummary
import dev.kolektiv.kalendee.web.CreateCalendarIn
import dev.kolektiv.kalendee.web.CreateOrganizationIn
import dev.kolektiv.kalendee.web.CreateOrganizationTeamIn
import dev.kolektiv.kalendee.web.DeleteOrganizationTeamIn
import dev.kolektiv.kalendee.web.DeletedOut
import dev.kolektiv.kalendee.web.GrantCalendarToTeamIn
import dev.kolektiv.kalendee.web.HomePage
import dev.kolektiv.kalendee.web.OrganizationSettingsPage
import dev.kolektiv.kalendee.web.OrganizationSummary
import dev.kolektiv.kalendee.web.OrganizationTeamCalendarSummary
import dev.kolektiv.kalendee.web.OrganizationTeamMemberSummary
import dev.kolektiv.kalendee.web.OrganizationTeamSummary
import dev.kolektiv.kalendee.web.OrganizationTeamsIn
import dev.kolektiv.kalendee.web.OrganizationTeamsOut
import dev.kolektiv.kalendee.web.RemoveOrganizationTeamMemberIn
import dev.kolektiv.kalendee.web.RevokeCalendarFromTeamIn
import dev.kolektiv.kalendee.web.SetOrganizationTeamMemberRoleIn
import dev.kolektiv.kalendee.web.UpdateOrganizationTeamIn
import dev.kolektiv.keel.Keel
import dev.kolektiv.keel.KeelJson
import dev.kolektiv.keel.seed.KeelSeed
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.HttpClient
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
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.koin.ktor.ext.get

class OrganizationTeamPageTest {
    @Test
    fun organizationTeamsPayloadExposesRolesAndVisibility() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        installApi(configure = { orgs = get(); teams = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")
        val carol = jsonClient()
        val carolUser = carol.registerAndLogin("carol")
        val dave = jsonClient()
        dave.registerAndLogin("dave")

        val org = alice.createOrganization("acme", "Acme")
        orgs.addMember(aliceUser.id, OrganizationId(org.id), bobUser.id, OrganizationRole.MEMBER)
        orgs.addMember(aliceUser.id, OrganizationId(org.id), carolUser.id, OrganizationRole.MEMBER)

        val defaultTeamId = teams.defaultTeamId(OrganizationId(org.id)) ?: error("default team missing")
        val design = alice.createTeam(org.id, "design", "Design", "Product designers")
        assertFalse(design.isDefault)
        assertEquals(0, design.memberCount)
        assertTrue(design.canManageMembers)
        assertTrue(design.canManageGrants)
        assertTrue(design.canDelete)

        val calendar = alice.createCalendar("Team", organizationId = org.id)
        alice.addTeamMember(design.id, bobUser.id.value)
        alice.grantCalendar(calendar.id, design.id, "read")

        val aliceTeams = alice.organizationTeams(org.id)
        assertEquals("owner", aliceTeams.viewerRole)
        assertTrue(aliceTeams.canManageTeams)
        assertEquals(setOf(defaultTeamId.value, design.id), aliceTeams.teams.map { it.id }.toSet())
        assertEquals(listOf(calendar.id), aliceTeams.manageableCalendars.map { it.id })
        val default = aliceTeams.teams.single { it.id == defaultTeamId.value }
        assertTrue(default.isDefault)
        assertEquals("Everyone", default.name)
        assertTrue(default.members.any { it.username == "alice" && it.isSelf })
        val aliceDesign = aliceTeams.teams.single { it.id == design.id }
        assertEquals(1, aliceDesign.memberCount)
        assertEquals(listOf("bob"), aliceDesign.members.map { it.username })
        assertEquals(listOf(calendar.id), aliceDesign.grants.map { it.calendarId })
        assertEquals("read", aliceDesign.grants.single().permission)

        val bobTeams = bob.organizationTeams(org.id)
        assertEquals("member", bobTeams.viewerRole)
        assertFalse(bobTeams.canManageTeams)
        assertEquals(setOf(defaultTeamId.value, design.id), bobTeams.teams.map { it.id }.toSet())
        val bobDesign = bobTeams.teams.single { it.id == design.id }
        assertEquals("member", bobDesign.viewerRole)
        assertFalse(bobDesign.canManageMembers)
        assertFalse(bobDesign.canDelete)

        val carolTeams = carol.organizationTeams(org.id)
        assertEquals(listOf(defaultTeamId.value), carolTeams.teams.map { it.id })

        val errors = dave.organizationTeamsError(org.id)
        assertTrue(errors.containsKey("organizationId"), "non-members get a field error: $errors")
    }

    @Test
    fun teamManagementLifecycle() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        lateinit var store: CalendarStore
        installApi(configure = { orgs = get(); teams = get(); store = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")
        val carol = jsonClient()
        val carolUser = carol.registerAndLogin("carol")

        val org = alice.createOrganization("acme", "Acme")
        orgs.addMember(aliceUser.id, OrganizationId(org.id), bobUser.id, OrganizationRole.MEMBER)
        orgs.addMember(aliceUser.id, OrganizationId(org.id), carolUser.id, OrganizationRole.MEMBER)

        val design = alice.createTeam(org.id, "design", "Design", "First pass")
        assertEquals("First pass", design.description)
        assertEquals("design", design.slug)
        assertEquals(org.id, design.organizationId)

        val bobMember = alice.addTeamMember(design.id, bobUser.id.value)
        assertEquals("bob", bobMember.username)
        assertEquals("member", bobMember.role)
        assertFalse(bobMember.isSelf)

        val aliceMember = alice.addTeamMember(design.id, aliceUser.id.value)
        assertTrue(aliceMember.isSelf)

        val maintainer = alice.setTeamMemberRole(design.id, bobUser.id.value, "maintainer")
        assertEquals("maintainer", maintainer.role)

        val bobTeams = bob.organizationTeams(org.id)
        val bobDesign = bobTeams.teams.single { it.id == design.id }
        assertEquals("maintainer", bobDesign.viewerRole)
        assertTrue(bobDesign.canManageMembers)
        assertTrue(bobDesign.canManageGrants)
        assertFalse(bobDesign.canDelete)
        assertTrue(bob.updateTeamError(design.id, "Nope").containsKey("teamId"))

        alice.addTeamMember(design.id, carolUser.id.value)
        val renamed = alice.updateTeam(design.id, "Product", "Renamed")
        assertEquals("Product", renamed.name)
        assertEquals("Renamed", renamed.description)
        assertEquals(3, renamed.memberCount)

        val calendar = store.createCalendar(aliceUser.id, CreateCalendar("Team", organizationId = OrganizationId(org.id)))
        val writeGrant = alice.grantCalendar(calendar.id.value, design.id, "write")
        assertEquals("write", writeGrant.permission)
        assertEquals("Team", writeGrant.displayName)
        val readGrant = alice.grantCalendar(calendar.id.value, design.id, "read")
        assertEquals("read", readGrant.permission)
        assertEquals(1, alice.organizationTeams(org.id).teams.single { it.id == design.id }.grants.size)

        alice.revokeCalendar(calendar.id.value, design.id)
        assertTrue(alice.organizationTeams(org.id).teams.single { it.id == design.id }.grants.isEmpty())

        alice.removeTeamMember(design.id, bobUser.id.value)
        assertTrue(
            alice.organizationTeams(org.id).teams.single { it.id == design.id }
                .members.none { it.userId == bobUser.id.value },
        )

        alice.deleteTeam(design.id)
        assertEquals(listOf("all"), alice.organizationTeams(org.id).teams.map { it.slug })
    }

    @Test
    fun teamActionErrorsMapToFields() = testApplication {
        lateinit var orgs: OrganizationService
        installApi(configure = { orgs = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")
        val dave = jsonClient()
        val daveUser = dave.registerAndLogin("dave")

        val org = alice.createOrganization("acme", "Acme")
        orgs.addMember(aliceUser.id, OrganizationId(org.id), bobUser.id, OrganizationRole.MEMBER)
        val design = alice.createTeam(org.id, "design", "Design")

        assertTrue(alice.createTeamError(org.id, "design", "Duplicate").containsKey("slug"))
        assertTrue(alice.createTeamError(org.id, "Bad Slug", "Bad").containsKey("slug"))
        assertTrue(alice.createTeamError(org.id, "short-name", " ").containsKey("name"))
        assertTrue(alice.createTeamError(org.id, "fine", "x".repeat(200)).containsKey("name"))

        val missingTeam = OrganizationTeamId.generate().value
        assertTrue(alice.updateTeamError(missingTeam, "Gone").containsKey("teamId"))
        assertTrue(alice.updateTeamError("not-a-uuid", "Bad").containsKey("teamId"))

        assertTrue(alice.addTeamMemberError(design.id, daveUser.id.value).containsKey("userId"))
        assertTrue(bob.removeTeamMemberError(design.id, aliceUser.id.value).containsKey("userId"))
        assertTrue(alice.setTeamMemberRoleError(design.id, daveUser.id.value, "owner").containsKey("role"))

        val calendar = alice.createCalendar("Team", organizationId = org.id)
        assertTrue(alice.grantCalendarError(calendar.id, design.id, "owner").containsKey("permission"))
        assertTrue(alice.grantCalendarError("not-a-uuid", design.id, "read").containsKey("calendarId"))

        val other = alice.createOrganization("other", "Other")
        val otherCalendar = alice.createCalendar("Other", organizationId = other.id)
        assertTrue(alice.grantCalendarError(otherCalendar.id, design.id, "read").containsKey("calendarId"))
        assertTrue(alice.revokeCalendarError("not-a-uuid", design.id).containsKey("calendarId"))
    }

    @Test
    fun homeAndSettingsCarryTeamContext() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        installApi(configure = { orgs = get(); teams = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")

        val org = alice.createOrganization("acme", "Acme")
        orgs.addMember(aliceUser.id, OrganizationId(org.id), bobUser.id, OrganizationRole.MEMBER)
        val calendar = alice.createCalendar("Team", organizationId = org.id)
        val everyoneCalendar = alice.createCalendar("Everyone", organizationId = org.id)
        val design = alice.createTeam(org.id, "design", "Design")
        alice.addTeamMember(design.id, bobUser.id.value)
        alice.grantCalendar(calendar.id, design.id, "read")

        val defaultTeamId = teams.defaultTeamId(OrganizationId(org.id)) ?: error("default team missing")
        assertTrue(teams.removeMember(aliceUser.id, defaultTeamId, bobUser.id))

        val bobHome = bob.visit("/").decodePage(HomePage.serializer())
        assertEquals(listOf(design.id), bobHome.teams.map { it.id })
        assertEquals("Design", bobHome.teams.single().name)
        assertEquals(1, bobHome.teams.single().memberCount)
        assertEquals("member", bobHome.teams.single().viewerTeamRole)
        val bobCalendar = bobHome.calendars.single { it.id == calendar.id }
        assertEquals(design.id, bobCalendar.teamId)
        assertEquals("Design", bobCalendar.teamName)

        val aliceHome = alice.visit("/").decodePage(HomePage.serializer())
        assertEquals(setOf(defaultTeamId.value, design.id), aliceHome.teams.map { it.id }.toSet())
        val aliceCalendar = aliceHome.calendars.single { it.id == calendar.id }
        assertEquals(design.id, aliceCalendar.teamId)
        assertEquals("Design", aliceCalendar.teamName)
        val aliceEveryoneCalendar = aliceHome.calendars.single { it.id == everyoneCalendar.id }
        assertEquals(defaultTeamId.value, aliceEveryoneCalendar.teamId)
        assertEquals("Everyone", aliceEveryoneCalendar.teamName)

        val aliceSettings = alice.visit("/o/acme/settings").decodePage(OrganizationSettingsPage.serializer())
        assertEquals(
            setOf(calendar.id, everyoneCalendar.id),
            aliceSettings.manageableCalendars.map { it.id }.toSet(),
        )
        val designSettings = aliceSettings.teams.single { it.id == design.id }
        assertEquals(listOf("bob"), designSettings.members.map { it.username })
        assertEquals(listOf(calendar.id), designSettings.grants.map { it.calendarId })
        assertEquals("read", designSettings.grants.single().permission)

        val bobSettings = bob.visit("/o/acme/settings").decodePage(OrganizationSettingsPage.serializer())
        assertTrue(bobSettings.manageableCalendars.isEmpty())
        assertEquals(listOf(design.id), bobSettings.teams.map { it.id })
        assertEquals("read", bobSettings.teams.single().grants.single().permission)
    }

    private suspend fun HttpClient.visit(path: String): KeelSeed {
        val response = get(path) { header(KeelHeaders.VISIT, "true") }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return KeelJson.codec.decodeFromString(KeelSeed.serializer(), response.bodyAsText())
    }

    private fun <T> KeelSeed.decodePage(serializer: KSerializer<T>): T =
        KeelJson.codec.decodeFromJsonElement(serializer, data)

    private suspend fun HttpClient.createOrganization(
        slug: String,
        displayName: String,
    ): OrganizationSummary = action(
        "kalendee.createOrganization",
        CreateOrganizationIn(slug = slug, displayName = displayName),
    )

    private suspend fun HttpClient.createCalendar(
        displayName: String,
        organizationId: String? = null,
    ): CalendarSummary = action(
        "kalendee.createCalendar",
        CreateCalendarIn(displayName = displayName, organizationId = organizationId),
    )

    private suspend fun HttpClient.organizationTeams(organizationId: String): OrganizationTeamsOut = action(
        "kalendee.organizationTeams",
        OrganizationTeamsIn(organizationId = organizationId),
    )

    private suspend fun HttpClient.organizationTeamsError(organizationId: String): Map<String, List<String>> =
        actionErrors("kalendee.organizationTeams", OrganizationTeamsIn(organizationId = organizationId))

    private suspend fun HttpClient.createTeam(
        organizationId: String,
        slug: String,
        name: String,
        description: String? = null,
    ): OrganizationTeamSummary = action(
        "kalendee.createOrganizationTeam",
        CreateOrganizationTeamIn(organizationId = organizationId, slug = slug, name = name, description = description),
    )

    private suspend fun HttpClient.createTeamError(
        organizationId: String,
        slug: String,
        name: String,
    ): Map<String, List<String>> = actionErrors(
        "kalendee.createOrganizationTeam",
        CreateOrganizationTeamIn(organizationId = organizationId, slug = slug, name = name),
    )

    private suspend fun HttpClient.updateTeam(
        teamId: String,
        name: String? = null,
        description: String? = null,
    ): OrganizationTeamSummary = action(
        "kalendee.updateOrganizationTeam",
        UpdateOrganizationTeamIn(teamId = teamId, name = name, description = description),
    )

    private suspend fun HttpClient.updateTeamError(
        teamId: String,
        name: String,
    ): Map<String, List<String>> = actionErrors(
        "kalendee.updateOrganizationTeam",
        UpdateOrganizationTeamIn(teamId = teamId, name = name),
    )

    private suspend fun HttpClient.deleteTeam(teamId: String): DeletedOut = action(
        "kalendee.deleteOrganizationTeam",
        DeleteOrganizationTeamIn(teamId = teamId),
    )

    private suspend fun HttpClient.addTeamMember(teamId: String, userId: String): OrganizationTeamMemberSummary =
        action(
            "kalendee.addOrganizationTeamMember",
            AddOrganizationTeamMemberIn(teamId = teamId, userId = userId),
        )

    private suspend fun HttpClient.addTeamMemberError(
        teamId: String,
        userId: String,
    ): Map<String, List<String>> = actionErrors(
        "kalendee.addOrganizationTeamMember",
        AddOrganizationTeamMemberIn(teamId = teamId, userId = userId),
    )

    private suspend fun HttpClient.removeTeamMember(teamId: String, userId: String): DeletedOut = action(
        "kalendee.removeOrganizationTeamMember",
        RemoveOrganizationTeamMemberIn(teamId = teamId, userId = userId),
    )

    private suspend fun HttpClient.removeTeamMemberError(
        teamId: String,
        userId: String,
    ): Map<String, List<String>> = actionErrors(
        "kalendee.removeOrganizationTeamMember",
        RemoveOrganizationTeamMemberIn(teamId = teamId, userId = userId),
    )

    private suspend fun HttpClient.setTeamMemberRole(
        teamId: String,
        userId: String,
        role: String,
    ): OrganizationTeamMemberSummary = action(
        "kalendee.setOrganizationTeamMemberRole",
        SetOrganizationTeamMemberRoleIn(teamId = teamId, userId = userId, role = role),
    )

    private suspend fun HttpClient.setTeamMemberRoleError(
        teamId: String,
        userId: String,
        role: String,
    ): Map<String, List<String>> = actionErrors(
        "kalendee.setOrganizationTeamMemberRole",
        SetOrganizationTeamMemberRoleIn(teamId = teamId, userId = userId, role = role),
    )

    private suspend fun HttpClient.grantCalendar(
        calendarId: String,
        teamId: String,
        permission: String,
    ): OrganizationTeamCalendarSummary = action(
        "kalendee.grantCalendarToTeam",
        GrantCalendarToTeamIn(calendarId = calendarId, teamId = teamId, permission = permission),
    )

    private suspend fun HttpClient.grantCalendarError(
        calendarId: String,
        teamId: String,
        permission: String,
    ): Map<String, List<String>> = actionErrors(
        "kalendee.grantCalendarToTeam",
        GrantCalendarToTeamIn(calendarId = calendarId, teamId = teamId, permission = permission),
    )

    private suspend fun HttpClient.revokeCalendar(calendarId: String, teamId: String): DeletedOut = action(
        "kalendee.revokeCalendarFromTeam",
        RevokeCalendarFromTeamIn(calendarId = calendarId, teamId = teamId),
    )

    private suspend fun HttpClient.revokeCalendarError(
        calendarId: String,
        teamId: String,
    ): Map<String, List<String>> = actionErrors(
        "kalendee.revokeCalendarFromTeam",
        RevokeCalendarFromTeamIn(calendarId = calendarId, teamId = teamId),
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

    private suspend inline fun <reified In : Any> HttpClient.actionErrors(
        id: String,
        input: In,
    ): Map<String, List<String>> {
        val response = post("${Keel.ACTION_PATH}/$id") {
            contentType(ContentType.Application.Json)
            setBody(input)
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        val root = KeelJson.codec.parseToJsonElement(response.bodyAsText()).jsonObject
        val errors = root["errors"]?.jsonObject ?: error("no errors in ${response.bodyAsText()}")
        return errors.mapValues { (_, value) -> value.jsonArray.map { it.jsonPrimitive.content } }
    }
}
