package dev.kolektiv.kalendee.mail

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends mail through the Mailgun HTTP API.
 *
 * The wire contract is `POST {endpoint}/v3/{domain}/messages` with HTTP Basic
 * auth (`api:{apiKey}`) and an `application/x-www-form-urlencoded` body of
 * `from`, `to`, `subject`, `text`, and `html` (omitted when absent). Any 2xx
 * response is success; a non-2xx response or a network/timeout failure throws,
 * including the status and response body in the message.
 */
class MailgunMailer(
    private val settings: MailSettings,
    private val client: HttpClient = defaultClient(settings.mailgun.timeoutSeconds),
) : Mailer {
    private val mailgun = settings.mailgun

    init {
        require(mailgun.apiKey.isNotBlank()) { "mail.mailgun.apiKey must not be blank" }
        require(mailgun.domain.isNotBlank()) { "mail.mailgun.domain must not be blank" }
    }

    override suspend fun send(message: OutboundMail) {
        withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${mailgun.endpoint}/v3/${mailgun.domain}/messages"))
                .timeout(Duration.ofSeconds(mailgun.timeoutSeconds.toLong()))
                .header("Authorization", basicAuth())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(message), StandardCharsets.UTF_8))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException(
                    "mailgun mailer returned HTTP ${response.statusCode()}: ${response.body()}",
                )
            }
        }
    }

    private fun basicAuth(): String {
        val credentials = "api:${mailgun.apiKey}".toByteArray(StandardCharsets.UTF_8)
        return "Basic ${Base64.getEncoder().encodeToString(credentials)}"
    }

    private fun form(message: OutboundMail): String {
        val fields = buildList {
            add("from" to settings.from)
            add("to" to message.to)
            add("subject" to message.subject)
            add("text" to message.text)
            message.html?.takeIf { it.isNotBlank() }?.let { add("html" to it) }
        }
        return fields.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        fun defaultClient(timeoutSeconds: Int): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .build()
    }
}
