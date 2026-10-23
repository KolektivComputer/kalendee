package dev.kolektiv.kalendee

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RealtimeTest {
    @Test
    fun schemaExposesRealtimeEvents() = testApplication {
        installApi()
        val client = jsonClient()
        val response = client.get("/__keel/schema")
        assertEquals(HttpStatusCode.OK, response.status)
        val schema = Json.parseToJsonElement(response.body<String>()).jsonObject
        val events = schema["events"]?.jsonObject ?: error("schema missing events section")
        for (id in listOf(
            "kalendee.notification",
            "kalendee.eventChanged",
            "kalendee.remindersChanged",
        )) {
            val direction = events[id]?.jsonObject?.get("direction")?.jsonPrimitive?.content
            assertEquals("out", direction, "event '$id' should be registered as OUT")
        }
    }
}
