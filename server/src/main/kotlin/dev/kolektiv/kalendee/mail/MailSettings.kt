package dev.kolektiv.kalendee.mail

import io.ktor.server.config.ApplicationConfig
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

data class MailSettings(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val from: String,
    val startTls: Boolean,
    val provider: MailProvider,
    val cloudflare: CloudflareMailSettings,
    val mailgun: MailgunMailSettings,
) {
    companion object {
        private val log = LoggerFactory.getLogger(MailSettings::class.java)
        private val legacyProviderWarned = AtomicBoolean(false)

        fun from(config: ApplicationConfig): MailSettings {
            val enabled = config.propertyOrNull("mail.enabled")?.getString()?.toBooleanStrictOrNull() ?: false
            val host = config.propertyOrNull("mail.host")?.getString().orEmpty()
            val cloudflare = CloudflareMailSettings.from(config)
            val mailgun = MailgunMailSettings.from(config)
            val explicitProvider = config.propertyOrNull("mail.provider")?.getString()?.trim()?.takeIf { it.isNotBlank() }
            val provider = when {
                explicitProvider != null -> MailProvider.parse(explicitProvider)
                !enabled -> MailProvider.LOG
                host.isNotBlank() -> {
                    if (legacyProviderWarned.compareAndSet(false, true)) {
                        log.warn(
                            "mail.enabled is true with mail.host set but mail.provider is unset; " +
                                "defaulting to SMTP. Set mail.provider = \"smtp\" (or the equivalent " +
                                "KALENDEE_MAIL_PROVIDER) to silence this warning.",
                        )
                    }
                    MailProvider.SMTP
                }
                cloudflare.endpoint.isNotBlank() -> MailProvider.CLOUDFLARE
                mailgun.isComplete -> MailProvider.MAILGUN
                else -> MailProvider.LOG
            }
            return MailSettings(
                enabled = enabled,
                host = host,
                port = config.propertyOrNull("mail.port")?.getString()?.toIntOrNull() ?: 587,
                username = config.propertyOrNull("mail.username")?.getString().orEmpty(),
                password = config.propertyOrNull("mail.password")?.getString()?.takeIf { it.isNotBlank() },
                from = config.propertyOrNull("mail.from")?.getString()?.takeIf { it.isNotBlank() }
                    ?: "Kalendee <no-reply@localhost>",
                startTls = config.propertyOrNull("mail.startTls")?.getString()?.toBooleanStrictOrNull() ?: true,
                provider = provider,
                cloudflare = cloudflare,
                mailgun = mailgun,
            )
        }
    }
}

data class CloudflareMailSettings(
    val endpoint: String,
    val token: String,
    val timeoutSeconds: Int,
) {
    /** True when both the endpoint and bearer token are configured. */
    val isComplete: Boolean get() = endpoint.isNotBlank() && token.isNotBlank()

    companion object {
        fun from(config: ApplicationConfig): CloudflareMailSettings = CloudflareMailSettings(
            endpoint = config.propertyOrNull("mail.cloudflare.endpoint")?.getString().orEmpty(),
            token = config.propertyOrNull("mail.cloudflare.token")?.getString().orEmpty(),
            timeoutSeconds = config.propertyOrNull("mail.cloudflare.timeoutSeconds")?.getString()?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: 10,
        )
    }
}

data class MailgunMailSettings(
    val apiKey: String,
    val domain: String,
    val region: String,
    val baseUrl: String,
    val timeoutSeconds: Int,
) {
    /** True when both the API key and sending domain are configured. */
    val isComplete: Boolean get() = apiKey.isNotBlank() && domain.isNotBlank()

    /** True for the EU control plane, which uses a different API host. */
    private val isEu: Boolean get() = region.trim().equals("eu", ignoreCase = true)

    /**
     * Base URL of the Mailgun API. An explicit [baseUrl] wins; otherwise the
     * host is derived from [region] (`us` -> api.mailgun.net, `eu` ->
     * api.eu.mailgun.net).
     */
    val endpoint: String
        get() = baseUrl.trimEnd('/').ifBlank {
            if (isEu) "https://api.eu.mailgun.net" else "https://api.mailgun.net"
        }

    companion object {
        fun from(config: ApplicationConfig): MailgunMailSettings = MailgunMailSettings(
            apiKey = config.propertyOrNull("mail.mailgun.apiKey")?.getString().orEmpty(),
            domain = config.propertyOrNull("mail.mailgun.domain")?.getString().orEmpty(),
            region = config.propertyOrNull("mail.mailgun.region")?.getString()?.takeIf { it.isNotBlank() } ?: "us",
            baseUrl = config.propertyOrNull("mail.mailgun.baseUrl")?.getString().orEmpty(),
            timeoutSeconds = config.propertyOrNull("mail.mailgun.timeoutSeconds")?.getString()?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?: 10,
        )
    }
}
