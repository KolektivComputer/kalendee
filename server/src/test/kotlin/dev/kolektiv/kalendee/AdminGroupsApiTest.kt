package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AdminGroupMembersResponse
import dev.kolektiv.kalendee.api.AdminGroupOut
import dev.kolektiv.kalendee.api.AdminUserOut
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.groups.GroupService
import dev.kolektiv.keel.Keel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.get

class AdminGroupsApiTest {
    @Test
    fun systemGroupsAreSeededOnceAndIdempotent() = testApplication {
        installApi(adminPassword = "adminpass1012")
        application {
            runBlocking {
                val groups = get<GroupService>()
                groups.seedSystemGroups()
                groups.seedSystemGroups()
            }
        }
        val admin = jsonClient()
        admin.loginAsAdmin()
        val response = admin.get("/api/v1/admin/groups")
        assertEquals(HttpStatusCode.OK, response.status)
        val groups = response.body<List<AdminGroupOut>>()
        assertEquals(2, groups.size)
        assertEquals(listOf("admin", "default"), groups.map { it.name })
        assertTrue(groups.all { it.isSystem })
        assertEquals(1L, groups.first { it.name == "admin" }.memberCount)
        assertEquals(1L, groups.first { it.name == "default" }.memberCount)
    }

    @Test
    fun configuredSuperadminIsPromotedWhenSeeding() = testApplication {
        installApi(adminPassword = "adminpass1012", superadminUsername = "admin")
        val admin = jsonClient()
        admin.loginAsAdmin()
        val seeded = admin.get("/api/v1/admin/users")
            .body<List<AdminUserOut>>()
            .first { it.username == "admin" }
        assertTrue(seeded.admin)
        assertTrue(seeded.superadmin)
    }

    @Test
    fun adminManagesCustomGroupsAndMembers() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val admin = jsonClient()
        admin.loginAsAdmin()
        val meyClient = jsonClient()
        val mey = meyClient.registerAndLogin(username = "mey", password = "password12")

        val created = admin.post("/api/v1/admin/groups") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Team","storageQuotaBytes":2048}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val group = created.body<AdminGroupOut>()
        assertEquals("Team", group.name)
        assertFalse(group.isSystem)
        assertEquals(2048L, group.storageQuotaBytes)
        assertEquals(0L, group.memberCount)

        val members = admin.put("/api/v1/admin/groups/${group.id}/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":["${mey.id.value}"]}""")
        }
        assertEquals(HttpStatusCode.OK, members.status)
        assertEquals(
            listOf(mey.id.value),
            members.body<AdminGroupMembersResponse>().members.map { it.userId },
        )

        val listed = admin.get("/api/v1/admin/groups").body<List<AdminGroupOut>>()
        assertEquals(1L, listed.first { it.id == group.id }.memberCount)

        val updated = admin.patch("/api/v1/admin/groups/${group.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Team B","storageQuotaBytes":4096}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        val updatedGroup = updated.body<AdminGroupOut>()
        assertEquals("Team B", updatedGroup.name)
        assertEquals(4096L, updatedGroup.storageQuotaBytes)

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/admin/groups/${group.id}").status)
        val remaining = admin.get("/api/v1/admin/groups").body<List<AdminGroupOut>>()
        assertTrue(remaining.none { it.id == group.id })
    }

    @Test
    fun defaultGroupIsImplicitAndNotEditable() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val admin = jsonClient()
        admin.loginAsAdmin()
        jsonClient().registerAndLogin(username = "mey", password = "password12")

        val groups = admin.get("/api/v1/admin/groups").body<List<AdminGroupOut>>()
        val default = groups.first { it.name == "default" }
        assertEquals(2L, default.memberCount)

        val edit = admin.put("/api/v1/admin/groups/${default.id}/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, edit.status)

        val quota = admin.patch("/api/v1/admin/groups/${default.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"storageQuotaBytes":1024}""")
        }
        assertEquals(HttpStatusCode.OK, quota.status)
        assertEquals(1024L, quota.body<AdminGroupOut>().storageQuotaBytes)

        val rename = admin.patch("/api/v1/admin/groups/${default.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"everything"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, rename.status)

        val remove = admin.delete("/api/v1/admin/groups/${default.id}")
        assertEquals(HttpStatusCode.Forbidden, remove.status)
    }

    @Test
    fun adminGroupMembershipFollowsSuperadminRules() = testApplication {
        lateinit var groupService: GroupService
        installApi(adminPassword = "adminpass1012") { groupService = get() }
        val admin = jsonClient()
        admin.loginAsAdmin()
        val meyClient = jsonClient()
        val mey = meyClient.registerAndLogin(username = "mey", password = "password12")

        val adminGroupId = admin.get("/api/v1/admin/groups")
            .body<List<AdminGroupOut>>()
            .first { it.name == "admin" }
            .id
        val seededAdminId = admin.get("/api/v1/auth/me").body<User>().id.value

        val granted = admin.put("/api/v1/admin/groups/$adminGroupId/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":["$seededAdminId","${mey.id.value}"]}""")
        }
        assertEquals(HttpStatusCode.OK, granted.status)
        assertTrue(meyClient.get("/api/v1/auth/me").body<User>().admin)

        val removeOther = meyClient.put("/api/v1/admin/groups/$adminGroupId/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":["${mey.id.value}"]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, removeOther.status)

        val demoteSelf = meyClient.put("/api/v1/admin/groups/$adminGroupId/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":["$seededAdminId"]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, demoteSelf.status)

        groupService.setSuperadmin(mey.id, true)

        val modifySuperadmin = admin.patch("/api/v1/admin/users/${mey.id.value}") {
            contentType(ContentType.Application.Json)
            setBody("""{"displayName":"Mey"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, modifySuperadmin.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            admin.delete("/api/v1/admin/users/${mey.id.value}").status,
        )

        val demoted = meyClient.patch("/api/v1/admin/users/$seededAdminId") {
            contentType(ContentType.Application.Json)
            setBody("""{"admin":false}""")
        }
        assertEquals(HttpStatusCode.OK, demoted.status)
        assertFalse(admin.get("/api/v1/auth/me").body<User>().admin)
        assertEquals(
            listOf(mey.id.value),
            meyClient.get("/api/v1/admin/groups/$adminGroupId/members")
                .body<AdminGroupMembersResponse>()
                .members
                .map { it.userId },
        )

        val regranted = meyClient.put("/api/v1/admin/groups/$adminGroupId/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":["$seededAdminId","${mey.id.value}"]}""")
        }
        assertEquals(HttpStatusCode.OK, regranted.status)
        assertTrue(admin.get("/api/v1/auth/me").body<User>().admin)

        val removed = meyClient.put("/api/v1/admin/groups/$adminGroupId/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":["${mey.id.value}"]}""")
        }
        assertEquals(HttpStatusCode.OK, removed.status)
        assertFalse(admin.get("/api/v1/auth/me").body<User>().admin)
    }

    @Test
    fun effectiveQuotaIsStrictestAndUnlimitedForAdmins() = testApplication {
        lateinit var groupService: GroupService
        lateinit var authService: AuthService
        installApi(adminPassword = "adminpass1012") {
            groupService = get()
            authService = get()
        }
        val admin = jsonClient()
        admin.loginAsAdmin()
        val mey = jsonClient().registerAndLogin(username = "mey", password = "password12")

        val default = admin.get("/api/v1/admin/groups")
            .body<List<AdminGroupOut>>()
            .first { it.name == "default" }
        admin.patch("/api/v1/admin/groups/${default.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"storageQuotaBytes":1000}""")
        }
        val strict = admin.post("/api/v1/admin/groups") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Strict","storageQuotaBytes":500}""")
        }.body<AdminGroupOut>()
        admin.put("/api/v1/admin/groups/${strict.id}/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":["${mey.id.value}"]}""")
        }
        val loose = admin.post("/api/v1/admin/groups") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Loose","storageQuotaBytes":5000}""")
        }.body<AdminGroupOut>()
        admin.put("/api/v1/admin/groups/${loose.id}/members") {
            contentType(ContentType.Application.Json)
            setBody("""{"userIds":["${mey.id.value}"]}""")
        }

        val adminUser = authService.userByIdentifier("admin")!!
        assertEquals(500L, groupService.effectiveQuota(mey.id))
        assertEquals(null, groupService.effectiveQuota(adminUser.id))
        assertEquals(listOf("default", "Loose", "Strict"), groupService.groupsFor(mey.id))
    }

    @Test
    fun keelAdminGroupActionsWork() = testApplication {
        installApi(adminPassword = "adminpass1012")
        val admin = jsonClient()
        admin.loginAsAdmin()

        val listed = admin.post("${Keel.ACTION_PATH}/kalendee.adminGroups") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, listed.status)
        assertTrue(listed.bodyAsText().contains("\"name\":\"default\""))

        val created = admin.post("${Keel.ACTION_PATH}/kalendee.adminCreateGroup") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Keel Team","storageQuotaBytes":128}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)
        assertTrue(created.bodyAsText().contains("\"name\":\"Keel Team\""))

        val reserved = admin.post("${Keel.ACTION_PATH}/kalendee.adminCreateGroup") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"default"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, reserved.status)
        assertTrue(reserved.bodyAsText().contains("name"))

        val nonAdmin = jsonClient()
        nonAdmin.registerAndLogin(username = "mey", password = "password12")
        val denied = nonAdmin.post("${Keel.ACTION_PATH}/kalendee.adminGroups") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, denied.status)
    }
}

private suspend fun HttpClient.loginAsAdmin(password: String = "adminpass1012") {
    val result = login(username = "admin", password = password)
    check(result.user?.admin == true) { "admin login failed" }
}
