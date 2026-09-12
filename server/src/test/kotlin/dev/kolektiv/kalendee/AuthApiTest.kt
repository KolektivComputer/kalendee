package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AuthResult
import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.auth.LoginUser
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.UpdateUser
import dev.kolektiv.kalendee.auth.User
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthApiTest {
    @Test
    fun registerLogsInAndMeReturnsUser() = testApplication {
        installApi()
        val client = jsonClient()
        val created = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "Mey", password = "password12"))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val user = created.body<AuthResult>().user ?: error("no user in register response")
        assertEquals("mey", user.username)
        assertEquals("mey", user.displayName)
        assertEquals("UTC", user.timeZone)
        val cookie = created.headers[HttpHeaders.SetCookie].orEmpty()
        assertTrue(cookie.startsWith("kalendee_session="))
        assertTrue("HttpOnly" in cookie)
        assertTrue("SameSite=Lax" in cookie)
        assertTrue("Secure" !in cookie)

        val me = client.get("/api/v1/auth/me")
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(user.id, me.body<User>().id)
    }

    @Test
    fun loginRejectsBadPasswordWithoutLeakingUser() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin(username = "mey", password = "password12")
        val other = jsonClient()
        val missing = other.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginUser(username = "nobody", password = "password12"))
        }
        val wrong = other.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginUser(username = "mey", password = "wrong-password"))
        }
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("invalid username or password", missing.body<ErrorBody>().message)
        assertEquals("invalid username or password", wrong.body<ErrorBody>().message)
    }

    @Test
    fun duplicateUsernameConflicts() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin(username = "mey")
        val duplicate = jsonClient().post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "mey", password = "password12"))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals("username taken", duplicate.body<ErrorBody>().message)
    }

    @Test
    fun logoutClearsSession() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/me").status)
        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/auth/logout").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me").status)
    }

    @Test
    fun patchMeUpdatesDisplayNameAndTimeZone() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin(username = "mey", password = "password12")
        val updated = client.patch("/api/v1/auth/me") {
            contentType(ContentType.Application.Json)
            setBody(UpdateUser(displayName = "Ada", timeZone = "Europe/Paris"))
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        val user = updated.body<User>()
        assertEquals("Ada", user.displayName)
        assertEquals("Europe/Paris", user.timeZone)
        assertEquals("primary", user.accent)
        assertEquals("mey", user.username)
        assertEquals("Ada", client.get("/api/v1/auth/me").body<User>().displayName)

        val accented = client.patch("/api/v1/auth/me") {
            contentType(ContentType.Application.Json)
            setBody(UpdateUser(accent = "info"))
        }
        assertEquals(HttpStatusCode.OK, accented.status)
        val accentedUser = accented.body<User>()
        assertEquals("info", accentedUser.accent)
        assertEquals("Ada", accentedUser.displayName)
    }

    @Test
    fun adminPasswordSeedsAdminUser() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val client = jsonClient()
        val login = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginUser(username = "admin", password = "adminpass1012"))
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val admin = login.body<AuthResult>().user ?: error("no user in login response")
        assertEquals("admin", admin.username)
        assertEquals(true, admin.admin)
    }

    @Test
    fun firstUserPolicyClosesRegistration() = testApplication {
        installApi(registration = "first-user")
        val client = jsonClient()
        client.registerAndLogin(username = "owner")
        val second = jsonClient().post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "guest", password = "password12"))
        }
        assertEquals(HttpStatusCode.Forbidden, second.status)
        assertEquals("registration is closed", second.body<ErrorBody>().message)
    }
}
