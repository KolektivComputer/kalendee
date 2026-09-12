package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.calendar.Calendar
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class DiscoveryResponse(
    val service: String,
    val api: String,
)

@Serializable
data class HealthResponse(
    val status: String,
)

@Serializable
data class ErrorBody(
    val error: String,
    val message: String,
)

@Serializable
data class AuthResult(
    val user: User? = null,
    val verificationRequired: Boolean = false,
    val email: String? = null,
)

@Serializable
data class VerifyEmailBody(
    val token: String,
)

@Serializable
data class VerifyEmailResponse(
    val ok: Boolean,
    val email: String? = null,
)

@Serializable
data class ResendVerificationBody(
    val username: String,
)

@Serializable
data class OkResponse(
    val ok: Boolean = true,
)

@Serializable
data class HiddenBody(
    val hidden: Boolean,
)

@Serializable
data class AvatarOut(
    val avatarUrl: String,
    val version: Long,
)

@Serializable
data class NotificationOut(
    val id: String,
    val kind: String,
    val title: String,
    val body: String? = null,
    val href: String? = null,
    val read: Boolean,
    val createdAt: String,
)

@Serializable
data class NotificationStateResponse(
    val unreadCount: Int,
)

@Serializable
data class ShareCalendarBody(
    val username: String,
    val permission: String = "read",
)

@Serializable
data class UpdateShareBody(
    val permission: String,
)

@Serializable
data class PublicLinkBody(
    val enabled: Boolean,
)

@Serializable
data class PublicCalendarOut(
    val calendar: Calendar,
    val following: Boolean,
)

@Serializable
data class ReminderSettingsBody(
    val defaultOffsetsSeconds: List<Int>,
    val notifyAtStart: Boolean,
)

@Serializable
data class ReminderSettingsResponse(
    val defaultOffsetsSeconds: List<Int>,
    val notifyAtStart: Boolean,
)

@Serializable
data class EventRemindersResponse(
    val eventId: String,
    val offsetsSeconds: List<Int>,
    val useDefaults: Boolean,
    val defaultOffsetsSeconds: List<Int>,
    val notifyAtStart: Boolean,
)

@Serializable
data class SetEventRemindersBody(
    val offsetsSeconds: List<Int>,
    val useDefaults: Boolean,
)

@Serializable
data class ReminderInstanceResponse(
    val eventId: String,
    val calendarId: String,
    val calendarName: String,
    val calendarColor: String,
    val title: String,
    val start: String,
    val allDay: Boolean,
    val offsetSeconds: Int,
    val remindAt: String,
)

@Serializable
data class AvailabilityWindowBody(
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
)

@Serializable
data class UpdateAvailabilityBody(
    val requestsEnabled: Boolean,
    val slotMinutes: Int = 60,
    val accessMode: String = "inherit",
    val windows: List<AvailabilityWindowBody> = emptyList(),
)

@Serializable
data class RequestTimeSlotBody(
    val start: Instant,
    val end: Instant,
    val message: String? = null,
)

@Serializable
data class PublicRequestTimeSlotBody(
    val name: String,
    val email: String? = null,
    val start: Instant,
    val end: Instant,
    val message: String? = null,
)

@Serializable
data class RespondTimeSlotBody(
    val accept: Boolean,
    val message: String? = null,
)

@Serializable
data class InviteEventBody(
    val username: String,
    val name: String? = null,
)

@Serializable
data class RespondEventBody(
    val status: String,
)

@Serializable
data class SetOpenRsvpBody(
    val enabled: Boolean,
)

@Serializable
data class PublicRsvpBody(
    val name: String,
    val email: String? = null,
    val status: String,
)

@Serializable
data class RsvpByTokenBody(
    val token: String,
    val status: String,
)

@Serializable
data class AdminUpdateUserBody(
    val displayName: String? = null,
    val email: String? = null,
    val password: String? = null,
    val admin: Boolean? = null,
)

@Serializable
data class AdminUserOut(
    val id: String,
    val username: String,
    val displayName: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val admin: Boolean,
    val superadmin: Boolean = false,
    val timeZone: String,
    val storageBytes: Long = 0,
    val quotaBytes: Long? = null,
    val groups: List<String> = emptyList(),
    val createdAt: String,
)

@Serializable
data class AdminCalendarOut(
    val id: String,
    val displayName: String,
    val ownerUsername: String,
    val eventCount: Long,
    val publicLinkEnabled: Boolean,
)

@Serializable
data class AdminSetCalendarPublicBody(
    val enabled: Boolean,
)

@Serializable
data class AdminCreateGroupBody(
    val name: String,
    val storageQuotaBytes: Long? = null,
)

@Serializable
data class AdminUpdateGroupBody(
    val name: String? = null,
    val storageQuotaBytes: Long? = null,
    val clearQuota: Boolean = false,
)

@Serializable
data class AdminGroupOut(
    val id: String,
    val name: String,
    val isSystem: Boolean,
    val storageQuotaBytes: Long? = null,
    val memberCount: Long,
)

@Serializable
data class AdminGroupMemberOut(
    val userId: String,
    val username: String,
    val displayName: String,
    val admin: Boolean,
)

@Serializable
data class AdminGroupMembersResponse(
    val groupId: String,
    val members: List<AdminGroupMemberOut>,
)

@Serializable
data class AdminSetGroupMembersBody(
    val userIds: List<String>,
)
