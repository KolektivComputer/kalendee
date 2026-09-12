package dev.kolektiv.kalendee.auth

import dev.kolektiv.kalendee.calendar.CalendarException
import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class UserId(val value: String) {
    companion object {
        fun generate(): UserId = UserId(Uuid.random().toString())

        fun parse(raw: String): UserId {
            val parsed = Uuid.parseOrNull(raw)
                ?: throw CalendarException.Invalid("invalid user id")
            return UserId(parsed.toString())
        }
    }
}

@Serializable
data class User(
    val id: UserId,
    val username: String,
    val displayName: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val avatarVersion: Long? = null,
    val timeZone: String = "UTC",
    val accent: String = Accent.Default,
    val admin: Boolean = false,
    val publicAccess: PublicAccessMode = PublicAccessMode.INHERIT,
    val createdAt: Instant,
)
