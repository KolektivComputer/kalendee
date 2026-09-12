package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.auth.LoginUser
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.keel.Keel
import dev.kolektiv.keel.KeelJson
import dev.kolektiv.keel.seed.KeelSeed
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
import dev.kolektiv.keel.visit.KeelHeaders

class AdminApiTest {
    @Test
    fun seededAdminCanOpenRegistrationAndSeeUsers() = testApplication {
        installApi(registration = "first-user", adminPassword = "adminpass1012")
        val client = jsonClient()
        val login = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginUser(username = "admin", password = "adminpass1012"))
        }
        assertEquals(HttpStatusCode.OK, login.status)
        assertTrue(login.bodyAsText().contains("\"admin\":true"))

        val closed = jsonClient().post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "guest", password = "password12"))
        }
        assertEquals(HttpStatusCode.Forbidden, closed.status)

        val opened = client.post("${Keel.ACTION_PATH}/kalendee.setRegistration") {
            contentType(ContentType.Application.Json)
            setBody("""{"open":true}""")
        }
        assertEquals(HttpStatusCode.OK, opened.status)
        assertTrue(opened.bodyAsText().contains("\"registrationOpen\":true"))

        val guest = jsonClient().post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "guest", password = "password12"))
        }
        assertEquals(HttpStatusCode.Created, guest.status)

        val adminPage = client.get("/admin") {
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, adminPage.status)
        val seed = KeelJson.codec.decodeFromString(KeelSeed.serializer(), adminPage.bodyAsText())
        assertEquals("kalendee.admin", seed.page)
        assertTrue(adminPage.bodyAsText().contains("guest"))
        assertTrue(adminPage.bodyAsText().contains("\"registrationOpen\":true"))
        assertTrue(adminPage.bodyAsText().contains("\"holidayCatalog\""))
        assertTrue(adminPage.bodyAsText().contains("\"subscribedHolidayIds\""))
    }

    @Test
    fun nonAdminCannotOpenRegistrationOrVisitAdmin() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val client = jsonClient()
        client.registerAndLogin(username = "mey", password = "password12")
        val denied = client.post("${Keel.ACTION_PATH}/kalendee.setRegistration") {
            contentType(ContentType.Application.Json)
            setBody("""{"open":false}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, denied.status)

        val adminPage = client.get("/admin") {
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, adminPage.status)
        val seed = KeelJson.codec.decodeFromString(KeelSeed.serializer(), adminPage.bodyAsText())
        assertEquals("/", seed.redirect)
    }

    @Test
    fun adminCanViewAnotherUsersCalendar() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val guestClient = jsonClient()
        val guest = guestClient.registerAndLogin(username = "guest", password = "password12")
        guestClient.post("${Keel.ACTION_PATH}/kalendee.createCalendar") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Guest Work","timeZone":"UTC"}""")
        }

        val admin = jsonClient()
        admin.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginUser(username = "admin", password = "adminpass1012"))
        }
        val home = admin.get("/") {
            url { parameters.append("as", guest.id.value) }
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, home.status)
        assertTrue(home.bodyAsText().contains("Guest Work"))
        assertTrue(home.bodyAsText().contains("\"readOnly\":true"))
        assertTrue(home.bodyAsText().contains("\"username\":\"guest\""))
    }
}
