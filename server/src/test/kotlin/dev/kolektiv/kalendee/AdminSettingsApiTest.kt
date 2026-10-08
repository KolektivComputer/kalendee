package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AdminSettingsOut
import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.auth.LoginUser
import dev.kolektiv.kalendee.auth.RegisterUser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminSettingsApiTest {
    @Test
    fun adminReadsAndUpdatesInstanceSettings() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val admin = jsonClient()
        admin.loginAsAdmin()

        val initial = admin.get("/api/v1/admin/settings")
        assertEquals(HttpStatusCode.OK, initial.status, initial.bodyAsText())
        val settings = initial.body<AdminSettingsOut>()
        assertTrue(settings.registrationOpen)
        assertFalse(settings.oauthRegistrationOpen)
        assertEquals("optional", settings.emailVerification)
        assertEquals("public", settings.publicAccess)

        val updated = admin.patchSettings(
            """{"registrationOpen":false,"oauthRegistrationOpen":true,""" +
                """"emailVerification":"soft","publicAccess":"signed_in"}""",
        )
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        val changed = updated.body<AdminSettingsOut>()
        assertFalse(changed.registrationOpen)
        assertTrue(changed.oauthRegistrationOpen)
        assertEquals("soft", changed.emailVerification)
        assertEquals("signed_in", changed.publicAccess)

        val reread = admin.get("/api/v1/admin/settings").body<AdminSettingsOut>()
        assertEquals(changed, reread)

        val closedRegistration = jsonClient().post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "guest", password = "password12"))
        }
        assertEquals(HttpStatusCode.Forbidden, closedRegistration.status)
    }

    @Test
    fun partialUpdatesTreatExplicitNullsAsNoChange() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val admin = jsonClient()
        admin.loginAsAdmin()

        val partial = admin.patchSettings("""{"emailVerification":"soft"}""")
        assertEquals(HttpStatusCode.OK, partial.status, partial.bodyAsText())
        val afterPartial = partial.body<AdminSettingsOut>()
        assertEquals("soft", afterPartial.emailVerification)
        assertTrue(afterPartial.registrationOpen)
        assertEquals("public", afterPartial.publicAccess)

        val explicitNulls = admin.patchSettings(
            """{"registrationOpen":null,"oauthRegistrationOpen":null,""" +
                """"emailVerification":null,"publicAccess":null}""",
        )
        assertEquals(HttpStatusCode.OK, explicitNulls.status, explicitNulls.bodyAsText())
        assertEquals(afterPartial, explicitNulls.body<AdminSettingsOut>())
    }

    @Test
    fun settingsRequireAdmin() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val anonymous = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/v1/admin/settings").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            anonymous.patchSettings("""{"registrationOpen":true}""").status,
        )

        val mey = jsonClient()
        mey.registerAndLogin("mey")
        assertEquals(HttpStatusCode.Forbidden, mey.get("/api/v1/admin/settings").status)
        val denied = mey.patchSettings("""{"registrationOpen":true}""")
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertEquals("forbidden", denied.body<ErrorBody>().error)
    }

    @Test
    fun invalidSettingsAreRejectedWithErrorBody() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val admin = jsonClient()
        admin.loginAsAdmin()

        val badPolicy = admin.patchSettings("""{"emailVerification":"bogus"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, badPolicy.status)
        val policyError = badPolicy.body<ErrorBody>()
        assertEquals("invalid", policyError.error)
        assertTrue(policyError.message.contains("optional, soft, or required"))

        val inherit = admin.patchSettings("""{"publicAccess":"inherit"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, inherit.status)
        assertEquals("public access must be public or signed_in", inherit.body<ErrorBody>().message)

        val badMode = admin.patchSettings("""{"publicAccess":"bogus"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, badMode.status)
        assertTrue(badMode.body<ErrorBody>().message.contains("access mode"))

        val unchanged = admin.get("/api/v1/admin/settings").body<AdminSettingsOut>()
        assertEquals("optional", unchanged.emailVerification)
        assertEquals("public", unchanged.publicAccess)
    }
}

private suspend fun HttpClient.loginAsAdmin() {
    val response = post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(LoginUser(username = "admin", password = "adminpass1012"))
    }
    check(response.status == HttpStatusCode.OK) { "admin login failed: ${response.status}" }
}

private suspend fun HttpClient.patchSettings(body: String): HttpResponse = patch("/api/v1/admin/settings") {
    contentType(ContentType.Application.Json)
    setBody(body)
}
