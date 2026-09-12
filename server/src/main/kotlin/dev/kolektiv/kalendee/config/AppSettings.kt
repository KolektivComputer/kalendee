package dev.kolektiv.kalendee.config

import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory

data class AppSettings(
    val baseUrl: String,
    val development: Boolean,
    val publicAccess: String = "public",
    val seedDemo: Boolean = false,
    val demoPassword: String = "demo",
    val demoTimezone: String = "Europe/Berlin",
) {
    companion object {
        private val log = LoggerFactory.getLogger(AppSettings::class.java)

        fun from(config: ApplicationConfig, developmentMode: Boolean): AppSettings {
            val development = config.propertyOrNull("app.development")?.getString()?.toBooleanStrictOrNull()
                ?: developmentMode
            val publicAccess = config.propertyOrNull("app.publicAccess")?.getString()
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?: "public"
            val seedDemo = config.propertyOrNull("app.seedDemo")?.getString()?.toBooleanStrictOrNull() ?: false
            val demoPassword = config.propertyOrNull("app.demoPassword")?.getString()
                ?.takeIf { it.isNotBlank() }
                ?: "demo"
            val demoTimezone = config.propertyOrNull("app.demoTimezone")?.getString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "Europe/Berlin"
            val configured = config.propertyOrNull("app.baseUrl")?.getString().orEmpty().trim().trimEnd('/')
            if (configured.isNotEmpty()) {
                if (!configured.startsWith("http://") && !configured.startsWith("https://")) {
                    log.warn("app.baseUrl should start with http:// or https://; using {} as-is", configured)
                }
                return AppSettings(
                    baseUrl = configured,
                    development = development,
                    publicAccess = publicAccess,
                    seedDemo = seedDemo,
                    demoPassword = demoPassword,
                    demoTimezone = demoTimezone,
                )
            }
            if (development) {
                val port = config.propertyOrNull("ktor.deployment.port")?.getString()?.toIntOrNull() ?: 8080
                val baseUrl = "http://127.0.0.1:$port"
                log.info("app.baseUrl is blank; using development base URL {}", baseUrl)
                return AppSettings(
                    baseUrl = baseUrl,
                    development = development,
                    publicAccess = publicAccess,
                    seedDemo = seedDemo,
                    demoPassword = demoPassword,
                    demoTimezone = demoTimezone,
                )
            }
            return AppSettings(
                baseUrl = "",
                development = development,
                publicAccess = publicAccess,
                seedDemo = seedDemo,
                demoPassword = demoPassword,
                demoTimezone = demoTimezone,
            )
        }
    }
}
