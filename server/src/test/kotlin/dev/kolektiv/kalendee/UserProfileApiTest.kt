package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AuthResult
import dev.kolektiv.kalendee.api.AvatarOut
import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.EmailVerificationPolicy
import dev.kolektiv.kalendee.auth.LoginUser
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.keel.Keel
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.get

class UserProfileApiTest {
    @Test
    fun registerWithEmailReturnsNormalizedEmail() = testApplication {
        installApi()
        val client = jsonClient()
        val created = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "Mey", password = "password12", email = "  Mey@Example.COM  "))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val user = created.body<AuthResult>().user ?: error("no user in register response")
        assertEquals("mey@example.com", user.email)
        assertEquals(false, user.emailVerified)

        val me = client.get("/api/v1/auth/me")
        assertEquals(HttpStatusCode.OK, me.status)
        val sessionUser = me.body<User>()
        assertEquals("mey@example.com", sessionUser.email)
        assertEquals(false, sessionUser.emailVerified)
        assertEquals(null, sessionUser.avatarVersion)
    }

    @Test
    fun duplicateEmailConflictsCaseInsensitively() = testApplication {
        installApi()
        val first = jsonClient().post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "mey", password = "password12", email = "Mey@Example.com"))
        }
        assertEquals(HttpStatusCode.Created, first.status)

        val duplicate = jsonClient().post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "other", password = "password12", email = "mey@example.com"))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals("email taken", duplicate.body<ErrorBody>().message)
    }

    @Test
    fun requiredPolicyNeedsEmailAtRegistration() = testApplication {
        installApi()
        application {
            runBlocking { get<AuthService>().setEmailVerificationPolicy(EmailVerificationPolicy.Required) }
        }
        val client = jsonClient()
        val missing = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "noemail", password = "password12"))
        }
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertEquals("email is required", missing.body<ErrorBody>().message)

        val withEmail = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "hasemail", password = "password12", email = "has@example.com"))
        }
        assertEquals(HttpStatusCode.Created, withEmail.status)
        assertEquals("has@example.com", withEmail.body<AuthResult>().email)
    }

    @Test
    fun avatarUploadFetchAndDelete() = testApplication {
        installApi()
        val client = jsonClient()
        val user = client.registerAndLogin(username = "mey", password = "password12")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03)

        val upload = client.post("/api/v1/auth/me/avatar") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "image/png")
                                append(HttpHeaders.ContentDisposition, "filename=\"avatar.png\"")
                            },
                        )
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, upload.status)
        val avatar = upload.body<AvatarOut>()
        assertTrue(avatar.version > 0)
        assertEquals("/api/v1/users/${user.id.value}/avatar?v=${avatar.version}", avatar.avatarUrl)

        val fetched = jsonClient().get(avatar.avatarUrl)
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertEquals("image/png", fetched.contentType()?.withoutParameters()?.toString())
        assertNotNull(fetched.headers[HttpHeaders.ETag])
        assertEquals("public, max-age=86400", fetched.headers[HttpHeaders.CacheControl])
        assertContentEquals(bytes, fetched.body<ByteArray>())

        val notModified = jsonClient().get(avatar.avatarUrl) {
            headers.append(HttpHeaders.IfNoneMatch, "\"v${avatar.version}\"")
        }
        assertEquals(HttpStatusCode.NotModified, notModified.status)

        val deleted = client.delete("/api/v1/auth/me/avatar")
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals(HttpStatusCode.NotFound, jsonClient().get(avatar.avatarUrl).status)
    }

    @Test
    fun adminPageExposesEmailVerificationPolicy() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val client = jsonClient()
        client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginUser(username = "admin", password = "adminpass1012"))
        }
        val page = client.get("/admin") {
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("\"emailVerificationPolicy\":\"optional\""))

        val updated = client.post("${Keel.ACTION_PATH}/kalendee.setEmailVerification") {
            contentType(ContentType.Application.Json)
            setBody("""{"policy":"soft"}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        assertTrue(updated.bodyAsText().contains("\"policy\":\"soft\""))
        val refreshed = client.get("/admin") {
            header(KeelHeaders.VISIT, "true")
        }
        assertTrue(refreshed.bodyAsText().contains("\"emailVerificationPolicy\":\"soft\""))

        val invalid = client.post("${Keel.ACTION_PATH}/kalendee.setEmailVerification") {
            contentType(ContentType.Application.Json)
            setBody("""{"policy":"bogus"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        assertTrue(invalid.bodyAsText().contains("policy"))
    }
}
