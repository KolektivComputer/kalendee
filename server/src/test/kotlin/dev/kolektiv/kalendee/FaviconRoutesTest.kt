package dev.kolektiv.kalendee

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.ktor.ext.get

class FaviconRoutesTest {
    @Test
    fun faviconRoutesServeIconWithoutSessionLookup() = testApplication {
        lateinit var database: Database
        installApi(configure = { database = get() })
        val client = jsonClient()
        client.registerAndLogin()

        suspendTransaction(database) {
            exec("SET QUERY_STATISTICS FALSE")
            exec("SET QUERY_STATISTICS TRUE")
        }

        val svg = client.get("/favicon.svg")
        assertEquals(HttpStatusCode.OK, svg.status)
        assertEquals(ContentType.Image.SVG, svg.contentType())
        assertEquals("public, max-age=86400", svg.headers[HttpHeaders.CacheControl])
        assertTrue(svg.bodyAsText().startsWith("<svg"))
        val etag = svg.headers[HttpHeaders.ETag] ?: error("favicon response is missing ETag")

        val notModified = client.get("/favicon.svg") {
            header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.NotModified, notModified.status)
        assertEquals(etag, notModified.headers[HttpHeaders.ETag])

        val ico = client.get("/favicon.ico")
        assertEquals(HttpStatusCode.OK, ico.status)
        assertEquals(ContentType.Image.SVG, ico.contentType())
        assertTrue(ico.bodyAsText().startsWith("<svg"))

        val statements = suspendTransaction(database) {
            exec("SELECT SQL_STATEMENT FROM INFORMATION_SCHEMA.QUERY_STATISTICS") { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1))
                }
            }.orEmpty()
        }
        assertEquals(0, statements.count { "SESSIONS" in it.uppercase() }, statements.toString())
    }
}
