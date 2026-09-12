package dev.kolektiv.kalendee

import dev.kolektiv.keel.Keel
import dev.kolektiv.keel.KeelJson
import dev.kolektiv.keel.seed.KeelSeed
import dev.kolektiv.keel.typegen.ClasspathContract
import dev.kolektiv.keel.typegen.Typegen
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeelAppTest {
    @Test
    fun homeDocumentCarriesSeedAndSeoTags() = testApplication {
        installApi()
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("id=\"__keel_seed\""))
        assertTrue(body.contains("\"page\":\"kalendee.home\""))
        assertTrue(body.contains("Kalendee"))
        assertTrue(body.contains("name=\"description\""))
        assertTrue(body.contains("/__keel/pack/kalendee/pages/kalendee.home.js"))
        assertTrue(body.contains("/__keel/pack/kalendee/bootstrap.js"))
    }

    @Test
    fun schemaExposesLiveContract() = testApplication {
        installApi()
        val response = client.get(Keel.SCHEMA_PATH)
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("keel/1"))
        assertTrue(body.contains("kalendee.home"))
        assertTrue(body.contains("kalendee.login"))
        assertTrue(body.contains("kalendee.register"))
        assertTrue(body.contains("kalendee.admin"))
        assertTrue(body.contains("kalendee.settings"))
        assertTrue(body.contains("kalendee.notFound"))
        assertTrue(body.contains("kalendee.profile"))
        assertTrue(body.contains("kalendee.org"))
        assertTrue(body.contains("kalendee.orgSettings"))
        assertTrue(body.contains("kalendee.directory"))
        assertTrue(body.contains("kalendee.login"))
        assertTrue(body.contains("\"kalendee.logout\""))
        assertTrue(body.contains("\"kalendee.createCalendar\""))
        assertTrue(body.contains("\"kalendee.updateCalendar\""))
        assertTrue(body.contains("\"kalendee.deleteCalendar\""))
        assertTrue(body.contains("\"kalendee.createEvent\""))
        assertTrue(body.contains("\"kalendee.updateEvent\""))
        assertTrue(body.contains("\"kalendee.deleteEvent\""))
        assertTrue(body.contains("\"kalendee.moveEvent\""))
        assertTrue(body.contains("\"kalendee.updateSettings\""))
        assertTrue(body.contains("\"kalendee.setCalendarHidden\""))
        assertTrue(body.contains("\"kalendee.setShowHolidays\""))
        assertTrue(body.contains("\"kalendee.updateHolidaySubscriptions\""))
        assertTrue(body.contains("\"kalendee.createCustomHoliday\""))
        assertTrue(body.contains("\"kalendee.deleteCustomHoliday\""))
        assertTrue(body.contains("\"kalendee.setRegistration\""))
        assertTrue(body.contains("\"kalendee.organizations\""))
        assertTrue(body.contains("\"kalendee.createOrganization\""))
        assertTrue(body.contains("\"kalendee.updateOrganization\""))
        assertTrue(body.contains("\"kalendee.deleteOrganization\""))
        assertTrue(body.contains("\"kalendee.organizationMembers\""))
        assertTrue(body.contains("\"kalendee.organizationInvitations\""))
        assertTrue(body.contains("\"kalendee.inviteToOrganization\""))
        assertTrue(body.contains("\"kalendee.revokeOrganizationInvitation\""))
        assertTrue(body.contains("\"kalendee.setOrganizationMemberRole\""))
        assertTrue(body.contains("\"kalendee.removeOrganizationMember\""))
        assertTrue(body.contains("\"kalendee.acceptOrganizationInvitation\""))
        assertTrue(body.contains("\"kalendee.declineOrganizationInvitation\""))
        assertTrue(body.contains("\"kalendee.organizationTeams\""))
        assertTrue(body.contains("\"kalendee.createOrganizationTeam\""))
        assertTrue(body.contains("\"kalendee.updateOrganizationTeam\""))
        assertTrue(body.contains("\"kalendee.deleteOrganizationTeam\""))
        assertTrue(body.contains("\"kalendee.addOrganizationTeamMember\""))
        assertTrue(body.contains("\"kalendee.removeOrganizationTeamMember\""))
        assertTrue(body.contains("\"kalendee.setOrganizationTeamMemberRole\""))
        assertTrue(body.contains("\"kalendee.grantCalendarToTeam\""))
        assertTrue(body.contains("\"kalendee.revokeCalendarFromTeam\""))
        assertTrue(body.contains("OrganizationTeamSummary"))
        assertTrue(body.contains("OrganizationTeamMemberSummary"))
        assertTrue(body.contains("OrganizationTeamCalendarSummary"))
        assertTrue(body.contains("CalendarOptionSummary"))
        assertTrue(body.contains("TeamSummary"))
    }

    @Test
    fun loginActionSetsCookieAndHomeVisitSeesViewer() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin(username = "mey", password = "password12")
        client.post("/api/v1/auth/logout")

        val denied = client.post("${Keel.ACTION_PATH}/kalendee.login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"mey","password":"wrong-password"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, denied.status)
        assertTrue(denied.bodyAsText().contains("\"errors\""))
        assertTrue(!denied.bodyAsText().contains("\"page\""))

        val login = client.post("${Keel.ACTION_PATH}/kalendee.login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"mey","password":"password12"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        assertTrue(login.bodyAsText().contains("mey"))

        val home = client.get("/") {
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, home.status)
        val seed = KeelJson.codec.decodeFromString(KeelSeed.serializer(), home.bodyAsText())
        assertEquals("kalendee.home", seed.page)
        assertTrue(seed.head?.title?.contains("Kalendee") == true)
        assertTrue(home.bodyAsText().contains("mey"))
    }

    @Test
    fun registerActionCreatesSession() = testApplication {
        installApi(registration = "first-user")
        val client = jsonClient()
        val created = client.post("${Keel.ACTION_PATH}/kalendee.register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"ada","password":"password12"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)
        assertTrue(created.bodyAsText().contains("ada"))

        val home = client.get("/") {
            header(KeelHeaders.VISIT, "true")
        }
        assertTrue(home.bodyAsText().contains("ada"))
        assertTrue(home.bodyAsText().contains("\"registrationOpen\":false"))
    }

    @Test
    fun createCalendarActionAddsToHome() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin(username = "mey", password = "password12")

        val created = client.post("${Keel.ACTION_PATH}/kalendee.createCalendar") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Work","timeZone":"UTC"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)
        assertTrue(created.bodyAsText().contains("Work"))

        val home = client.get("/") {
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, home.status)
        assertTrue(home.bodyAsText().contains("Work"))
    }

    @Test
    fun createAndMoveEventActionsShowOnHomeWeek() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin(username = "mey", password = "password12")
        val createdCalendar = client.post("${Keel.ACTION_PATH}/kalendee.createCalendar") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Work","timeZone":"UTC"}""")
        }
        assertEquals(HttpStatusCode.OK, createdCalendar.status)
        val calendarId = Regex("\"id\":\"([0-9a-f-]+)\"").find(createdCalendar.bodyAsText())?.groupValues?.get(1)
            ?: error("missing calendar id")

        val created = client.post("${Keel.ACTION_PATH}/kalendee.createEvent") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"calendarId":"$calendarId","title":"Standup","start":"2026-09-07T14:00:00Z","end":"2026-09-07T14:30:00Z","allDay":false}""",
            )
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val eventId = Regex("\"id\":\"([0-9a-f-]+)\"").find(created.bodyAsText())?.groupValues?.get(1)
            ?: error("missing event id")
        val etag = Regex("\"etag\":\"([^\"]+)\"").find(created.bodyAsText())?.groupValues?.get(1)
            ?: error("missing etag")

        val home = client.get("/") {
            url {
                parameters.append("week", "2026-09-07")
                parameters.append("tz", "UTC")
            }
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, home.status)
        assertTrue(home.bodyAsText().contains("Standup"))
        assertTrue(home.bodyAsText().contains("2026-09-07T14:00:00Z"))

        val moved = client.post("${Keel.ACTION_PATH}/kalendee.updateEvent") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"id":"$eventId","start":"2026-09-08T15:00:00Z","end":"2026-09-08T15:30:00Z","etag":"$etag"}""",
            )
        }
        assertEquals(HttpStatusCode.OK, moved.status)
        val movedHome = client.get("/") {
            url {
                parameters.append("week", "2026-09-07")
                parameters.append("tz", "UTC")
            }
            header(KeelHeaders.VISIT, "true")
        }
        assertTrue(movedHome.bodyAsText().contains("2026-09-08T15:00:00Z"))
        assertTrue(!movedHome.bodyAsText().contains("2026-09-07T14:00:00Z"))
    }

    @Test
    fun homeVisitHonorsDayAndMonthViews() = testApplication {
        installApi()
        val day = client.get("/") {
            url {
                parameters.append("view", "day")
                parameters.append("date", "2026-09-11")
                parameters.append("tz", "UTC")
            }
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, day.status)
        assertTrue(day.bodyAsText().contains("\"view\":\"day\""))
        assertTrue(day.bodyAsText().contains("\"date\":\"2026-09-11\""))
        assertTrue(day.bodyAsText().contains("\"gridStart\":\"2026-09-11\""))
        assertTrue(day.bodyAsText().contains("\"gridEnd\":\"2026-09-12\""))
        assertTrue(day.bodyAsText().contains("\"label\":\"Sep 11\""))

        val month = client.get("/") {
            url {
                parameters.append("view", "month")
                parameters.append("date", "2026-09-11")
                parameters.append("tz", "UTC")
            }
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, month.status)
        assertTrue(month.bodyAsText().contains("\"view\":\"month\""))
        assertTrue(month.bodyAsText().contains("\"date\":\"2026-09-01\""))
        assertTrue(month.bodyAsText().contains("\"gridStart\":\"2026-08-31\""))
        assertTrue(month.bodyAsText().contains("\"label\":\"September 2026\""))
    }

    @Test
    fun loggedInHomeKeepsGridWindowWithoutCalendars() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin(username = "mey", password = "password12")
        val home = client.get("/") {
            url {
                parameters.append("view", "week")
                parameters.append("date", "2026-09-07")
                parameters.append("tz", "UTC")
            }
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, home.status)
        assertTrue(home.bodyAsText().contains("\"calendars\":[]"))
        assertTrue(home.bodyAsText().contains("\"events\":[]"))
        assertTrue(home.bodyAsText().contains("\"view\":\"week\""))
        assertTrue(home.bodyAsText().contains("\"gridStart\":\"2026-09-07\""))
        assertTrue(home.bodyAsText().contains("\"gridEnd\":\"2026-09-14\""))
    }

    @Test
    fun homeVisitHonorsClientTimeZoneQuery() = testApplication {
        installApi()
        val home = client.get("/") {
            url {
                parameters.append("tz", "America/New_York")
            }
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, home.status)
        assertTrue(home.bodyAsText().contains("\"timeZone\":\"America/New_York\""))
        assertTrue(home.bodyAsText().contains("\"weekStart\""))
    }

    @Test
    fun settingsRequiresLogin() = testApplication {
        installApi()
        val response = client.get("/settings") {
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val seed = KeelJson.codec.decodeFromString(KeelSeed.serializer(), response.bodyAsText())
        assertEquals("/login", seed.redirect)
    }

    @Test
    fun loggedInUserCanVisitSettings() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin(username = "mey", password = "password12")
        val response = client.get("/settings") {
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val seed = KeelJson.codec.decodeFromString(KeelSeed.serializer(), response.bodyAsText())
        assertEquals("kalendee.settings", seed.page)
        assertTrue(response.bodyAsText().contains("\"tab\":\"account\""))
        assertTrue(response.bodyAsText().contains("\"holidayCatalog\""))
        assertTrue(response.bodyAsText().contains("\"subscribedHolidayIds\""))
        assertTrue(seed.head?.title?.contains("Settings") == true)
    }

    @Test
    fun settingsVisitHonorsTabQuery() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin(username = "mey", password = "password12")
        val holidays = client.get("/settings") {
            url { parameters.append("tab", "holidays") }
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, holidays.status)
        assertTrue(holidays.bodyAsText().contains("\"tab\":\"holidays\""))

        val unknown = client.get("/settings") {
            url { parameters.append("tab", "nope") }
            header(KeelHeaders.VISIT, "true")
        }
        assertTrue(unknown.bodyAsText().contains("\"tab\":\"account\""))
    }

    @Test
    fun unknownPathUsesNotFound() = testApplication {
        installApi()
        val missing = client.get("/nope")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertTrue(missing.bodyAsText().contains("kalendee.notFound"))
        assertTrue(missing.bodyAsText().contains("\"path\":\"/nope\""))
    }

    @Test
    fun generatedPageTypesStayInLockstep() {
        val expected = Path.of("pack/src/lib/page-types.ts").readText()
        val actual = Typegen.emit(
            ClasspathContract.scan(listOf("dev.kolektiv.kalendee.web")),
            pagesName = "KalendeePages",
        )
        assertEquals(expected, actual)
    }
}
