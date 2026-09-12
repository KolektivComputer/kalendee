package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.DiscoveryResponse
import dev.kolektiv.kalendee.api.HealthResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun apiRootReturnsDiscoveryJson() = testApplication {
        installApi()
        val client = jsonClient()
        val response = client.get("/api/v1")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            DiscoveryResponse(service = "kalendee", api = "/api/v1"),
            response.body<DiscoveryResponse>(),
        )
    }

    @Test
    fun healthPingsPostgres() = testApplication {
        installApi()
        val client = jsonClient()
        val response = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(HealthResponse(status = "ok"), response.body())
    }

    @Test
    fun calendarsRequireAuth() = testApplication {
        installApi()
        val client = jsonClient()
        val response = client.get("/api/v1/calendars")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
