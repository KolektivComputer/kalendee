package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AdminCalendarOut
import dev.kolektiv.kalendee.api.AdminUserOut
import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.api.PublicLinkBody
import dev.kolektiv.kalendee.auth.LoginUser
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.InstantRange
import dev.kolektiv.kalendee.web.CalendarSharingOut
import dev.kolektiv.keel.Keel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.koin.ktor.ext.get

class AdminModerationApiTest {
    @Test
    fun adminUpdatesProfileEmailAndPassword() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val admin = jsonClient()
        admin.loginAsAdmin()
        val mey = jsonClient().registerAndLogin(username = "mey", password = "password12")
        jsonClient().registerWithEmail(username = "other", email = "other@example.com")

        val updated = admin.patch("/api/v1/admin/users/${mey.id.value}") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Mey Updated","email":"mey@example.com","password":"newpassword1"}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        val body = updated.body<AdminUserOut>()
        assertEquals("Mey Updated", body.displayName)
        assertEquals("mey@example.com", body.email)
        assertFalse(body.emailVerified)
        assertFalse(body.admin)

        assertEquals(
            HttpStatusCode.OK,
            jsonClient().post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginUser(username = "mey", password = "newpassword1"))
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginUser(username = "mey", password = "password12"))
            }.status,
        )

        val duplicate = admin.patch("/api/v1/admin/users/${mey.id.value}") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"other@example.com"}""")
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals("email taken", duplicate.body<ErrorBody>().message)

        val weakPassword = admin.patch("/api/v1/admin/users/${mey.id.value}") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"short"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, weakPassword.status)
    }

    @Test
    fun adminDeletesUserAndTheirData() = testApplication {
        lateinit var store: CalendarStore
        installApi(adminPassword = "adminpass1012") { store = get() }
        val admin = jsonClient()
        admin.loginAsAdmin()
        val meyClient = jsonClient()
        val mey = meyClient.registerAndLogin(username = "mey", password = "password12")
        val calendar = meyClient.createCalendar("Mey Work")
        meyClient.createEvent(
            calendar.id.value,
            "Standup",
            Instant.parse("2026-09-07T14:00:00Z"),
            Instant.parse("2026-09-07T14:30:00Z"),
        )
        val token = meyClient.enablePublicLink(calendar.id.value)

        val deleted = admin.delete("/api/v1/admin/users/${mey.id.value}")
        assertEquals(HttpStatusCode.NoContent, deleted.status)

        assertEquals(HttpStatusCode.Unauthorized, meyClient.get("/api/v1/auth/me").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginUser(username = "mey", password = "password12"))
            }.status,
        )
        assertEquals(HttpStatusCode.NotFound, jsonClient().get("/api/v1/public/calendars/$token").status)

        assertTrue(store.listCalendars(mey.id).isEmpty())
        assertTrue(
            store.listEvents(mey.id, InstantRange(Instant.DISTANT_PAST, Instant.DISTANT_FUTURE)).isEmpty(),
        )

        val users = admin.get("/api/v1/admin/users").body<List<AdminUserOut>>()
        assertTrue(users.none { it.id == mey.id.value })
    }

    @Test
    fun adminDeletesAnotherUsersCalendar() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val admin = jsonClient()
        admin.loginAsAdmin()
        val meyClient = jsonClient()
        meyClient.registerAndLogin(username = "mey", password = "password12")
        val calendar = meyClient.createCalendar("Mey Work")
        meyClient.createEvent(
            calendar.id.value,
            "Standup",
            Instant.parse("2026-09-07T14:00:00Z"),
            Instant.parse("2026-09-07T14:30:00Z"),
        )

        val moderation = admin.get("/api/v1/admin/calendars").body<List<AdminCalendarOut>>()
        val summary = moderation.first { it.id == calendar.id.value }
        assertEquals("Mey Work", summary.displayName)
        assertEquals("mey", summary.ownerUsername)
        assertEquals(1L, summary.eventCount)
        assertFalse(summary.publicLinkEnabled)

        assertEquals(
            HttpStatusCode.NoContent,
            admin.delete("/api/v1/admin/calendars/${calendar.id.value}").status,
        )
        assertEquals(HttpStatusCode.NotFound, meyClient.get("/api/v1/calendars/${calendar.id.value}").status)
        val after = admin.get("/api/v1/admin/calendars").body<List<AdminCalendarOut>>()
        assertTrue(after.none { it.id == calendar.id.value })
    }

    @Test
    fun adminForceDisablesPublicLink() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val admin = jsonClient()
        admin.loginAsAdmin()
        val meyClient = jsonClient()
        meyClient.registerAndLogin(username = "mey", password = "password12")
        val calendar = meyClient.createCalendar("Mey Work")
        val token = meyClient.enablePublicLink(calendar.id.value)
        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/public/calendars/$token").status)

        val disabled = admin.patch("/api/v1/admin/calendars/${calendar.id.value}") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, disabled.status)
        assertFalse(disabled.body<AdminCalendarOut>().publicLinkEnabled)
        assertEquals(HttpStatusCode.NotFound, jsonClient().get("/api/v1/public/calendars/$token").status)
        assertFalse(meyClient.get("/api/v1/calendars/${calendar.id.value}").body<Calendar>().publicLinkEnabled)
    }

    @Test
    fun nonAdminCannotUseAdminApi() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val meyClient = jsonClient()
        val mey = meyClient.registerAndLogin(username = "mey", password = "password12")

        assertEquals(HttpStatusCode.Forbidden, meyClient.get("/api/v1/admin/users").status)
        assertEquals(HttpStatusCode.Forbidden, meyClient.get("/api/v1/admin/groups").status)
        assertEquals(HttpStatusCode.Forbidden, meyClient.get("/api/v1/admin/calendars").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            meyClient.delete("/api/v1/admin/calendars/00000000-0000-0000-0000-000000000001").status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            meyClient.delete("/api/v1/admin/users/${mey.id.value}").status,
        )
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/admin/users").status)

        val action = meyClient.post("${Keel.ACTION_PATH}/kalendee.adminGroups") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, action.status)
    }
}

private suspend fun HttpClient.registerWithEmail(username: String, email: String) {
    val response = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterUser(username = username, password = "password12", email = email))
    }
    check(response.status == HttpStatusCode.Created) { "register failed: ${response.status}" }
}

private suspend fun HttpClient.createCalendar(name: String): Calendar {
    val response = post("/api/v1/calendars") {
        contentType(ContentType.Application.Json)
        setBody(CreateCalendar(displayName = name))
    }
    check(response.status == HttpStatusCode.Created) { "create calendar failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.createEvent(
    calendarId: String,
    title: String,
    start: Instant,
    end: Instant,
): Event {
    val response = post("/api/v1/calendars/$calendarId/events") {
        contentType(ContentType.Application.Json)
        setBody(CreateEvent(title = title, start = start, end = end))
    }
    check(response.status == HttpStatusCode.Created) { "create event failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.enablePublicLink(calendarId: String): String {
    val response = put("/api/v1/calendars/$calendarId/public") {
        contentType(ContentType.Application.Json)
        setBody(PublicLinkBody(enabled = true))
    }
    check(response.status == HttpStatusCode.OK) { "enable public link failed: ${response.status}" }
    return response.body<CalendarSharingOut>().publicLinkToken ?: error("no public token")
}

private suspend fun HttpClient.loginAsAdmin(password: String = "adminpass1012") {
    val result = login(username = "admin", password = password)
    check(result.user?.admin == true) { "admin login failed" }
}
