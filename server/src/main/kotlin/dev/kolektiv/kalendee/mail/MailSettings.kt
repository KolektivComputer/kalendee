package dev.kolektiv.kalendee.mail

import io.ktor.server.config.ApplicationConfig

data class MailSettings(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val from: String,
    val startTls: Boolean,
) {
    companion object {
        fun from(config: ApplicationConfig): MailSettings = MailSettings(
            enabled = config.propertyOrNull("mail.enabled")?.getString()?.toBooleanStrictOrNull() ?: false,
            host = config.propertyOrNull("mail.host")?.getString().orEmpty(),
            port = config.propertyOrNull("mail.port")?.getString()?.toIntOrNull() ?: 587,
            username = config.propertyOrNull("mail.username")?.getString().orEmpty(),
            password = config.propertyOrNull("mail.password")?.getString()?.takeIf { it.isNotBlank() },
            from = config.propertyOrNull("mail.from")?.getString()?.takeIf { it.isNotBlank() }
                ?: "Kalendee <no-reply@localhost>",
            startTls = config.propertyOrNull("mail.startTls")?.getString()?.toBooleanStrictOrNull() ?: true,
        )
    }
}
