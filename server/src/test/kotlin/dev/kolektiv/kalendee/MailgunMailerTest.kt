package dev.kolektiv.kalendee

import com.sun.net.httpserver.HttpServer
import dev.kolektiv.kalendee.mail.MailSettings
import dev.kolektiv.kalendee.mail.MailgunMailer
import dev.kolektiv.kalendee.mail.OutboundMail
import io.ktor.server.config.MapApplicationConfig
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MailgunMailerTest {
    private data class CapturedRequest(
        val method: String,
        val path: String,
        val authorization: String?,
        val contentType: String?,
        val body: String,
    )

    private fun settings(
        server: HttpServer,
        domain: String = "mg.example.com",
        apiKey: String = "secret-key",
    ): MailSettings = MailSettings.from(
        MapApplicationConfig(
            "mail.provider" to "mailgun",
            "mail.mailgun.apiKey" to apiKey,
            "mail.mailgun.domain" to domain,
            "mail.mailgun.baseUrl" to "http://127.0.0.1:${server.address.port}",
            "mail.mailgun.timeoutSeconds" to "5",
        ),
    )

    private fun withServer(
        status: Int = 200,
        responseBody: String = "",
        block: (HttpServer, AtomicReference<CapturedRequest>) -> Unit,
    ) {
        val captured = AtomicReference<CapturedRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
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

    private fun form(body: String): Map<String, String> = body
        .split("&")
        .filter { it.isNotEmpty() }
        .associate { pair ->
            val name = URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8)
            val value = URLDecoder.decode(pair.substringAfter('=', ""), StandardCharsets.UTF_8)
            name to value
        }

    private fun expectedBasic(key: String): String =
        "Basic " + Base64.getEncoder().encodeToString("api:$key".toByteArray(StandardCharsets.UTF_8))

    @Test
    fun postsToDomainMessagesEndpointWithBasicAuth() {
        withServer { server, captured ->
            val mailer = MailgunMailer(settings(server))
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
            assertEquals("/v3/mg.example.com/messages", request.path)
            assertEquals(expectedBasic("secret-key"), request.authorization)
            assertTrue(
                request.contentType.orEmpty().startsWith("application/x-www-form-urlencoded"),
                "unexpected content type: ${request.contentType}",
            )

            val fields = form(request.body)
            assertEquals("Kalendee <no-reply@localhost>", fields["from"])
            assertEquals("recipient@example.com", fields["to"])
            assertEquals("Hello", fields["subject"])
            assertEquals("plain text", fields["text"])
            assertEquals("<p>plain text</p>", fields["html"])
        }
    }

    @Test
    fun usesConfiguredDomainInPath() {
        withServer { server, captured ->
            val mailer = MailgunMailer(settings(server, domain = "mail.example.org"))
            runBlocking {
                mailer.send(OutboundMail(to = "recipient@example.com", subject = "Hi", text = "plain"))
            }
            assertEquals("/v3/mail.example.org/messages", captured.get().path)
        }
    }

    @Test
    fun omitsHtmlWhenAbsent() {
        withServer { server, captured ->
            val mailer = MailgunMailer(settings(server))
            runBlocking {
                mailer.send(OutboundMail(to = "recipient@example.com", subject = "Hi", text = "plain"))
            }
            val fields = form(captured.get().body)
            assertFalse(fields.containsKey("html"))
        }
    }

    @Test
    fun omitsHtmlWhenBlank() {
        withServer { server, captured ->
            val mailer = MailgunMailer(settings(server))
            runBlocking {
                mailer.send(
                    OutboundMail(to = "recipient@example.com", subject = "Hi", text = "plain", html = "  "),
                )
            }
            assertFalse(form(captured.get().body).containsKey("html"))
        }
    }

    @Test
    fun percentEncodesFormValuesWithoutDoubleEncoding() {
        withServer { server, captured ->
            val mailer = MailgunMailer(settings(server))
            runBlocking {
                mailer.send(
                    OutboundMail(
                        to = "recipient@example.com",
                        subject = "Hello World & more",
                        text = "a + b % c\nsecond line",
                    ),
                )
            }
            val raw = captured.get().body
            assertTrue(raw.contains("subject=Hello+World+%26+more"), "unexpected encoding: $raw")
            assertTrue(raw.contains("text=a+%2B+b+%25+c%0Asecond+line"), "unexpected encoding: $raw")

            val fields = form(raw)
            assertEquals("Hello World & more", fields["subject"])
            assertEquals("a + b % c\nsecond line", fields["text"])
        }
    }

    @Test
    fun usesCustomFromAddress() {
        withServer { server, captured ->
            val mailer = MailgunMailer(
                MailSettings.from(
                    MapApplicationConfig(
                        "mail.provider" to "mailgun",
                        "mail.from" to "Kalendee <calendar@example.com>",
                        "mail.mailgun.apiKey" to "secret-key",
                        "mail.mailgun.domain" to "mg.example.com",
                        "mail.mailgun.baseUrl" to "http://127.0.0.1:${server.address.port}",
                    ),
                ),
            )
            runBlocking {
                mailer.send(OutboundMail(to = "recipient@example.com", subject = "Hi", text = "plain"))
            }
            assertEquals("Kalendee <calendar@example.com>", form(captured.get().body)["from"])
        }
    }

    @Test
    fun non2xxThrowsWithStatusAndBody() {
        withServer(status = 401, responseBody = "Forbidden") { server, _ ->
            val mailer = MailgunMailer(settings(server))
            val error = assertFailsWith<IllegalStateException> {
                runBlocking {
                    mailer.send(OutboundMail(to = "recipient@example.com", subject = "Hi", text = "plain"))
                }
            }
            assertTrue(error.message.orEmpty().contains("401"), "missing status: ${error.message}")
            assertTrue(error.message.orEmpty().contains("Forbidden"), "missing body: ${error.message}")
        }
    }

    @Test
    fun networkFailureThrows() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        server.stop(0)
        val mailer = MailgunMailer(
            MailSettings.from(
                MapApplicationConfig(
                    "mail.provider" to "mailgun",
                    "mail.mailgun.apiKey" to "secret-key",
                    "mail.mailgun.domain" to "mg.example.com",
                    "mail.mailgun.baseUrl" to baseUrl,
                    "mail.mailgun.timeoutSeconds" to "2",
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
        val missingDomain = MailSettings.from(
            MapApplicationConfig(
                "mail.provider" to "mailgun",
                "mail.mailgun.apiKey" to "secret-key",
                "mail.mailgun.domain" to "",
            ),
        )
        assertFailsWith<IllegalArgumentException> { MailgunMailer(missingDomain) }

        val missingKey = MailSettings.from(
            MapApplicationConfig(
                "mail.provider" to "mailgun",
                "mail.mailgun.apiKey" to "",
                "mail.mailgun.domain" to "mg.example.com",
            ),
        )
        assertFailsWith<IllegalArgumentException> { MailgunMailer(missingKey) }
    }
}
