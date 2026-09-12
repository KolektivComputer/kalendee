package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.OrganizationId
import dev.kolektiv.kalendee.organizations.OrganizationRole
import dev.kolektiv.kalendee.organizations.OrganizationService
import dev.kolektiv.kalendee.organizations.OrganizationTeamService
import dev.kolektiv.kalendee.web.CalendarSummary
import dev.kolektiv.kalendee.web.CreateCalendarIn
import dev.kolektiv.kalendee.web.CreateOrganizationIn
import dev.kolektiv.kalendee.web.CreateOrganizationTeamIn
import dev.kolektiv.kalendee.web.HomePage
import dev.kolektiv.kalendee.web.OrganizationSummary
import dev.kolektiv.kalendee.web.OrganizationTeamSummary
import dev.kolektiv.kalendee.web.TransferCalendarIn
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
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.koin.ktor.ext.get

class TransferCalendarActionTest {
    @Test
    fun transferActionMovesCalendarAndHomeReflectsDestination() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        lateinit var store: CalendarStore
        installApi(configure = { orgs = get(); teams = get(); store = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")

        val org = alice.createOrganization("acme", "Acme")
        val calendar = alice.createCalendar("Personal")

        val movedToOrg = alice.transfer(calendar.id, organizationId = org.id)
        assertEquals(calendar.id, movedToOrg.id)
        assertEquals(org.id, movedToOrg.organizationId)
        assertEquals("owner", movedToOrg.permission)

        val design = alice.createTeam(org.id, "design", "Design")
        val movedToTeam = alice.transfer(calendar.id, organizationId = org.id, teamId = design.id)
        assertEquals(org.id, movedToTeam.organizationId)

        val home = alice.visit("/").decodePage(HomePage.serializer())
        val homeCalendar = home.calendars.single { it.id == calendar.id }
        assertEquals(org.id, homeCalendar.organizationId)
        assertEquals("Acme", homeCalendar.organizationName)
        assertEquals(design.id, homeCalendar.teamId)
        assertEquals("Design", homeCalendar.teamName)

        val stored = store.getCalendar(CalendarId.parse(calendar.id), aliceUser.id)
        assertEquals(OrganizationId(org.id), stored?.organizationId)
        assertEquals(aliceUser.id, stored?.ownerId)
    }

    @Test
    fun transferActionErrorsMapToFields() = testApplication {
        lateinit var orgs: OrganizationService
        lateinit var teams: OrganizationTeamService
        installApi(configure = { orgs = get(); teams = get() })
        startApplication()
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")
        val carol = jsonClient()
        carol.registerAndLogin("carol")

        val org = alice.createOrganization("acme", "Acme")
        orgs.addMember(aliceUser.id, OrganizationId(org.id), bobUser.id, OrganizationRole.MEMBER)
        val calendar = alice.createCalendar("Personal")

        assertTrue(alice.transferError("not-a-uuid", organizationId = org.id).containsKey("calendarId"))
        assertTrue(alice.transferError(calendar.id, organizationId = "not-a-uuid").containsKey("organizationId"))
        assertTrue(alice.transferError(calendar.id, teamId = "not-a-uuid").containsKey("teamId"))
        assertTrue(
            alice.transferError(calendar.id, organizationId = org.id, teamId = Uuid.random().toString())
                .containsKey("teamId"),
        )

        val orgCalendar = alice.createCalendar("Team", organizationId = org.id)
        assertTrue(bob.transferError(orgCalendar.id).containsKey("id"))

        val carolOrg = carol.createOrganization("other", "Other")
        assertTrue(alice.transferError(calendar.id, organizationId = carolOrg.id).containsKey("organizationId"))

        val design = teams.create(aliceUser.id, OrganizationId(org.id), "design", "Design")
        val bobCalendar = bob.createCalendar("Bob Personal")
        assertTrue(
            bob.transferError(bobCalendar.id, organizationId = org.id, teamId = design.id.value)
                .containsKey("teamId"),
        )
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

    private suspend fun HttpClient.createTeam(
        organizationId: String,
        slug: String,
        name: String,
    ): OrganizationTeamSummary = action(
        "kalendee.createOrganizationTeam",
        CreateOrganizationTeamIn(organizationId = organizationId, slug = slug, name = name),
    )

    private suspend fun HttpClient.transfer(
        id: String,
        organizationId: String? = null,
        teamId: String? = null,
    ): CalendarSummary = action(
        "kalendee.transferCalendar",
        TransferCalendarIn(id = id, organizationId = organizationId, teamId = teamId),
    )

    private suspend fun HttpClient.transferError(
        id: String,
        organizationId: String? = null,
        teamId: String? = null,
    ): Map<String, List<String>> = actionErrors(
        "kalendee.transferCalendar",
        TransferCalendarIn(id = id, organizationId = organizationId, teamId = teamId),
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
