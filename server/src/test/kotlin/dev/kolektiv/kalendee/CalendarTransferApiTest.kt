package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.CreateOrganizationBody
import dev.kolektiv.kalendee.api.CreateTeamBody
import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.api.OrganizationSummaryOut
import dev.kolektiv.kalendee.api.TeamOut
import dev.kolektiv.kalendee.api.TransferCalendarBody
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.OrganizationId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CalendarTransferApiTest {
    @Test
    fun transfersBetweenPersonalOrganizationAndTeam() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val org = alice.createOrganization("acme", "Acme")
        val calendar = alice.createCalendar("Personal")

        val toOrg = alice.transfer(calendar.id.value, organizationId = org.id)
        assertEquals(HttpStatusCode.OK, toOrg.status, toOrg.bodyAsText())
        val moved = toOrg.body<Calendar>()
        assertEquals(calendar.id, moved.id)
        assertEquals(OrganizationId(org.id), moved.organizationId)

        val team = alice.createTeam(org.id, "design", "Design")
        val toTeam = alice.transfer(calendar.id.value, organizationId = org.id, teamId = team.id)
        assertEquals(HttpStatusCode.OK, toTeam.status, toTeam.bodyAsText())
        assertEquals(OrganizationId(org.id), toTeam.body<Calendar>().organizationId)

        val personal = alice.transfer(calendar.id.value)
        assertEquals(HttpStatusCode.OK, personal.status, personal.bodyAsText())
        assertNull(personal.body<Calendar>().organizationId)
    }

    @Test
    fun transferRequiresSessionAndOwnership() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val org = alice.createOrganization("acme", "Acme")
        val calendar = alice.createCalendar("Personal")

        val anonymous = jsonClient().transfer(calendar.id.value, organizationId = org.id)
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

        val bobAttempt = bob.transfer(calendar.id.value, organizationId = org.id)
        assertEquals(HttpStatusCode.Forbidden, bobAttempt.status)
        assertEquals("forbidden", bobAttempt.body<ErrorBody>().error)

        val unknown = alice.transfer(Uuid.random().toString(), organizationId = org.id)
        assertEquals(HttpStatusCode.NotFound, unknown.status)

        val badCalendar = alice.transfer("not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, badCalendar.status)

        val badOrg = alice.transfer(calendar.id.value, organizationId = "not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, badOrg.status)
        assertTrue(badOrg.body<ErrorBody>().message.contains("organization"))

        val badTeam = alice.transfer(calendar.id.value, organizationId = org.id, teamId = "not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, badTeam.status)
        assertTrue(badTeam.body<ErrorBody>().message.contains("team"))

        val unknownOrg = alice.transfer(calendar.id.value, organizationId = Uuid.random().toString())
        assertEquals(HttpStatusCode.NotFound, unknownOrg.status)
    }

    @Test
    fun transferringOrganizationCalendarRequiresManager() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val org = alice.createOrganization("acme", "Acme")
        val orgCalendar = alice.createCalendar("Team", organizationId = org.id)

        val denied = bob.transfer(orgCalendar.id.value)
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertTrue(denied.body<ErrorBody>().message.contains("cannot transfer"))
    }
}

private suspend fun HttpClient.createCalendar(name: String, organizationId: String? = null): Calendar {
    val response = post("/api/v1/calendars") {
        contentType(ContentType.Application.Json)
        setBody(
            CreateCalendar(
                displayName = name,
                organizationId = organizationId?.let(OrganizationId::parse),
            ),
        )
    }
    check(response.status == HttpStatusCode.Created) { "create calendar failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.createOrganization(slug: String, displayName: String): OrganizationSummaryOut {
    val response = post("/api/v1/organizations") {
        contentType(ContentType.Application.Json)
        setBody(CreateOrganizationBody(slug = slug, displayName = displayName))
    }
    check(response.status == HttpStatusCode.Created) { "create organization failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.createTeam(organizationId: String, slug: String, name: String): TeamOut {
    val response = post("/api/v1/organizations/$organizationId/teams") {
        contentType(ContentType.Application.Json)
        setBody(CreateTeamBody(slug = slug, name = name))
    }
    check(response.status == HttpStatusCode.Created) { "create team failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.transfer(
    id: String,
    organizationId: String? = null,
    teamId: String? = null,
): HttpResponse = put("/api/v1/calendars/$id/transfer") {
    contentType(ContentType.Application.Json)
    setBody(TransferCalendarBody(organizationId = organizationId, teamId = teamId))
}
