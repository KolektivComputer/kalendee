package dev.kolektiv.kalendee

import com.sun.net.httpserver.HttpServer
import dev.kolektiv.kalendee.mail.CloudflareMailer
import dev.kolektiv.kalendee.mail.MailSettings
import dev.kolektiv.kalendee.mail.OutboundMail
import io.ktor.server.config.MapApplicationConfig
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CloudflareMailerTest {
    private data class CapturedRequest(
        val method: String,
        val path: String,
        val authorization: String?,
        val contentType: String?,
        val body: String,
    )

    private fun settings(server: HttpServer, token: String = "secret-token"): MailSettings = MailSettings.from(
        MapApplicationConfig(
            "mail.provider" to "cloudflare",
            "mail.cloudflare.endpoint" to "http://127.0.0.1:${server.address.port}/send",
            "mail.cloudflare.token" to token,
            "mail.cloudflare.timeoutSeconds" to "5",
        ),
    )

    private fun withServer(
        status: Int = 200,
        responseBody: String = "",
        block: (HttpServer, AtomicReference<CapturedRequest>) -> Unit,
    ) {
        val captured = AtomicReference<CapturedRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/send") { exchange ->
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            captured.set(
                CapturedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    authorization = exchange.requestHeaders.getFirst("Authorization"),
                    contentType = exchange.requestHeaders.getFirst("Content-Type"),
                    body = body,
                ),
            )
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block(server, captured)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun postsFrozenContractToConfiguredEndpoint() {
        withServer { server, captured ->
            val mailer = CloudflareMailer(settings(server))
            runBlocking {
                mailer.send(
                    OutboundMail(
                        to = "recipient@example.com",
                        subject = "Hello",
                        text = "plain text",
                        html = "<p>plain text</p>",
                    ),
                )
            }
            val request = captured.get()
            assertEquals("POST", request.method)
            assertEquals("/send", request.path)
            assertEquals("Bearer secret-token", request.authorization)
            assertEquals("application/json", request.contentType?.substringBefore(';')?.trim())

            val json = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("Kalendee <no-reply@localhost>", json.getValue("from").jsonPrimitive.content)
            assertEquals("recipient@example.com", json.getValue("to").jsonPrimitive.content)
            assertEquals("Hello", json.getValue("subject").jsonPrimitive.content)
            assertEquals("plain text", json.getValue("text").jsonPrimitive.content)
            assertEquals("<p>plain text</p>", json.getValue("html").jsonPrimitive.content)
        }
    }

    @Test
    fun omitsHtmlWhenAbsent() {
        withServer { server, captured ->
            val mailer = CloudflareMailer(settings(server))
            runBlocking {
                mailer.send(OutboundMail(to = "recipient@example.com", subject = "Hi", text = "plain"))
            }
            val json = Json.parseToJsonElement(captured.get().body).jsonObject
            assertFalse(json.containsKey("html"))
            assertNull(json["html"])
        }
    }

    @Test
    fun usesCustomFromAddress() {
        withServer { server, captured ->
            val mailer = CloudflareMailer(
                MailSettings.from(
                    MapApplicationConfig(
                        "mail.provider" to "cloudflare",
                        "mail.from" to "Kalendee <calendar@example.com>",
                        "mail.cloudflare.endpoint" to "http://127.0.0.1:${server.address.port}/send",
                        "mail.cloudflare.token" to "secret-token",
                    ),
                ),
            )
            runBlocking {
                mailer.send(OutboundMail(to = "recipient@example.com", subject = "Hi", text = "plain"))
            }
            val json = Json.parseToJsonElement(captured.get().body).jsonObject
            assertEquals("Kalendee <calendar@example.com>", json.getValue("from").jsonPrimitive.content)
        }
    }

    @Test
    fun non2xxThrowsWithStatusAndBody() {
        withServer(status = 500, responseBody = "worker exploded") { server, _ ->
            val mailer = CloudflareMailer(settings(server))
            val error = assertFailsWith<IllegalStateException> {
                runBlocking {
                    mailer.send(OutboundMail(to = "recipient@example.com", subject = "Hi", text = "plain"))
                }
            }
            assertTrue(error.message.orEmpty().contains("500"), "missing status: ${error.message}")
            assertTrue(error.message.orEmpty().contains("worker exploded"), "missing body: ${error.message}")
        }
    }

    @Test
    fun networkFailureThrows() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        val endpoint = "http://127.0.0.1:${server.address.port}/send"
        server.stop(0)
        val mailer = CloudflareMailer(
            MailSettings.from(
                MapApplicationConfig(
                    "mail.provider" to "cloudflare",
                    "mail.cloudflare.endpoint" to endpoint,
                    "mail.cloudflare.token" to "secret-token",
                    "mail.cloudflare.timeoutSeconds" to "2",
                ),
            ),
        )
        assertFailsWith<Exception> {
            runBlocking {
                mailer.send(OutboundMail(to = "recipient@example.com", subject = "Hi", text = "plain"))
            }
        }
    }

    @Test
    fun rejectsIncompleteConfiguration() {
        val settings = MailSettings.from(
            MapApplicationConfig(
                "mail.provider" to "cloudflare",
                "mail.cloudflare.endpoint" to "https://mailer.example/send",
                "mail.cloudflare.token" to "",
            ),
        )
        assertFailsWith<IllegalArgumentException> { CloudflareMailer(settings) }
    }
}
