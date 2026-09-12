package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AdminGroupOut
import dev.kolektiv.kalendee.api.AdminUserOut
import dev.kolektiv.kalendee.api.AvatarOut
import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlin.test.assertTrue

class AdminQuotaApiTest {
    @Test
    fun avatarUploadsRespectDefaultGroupQuota() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val admin = jsonClient()
        admin.loginAsAdmin()
        val meyClient = jsonClient()
        val mey = meyClient.registerAndLogin(username = "mey", password = "password12")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03)

        val first = meyClient.uploadAvatar(bytes)
        assertEquals(HttpStatusCode.OK, first.status)
        val avatar = first.body<AvatarOut>()

        val defaultGroup = admin.get("/api/v1/admin/groups")
            .body<List<AdminGroupOut>>()
            .first { it.name == "default" }
        admin.patch("/api/v1/admin/groups/${defaultGroup.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"storageQuotaBytes":10}""")
        }

        val blocked = meyClient.uploadAvatar(bytes)
        assertEquals(HttpStatusCode.Forbidden, blocked.status)
        assertEquals("storage quota exceeded", blocked.body<ErrorBody>().message)

        val fetched = jsonClient().get(avatar.avatarUrl)
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertContentEquals(bytes, fetched.body<ByteArray>())

        val adminUpload = admin.uploadAvatar(bytes)
        assertEquals(HttpStatusCode.OK, adminUpload.status)

        val users = admin.get("/api/v1/admin/users").body<List<AdminUserOut>>()
        val meyUser = users.first { it.id == mey.id.value }
        assertEquals(bytes.size.toLong(), meyUser.storageBytes)
        assertEquals(10L, meyUser.quotaBytes)
        assertEquals(null, users.first { it.username == "admin" }.quotaBytes)

        val page = admin.get("/admin") {
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("\"storageBytes\":${bytes.size}"))
        assertTrue(page.bodyAsText().contains("\"quotaBytes\":10"))
        assertTrue(page.bodyAsText().contains("\"isSystem\":true"))
    }
}

private suspend fun HttpClient.loginAsAdmin(password: String = "adminpass1012") {
    val result = login(username = "admin", password = password)
    check(result.user?.admin == true) { "admin login failed" }
}

private suspend fun HttpClient.uploadAvatar(bytes: ByteArray): HttpResponse = post("/api/v1/auth/me/avatar") {
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
