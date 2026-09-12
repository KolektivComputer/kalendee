package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.plugins.configureSerialization
import dev.kolektiv.kalendee.plugins.configureStatusPages
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusPagesTest {
    @Test
    fun developmentRendersEscapedHtmlErrorPage() = testApplication {
        application {
            configureSerialization()
            configureStatusPages(development = true)
            routing {
                get("/boom") { throw IllegalStateException("boom <script>alert(1)</script>") }
            }
        }
        val response = client.get("/boom")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("java.lang.IllegalStateException"), "exception class missing: $body")
        assertTrue(body.contains("boom &lt;script&gt;alert(1)&lt;/script&gt;"), "message not escaped: $body")
        assertTrue(body.contains("GET /boom"), "request line missing: $body")
        assertTrue(body.contains("app.development=false"), "banner missing: $body")
    }

    @Test
    fun productionReturnsInternalErrorJson() = testApplication {
        application {
            configureSerialization()
            configureStatusPages(development = false)
            routing {
                get("/boom") { throw IllegalStateException("boom") }
            }
        }
        val response = jsonClient().get("/boom")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(
            ErrorBody(error = "internal_error", message = "internal server error"),
            response.body(),
        )
    }

    @Test
    fun specificHandlersStillWinOverThrowable() = testApplication {
        application {
            configureSerialization()
            configureStatusPages(development = true)
            routing {
                get("/missing") { throw CalendarException.NotFound("nope") }
            }
        }
        val response = jsonClient().get("/missing")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ErrorBody(error = "not_found", message = "nope"), response.body())
    }
}
