package dev.kolektiv.kalendee.mail

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Sends mail through a Cloudflare mailer Worker.
 *
 * The frozen wire contract is `POST {endpoint}` with a bearer token and a JSON
 * body of `{from, to, subject, text, html}`; `html` is omitted when absent. Any
 * 2xx response is success; a non-2xx response or a network/timeout failure
 * throws.
 */
class CloudflareMailer(
    private val settings: MailSettings,
    private val client: HttpClient = defaultClient(settings.cloudflare.timeoutSeconds),
) : Mailer {
    private val cloudflare = settings.cloudflare

    init {
        require(cloudflare.endpoint.isNotBlank()) { "mail.cloudflare.endpoint must not be blank" }
        require(cloudflare.token.isNotBlank()) { "mail.cloudflare.token must not be blank" }
    }

    override suspend fun send(message: OutboundMail) {
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                MailPayload(
                    from = settings.from,
                    to = message.to,
                    subject = message.subject,
                    text = message.text,
                    html = message.html,
                ),
            )
            val request = HttpRequest.newBuilder()
                .uri(URI.create(cloudflare.endpoint))
                .timeout(Duration.ofSeconds(cloudflare.timeoutSeconds.toLong()))
                .header("Authorization", "Bearer ${cloudflare.token}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException(
                    "cloudflare mailer returned HTTP ${response.statusCode()}: ${response.body()}",
                )
            }
        }
    }

    private companion object {
        val json = Json {
            encodeDefaults = false
            explicitNulls = true
        }

        fun defaultClient(timeoutSeconds: Int): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .build()
    }
}

@Serializable
private data class MailPayload(
    val from: String,
    val to: String,
    val subject: String,
    val text: String,
    val html: String? = null,
)
