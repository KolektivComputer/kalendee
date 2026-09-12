package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.NotificationOut
import dev.kolektiv.kalendee.api.NotificationStateResponse
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.notifications.NotificationService
import dev.kolektiv.keel.Keel
import dev.kolektiv.keel.KeelJson
import dev.kolektiv.keel.seed.KeelSeed
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.call.body
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
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.get

class NotificationApiTest {
    @Test
    fun notificationsRoundtripThroughApiAndKeel() = testApplication {
        installApi()
        application {
            val auth = get<AuthService>()
            val service = get<NotificationService>()
            runBlocking {
                val session = auth.register(RegisterUser(username = "mey", password = "password12")).session
                    ?: error("expected a session")
                service.create(session.user.id, kind = "test", title = "First")
                service.create(session.user.id, kind = "test", title = "Second", body = "Second body", href = "/settings")
            }
        }
        val client = jsonClient()
        client.login("mey", "password12")

        val list = client.get("/api/v1/notifications")
        assertEquals(HttpStatusCode.OK, list.status)
        val notifications = list.body<List<NotificationOut>>()
        assertEquals(2, notifications.size)
        assertTrue(notifications.none { it.read })
        assertEquals("Second body", notifications.first { it.title == "Second" }.body)
        assertEquals("/settings", notifications.first { it.title == "Second" }.href)

        val first = notifications.first { it.title == "First" }
        val marked = client.post("/api/v1/notifications/${first.id}/read")
        assertEquals(HttpStatusCode.OK, marked.status)
        assertEquals(1, marked.body<NotificationStateResponse>().unreadCount)

        val page = client.get("/notifications") {
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, page.status)
        val seed = KeelJson.codec.decodeFromString(KeelSeed.serializer(), page.bodyAsText())
        assertEquals("kalendee.notifications", seed.page)
        assertTrue(page.bodyAsText().contains("\"unreadCount\":1"))
        assertTrue(page.bodyAsText().contains("\"notifications\""))

        val markAll = client.post("${Keel.ACTION_PATH}/kalendee.markAllNotificationsRead") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, markAll.status)
        assertTrue(markAll.bodyAsText().contains("\"unreadCount\":0"))

        val remaining = client.get("/api/v1/notifications").body<List<NotificationOut>>()
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.read })
    }

    @Test
    fun markNotificationReadActionUpdatesUnreadCount() = testApplication {
        installApi()
        application {
            val auth = get<AuthService>()
            val service = get<NotificationService>()
            runBlocking {
                val session = auth.register(RegisterUser(username = "mey", password = "password12")).session
                    ?: error("expected a session")
                service.create(session.user.id, kind = "test", title = "Hello")
            }
        }
        val client = jsonClient()
        client.login("mey", "password12")
        val page = client.get("/notifications") {
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, page.status)
        val idPattern = Regex(""""id":"([0-9a-f-]+)","kind":"test"""")
        val id = idPattern.find(page.bodyAsText())?.groupValues?.get(1) ?: error("no notification id")

        val action = client.post("${Keel.ACTION_PATH}/kalendee.markNotificationRead") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$id"}""")
        }
        assertEquals(HttpStatusCode.OK, action.status)
        assertTrue(action.bodyAsText().contains("\"unreadCount\":0"))
    }

    @Test
    fun notificationsRequireLogin() = testApplication {
        installApi()
        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().get("/api/v1/notifications").status,
        )

        val page = client.get("/notifications") {
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, page.status)
        val seed = KeelJson.codec.decodeFromString(KeelSeed.serializer(), page.bodyAsText())
        assertEquals("/login", seed.redirect)
    }
}
