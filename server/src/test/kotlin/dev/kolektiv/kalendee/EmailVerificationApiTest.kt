package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AuthResult
import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.api.OkResponse
import dev.kolektiv.kalendee.api.ResendVerificationBody
import dev.kolektiv.kalendee.api.VerifyEmailBody
import dev.kolektiv.kalendee.api.VerifyEmailResponse
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.EmailVerificationPolicy
import dev.kolektiv.kalendee.auth.LoginUser
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.UpdateUser
import dev.kolektiv.kalendee.auth.User
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.get

class EmailVerificationApiTest {
    @Test
    fun requiredPolicyGatesLoginUntilVerifiedAndTokensAreSingleUse() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        application {
            runBlocking { get<AuthService>().setEmailVerificationPolicy(EmailVerificationPolicy.Required) }
        }
        val client = jsonClient()

        val created = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "mey", password = "password12", email = "Mey@Example.COM"))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val registered = created.body<AuthResult>()
        assertNull(registered.user)
        assertTrue(registered.verificationRequired)
        assertEquals("mey@example.com", registered.email)
        assertNull(created.headers[HttpHeaders.SetCookie])
        assertEquals(1, mail.sent.size)
        assertEquals("mey@example.com", mail.sent.single().to)

        val denied = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginUser(username = "mey", password = "password12"))
        }
        assertEquals(HttpStatusCode.OK, denied.status)
        val deniedBody = denied.body<AuthResult>()
        assertNull(deniedBody.user)
        assertTrue(deniedBody.verificationRequired)
        assertEquals("mey@example.com", deniedBody.email)
        assertNull(denied.headers[HttpHeaders.SetCookie])

        val token = mail.lastVerificationToken()
        assertTrue(mail.sent.single().text.contains("https://kalendee.test/verify-email?token=$token"))
        val verified = client.post("/api/v1/auth/verify-email") {
            contentType(ContentType.Application.Json)
            setBody(VerifyEmailBody(token))
        }
        assertEquals(HttpStatusCode.OK, verified.status)
        val verifiedBody = verified.body<VerifyEmailResponse>()
        assertTrue(verifiedBody.ok)
        assertEquals("mey@example.com", verifiedBody.email)

        val login = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginUser(username = "mey", password = "password12"))
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val sessionUser = login.body<AuthResult>().user ?: error("no session after verification")
        assertTrue(sessionUser.emailVerified)
        assertTrue(login.headers[HttpHeaders.SetCookie].orEmpty().startsWith("kalendee_session="))

        val reused = client.post("/api/v1/auth/verify-email") {
            contentType(ContentType.Application.Json)
            setBody(VerifyEmailBody(token))
        }
        val reusedBody = reused.body<VerifyEmailResponse>()
        assertFalse(reusedBody.ok)
        assertNull(reusedBody.email)
    }

    @Test
    fun requiredPolicyExemptsUsersWithoutEmail() = testApplication {
        installApi(adminPassword = "adminpass1012")
        application {
            runBlocking { get<AuthService>().setEmailVerificationPolicy(EmailVerificationPolicy.Required) }
        }
        val client = jsonClient()
        val login = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginUser(username = "admin", password = "adminpass1012"))
        }
        assertEquals(HttpStatusCode.OK, login.status)
        assertNotNull(login.body<AuthResult>().user)
        assertNotNull(login.headers[HttpHeaders.SetCookie])
    }

    @Test
    fun softPolicyAllowsSessionBeforeVerification() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        application {
            runBlocking { get<AuthService>().setEmailVerificationPolicy(EmailVerificationPolicy.Soft) }
        }
        val client = jsonClient()
        val created = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "mey", password = "password12", email = "mey@example.com"))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val registered = created.body<AuthResult>()
        val user = registered.user ?: error("soft policy should issue a session")
        assertFalse(user.emailVerified)
        assertTrue(registered.verificationRequired)
        assertEquals("mey@example.com", registered.email)
        assertNotNull(created.headers[HttpHeaders.SetCookie])

        val token = mail.lastVerificationToken()
        val verified = client.post("/api/v1/auth/verify-email") {
            contentType(ContentType.Application.Json)
            setBody(VerifyEmailBody(token))
        }
        assertTrue(verified.body<VerifyEmailResponse>().ok)
        val me = client.get("/api/v1/auth/me")
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.body<User>().emailVerified)
    }

    @Test
    fun optionalEmailChangeResetsVerificationAndRejectsDuplicates() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        val client = jsonClient()
        client.registerAndLogin(username = "mey", password = "password12")

        val changed = client.patch("/api/v1/auth/me") {
            contentType(ContentType.Application.Json)
            setBody(UpdateUser(email = "New@Example.com"))
        }
        assertEquals(HttpStatusCode.OK, changed.status)
        val updated = changed.body<User>()
        assertEquals("new@example.com", updated.email)
        assertFalse(updated.emailVerified)
        assertEquals(1, mail.sent.size)
        assertEquals("new@example.com", mail.sent.single().to)

        val token = mail.lastVerificationToken()
        val verified = client.post("/api/v1/auth/verify-email") {
            contentType(ContentType.Application.Json)
            setBody(VerifyEmailBody(token))
        }
        assertTrue(verified.body<VerifyEmailResponse>().ok)

        val other = jsonClient()
        other.registerAndLogin(username = "other")
        val duplicate = other.patch("/api/v1/auth/me") {
            contentType(ContentType.Application.Json)
            setBody(UpdateUser(email = "new@example.com"))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals("email taken", duplicate.body<ErrorBody>().message)
    }

    @Test
    fun resendIsGenericAndThrottled() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        val client = jsonClient()
        client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "mey", password = "password12", email = "mey@example.com"))
        }
        assertEquals(1, mail.sent.size)

        val unknown = client.post("/api/v1/auth/resend-verification") {
            contentType(ContentType.Application.Json)
            setBody(ResendVerificationBody(username = "nobody"))
        }
        assertEquals(HttpStatusCode.OK, unknown.status)
        assertTrue(unknown.body<OkResponse>().ok)
        assertEquals(1, mail.sent.size)

        val resent = client.post("/api/v1/auth/resend-verification") {
            contentType(ContentType.Application.Json)
            setBody(ResendVerificationBody(username = "MEY"))
        }
        assertEquals(HttpStatusCode.OK, resent.status)
        assertEquals(2, mail.sent.size)

        val throttled = client.post("/api/v1/auth/resend-verification") {
            contentType(ContentType.Application.Json)
            setBody(ResendVerificationBody(username = "mey"))
        }
        assertEquals(HttpStatusCode.OK, throttled.status)
        assertTrue(throttled.body<OkResponse>().ok)
        assertEquals(2, mail.sent.size)
    }

    @Test
    fun newDeviceLoginSendsAlertOnlyOnce() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        val client = jsonClient()
        val created = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "mey", password = "password12", email = "mey@example.com"))
        }
        created.body<AuthResult>().user ?: error("expected a session")
        val token = mail.lastVerificationToken()
        val verified = client.post("/api/v1/auth/verify-email") {
            contentType(ContentType.Application.Json)
            setBody(VerifyEmailBody(token))
        }
        assertTrue(verified.body<VerifyEmailResponse>().ok)
        mail.clear()

        val first = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, "kalendee-test-agent")
            setBody(LoginUser(username = "mey", password = "password12"))
        }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(1, mail.sent.count { "New sign-in" in it.subject })
        assertEquals("mey@example.com", mail.sent.first { "New sign-in" in it.subject }.to)

        val second = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, "kalendee-test-agent")
            setBody(LoginUser(username = "mey", password = "password12"))
        }
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(1, mail.sent.count { "New sign-in" in it.subject })
    }
}
