package dev.kolektiv.kalendee.calendar

import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.auth.UserId
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EventStatus {
    CONFIRMED,
    TENTATIVE,
    CANCELLED,
}

@Serializable
enum class CalendarPermission(val wire: String) {
    @SerialName("owner")
    OWNER("owner"),

    @SerialName("read")
    READ("read"),

    @SerialName("write")
    WRITE("write"),

    @SerialName("follow")
    FOLLOW("follow"),
    ;

    val canWrite: Boolean get() = this == OWNER || this == WRITE

    companion object {
        fun parseShare(raw: String): CalendarPermission {
            val value = raw.trim().lowercase()
            return entries.firstOrNull {
                it.wire == value && (it == CalendarPermission.READ || it == CalendarPermission.WRITE)
            } ?: throw CalendarException.Invalid("permission must be read or write")
        }

        fun fromWire(raw: String): CalendarPermission? = entries.firstOrNull { it.wire == raw }
    }
}

@Serializable
enum class OrganizationVisibility(val wire: String) {
    @SerialName("private")
    PRIVATE("private"),

    @SerialName("public")
    PUBLIC("public"),
    ;

    companion object {
        fun fromWire(raw: String): OrganizationVisibility? =
            entries.firstOrNull { it.wire == raw.trim().lowercase() }

        fun parse(raw: String): OrganizationVisibility =
            fromWire(raw) ?: throw CalendarException.Invalid("visibility must be private or public")
    }
}

@Serializable
enum class EventRsvpStatus(val wire: String) {
    @SerialName("invited")
    INVITED("invited"),

    @SerialName("yes")
    YES("yes"),

    @SerialName("no")
    NO("no"),

    @SerialName("maybe")
    MAYBE("maybe"),
    ;

    companion object {
        fun fromWire(raw: String): EventRsvpStatus? =
            entries.firstOrNull { it.wire == raw.trim().lowercase() }

        fun parse(raw: String): EventRsvpStatus =
            fromWire(raw) ?: throw CalendarException.Invalid("status must be invited, yes, no, or maybe")
    }
}

@Serializable
data class Calendar(
    val id: CalendarId,
    val ownerId: UserId,
    val displayName: String,
    val description: String? = null,
    val timeZone: String = "UTC",
    val color: String = "primary",
    val hidden: Boolean = false,
    val permission: CalendarPermission = CalendarPermission.OWNER,
    val publicLinkEnabled: Boolean = false,
    val publicLinkToken: String? = null,
    val requestsEnabled: Boolean = false,
    val rsvpEnabled: Boolean = false,
    val anonymousRsvpEnabled: Boolean = false,
    val slotMinutes: Int = 60,
    val accessMode: PublicAccessMode = PublicAccessMode.INHERIT,
    val createdAt: Instant,
    val updatedAt: Instant,
    val ownerAvatarVersion: Long? = null,
    val organizationId: OrganizationId? = null,
)

@Serializable
data class CalendarShare(
    val userId: UserId,
    val username: String,
    val displayName: String,
    val permission: CalendarPermission,
    val createdAt: Instant,
)

@Serializable
data class Event(
    val id: EventId,
    val calendarId: CalendarId,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val url: String? = null,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val timeZone: String? = null,
    val status: EventStatus,
    val recurrence: Recurrence? = null,
    val etag: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val openRsvp: Boolean = false,
    val rsvpEnabled: Boolean = false,
    val rsvpOverride: Boolean? = null,
    val anonymousRsvpOverride: Boolean? = null,
    val rsvpStatus: String? = null,
    val externalCalendarId: CalendarId? = null,
    val externalUid: String? = null,
)
