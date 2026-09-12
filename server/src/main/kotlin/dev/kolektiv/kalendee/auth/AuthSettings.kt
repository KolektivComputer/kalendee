package dev.kolektiv.kalendee.auth

import io.ktor.server.config.ApplicationConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

enum class RegistrationPolicy {
    Open,
    Closed,
    FirstUser,
    ;

    companion object {
        fun parse(raw: String): RegistrationPolicy = when (raw.trim().lowercase()) {
            "open" -> Open
            "closed" -> Closed
            "first-user", "first_user", "firstuser" -> FirstUser
            else -> error("unknown auth.registration: $raw")
        }
    }
}

data class AuthSettings(
    val registration: RegistrationPolicy,
    val sessionTtl: Duration,
    val cookieName: String,
    val cookieSecure: Boolean,
    val argon2MemoryKib: Int,
    val argon2Iterations: Int,
    val argon2Parallelism: Int,
    val adminUsername: String,
    val adminPassword: String?,
    val superadminUsername: String? = null,
    val emailVerification: EmailVerificationPolicy = EmailVerificationPolicy.Optional,
    val emailVerificationTtl: Duration = 24.hours,
) {
    companion object {
        fun from(config: ApplicationConfig, developmentMode: Boolean): AuthSettings {
            val registration = RegistrationPolicy.parse(
                config.propertyOrNull("auth.registration")?.getString() ?: "first-user",
            )
            val sessionDays = config.propertyOrNull("auth.sessionDays")?.getString()?.toIntOrNull() ?: 30
            val cookieName = config.propertyOrNull("auth.cookieName")?.getString() ?: "kalendee_session"
            val cookieSecure = config.propertyOrNull("auth.cookieSecure")?.getString()?.toBooleanStrictOrNull()
                ?: !developmentMode
            val adminUsername = config.propertyOrNull("auth.adminUsername")?.getString() ?: "admin"
            val adminPassword = config.propertyOrNull("auth.adminPassword")
                ?.getString()
                ?.takeIf { it.isNotBlank() }
            val superadminUsername = config.propertyOrNull("auth.superadminUsername")
                ?.getString()
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
            val emailVerification = config.propertyOrNull("auth.emailVerification")?.getString()
                ?.let(EmailVerificationPolicy::parse)
                ?: EmailVerificationPolicy.Optional
            val emailVerificationTtlHours = config.propertyOrNull("auth.emailVerificationTtlHours")
                ?.getString()
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: 24
            return AuthSettings(
                registration = registration,
                sessionTtl = sessionDays.days,
                cookieName = cookieName,
                cookieSecure = cookieSecure,
                argon2MemoryKib = config.propertyOrNull("auth.argon2.memoryKib")?.getString()?.toIntOrNull()
                    ?: 19_456,
                argon2Iterations = config.propertyOrNull("auth.argon2.iterations")?.getString()?.toIntOrNull()
                    ?: 2,
                argon2Parallelism = config.propertyOrNull("auth.argon2.parallelism")?.getString()?.toIntOrNull()
                    ?: 1,
                adminUsername = adminUsername,
                adminPassword = adminPassword,
                superadminUsername = superadminUsername,
                emailVerification = emailVerification,
                emailVerificationTtl = emailVerificationTtlHours.hours,
            )
        }
    }
}
