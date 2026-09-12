package dev.kolektiv.kalendee.auth

import dev.kolektiv.kalendee.calendar.CalendarException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PublicAccessMode(val wire: String) {
    @SerialName("inherit")
    INHERIT("inherit"),

    @SerialName("public")
    PUBLIC("public"),

    @SerialName("signed_in")
    SIGNED_IN("signed_in"),
    ;

    companion object {
        fun parse(raw: String): PublicAccessMode {
            val value = raw.trim().lowercase()
            return entries.firstOrNull { it.wire == value }
                ?: throw CalendarException.Invalid("access mode must be inherit, public, or signed_in")
        }

        fun fromWire(raw: String?): PublicAccessMode? {
            val value = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == value }
        }
    }
}
