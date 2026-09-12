package dev.kolektiv.kalendee.db

import io.ktor.server.config.ApplicationConfig

data class DatabaseSettings(
    val url: String,
    val user: String,
    val password: String,
    val migrationsLocation: String = "classpath:db/migration",
) {
    companion object {
        const val DEFAULT_URL = "jdbc:postgresql://127.0.0.1:5432/kalendee"
        const val DEFAULT_USER = "kalendee"

        fun from(config: ApplicationConfig): DatabaseSettings {
            val rawUrl = config.propertyOrNull("database.url")?.getString() ?: DEFAULT_URL
            val parsed = parsePostgresUrl(rawUrl)
            val user = config.propertyOrNull("database.user")?.getString()
                ?: parsed.user
                ?: DEFAULT_USER
            val password = config.propertyOrNull("database.password")?.getString()
                ?: parsed.password
                ?: error(
                    "KALENDEE_DATABASE_PASSWORD is required (set an empty value for trust auth)",
                )
            val migrations = config.propertyOrNull("database.migrations")?.getString()?.trim().orEmpty()
            return DatabaseSettings(
                url = parsed.jdbcUrl,
                user = user,
                password = password,
                migrationsLocation = migrations.ifEmpty { "classpath:db/migration" },
            )
        }
    }
}

internal data class ParsedPostgresUrl(
    val jdbcUrl: String,
    val user: String?,
    val password: String?,
)

internal fun parsePostgresUrl(raw: String): ParsedPostgresUrl {
    val prefix = "jdbc:postgresql://"
    if (!raw.startsWith(prefix)) return ParsedPostgresUrl(raw, null, null)
    val rest = raw.removePrefix(prefix)
    val at = rest.indexOf('@')
    val slash = rest.indexOf('/')
    if (at <= 0 || (slash in 0 until at)) return ParsedPostgresUrl(raw, null, null)
    val userInfo = rest.take(at)
    val hostAndDb = rest.substring(at + 1)
    val colon = userInfo.indexOf(':')
    val user = if (colon < 0) userInfo else userInfo.take(colon)
    val password = if (colon < 0) null else userInfo.substring(colon + 1)
    return ParsedPostgresUrl(
        jdbcUrl = prefix + hostAndDb,
        user = user,
        password = password,
    )
}
