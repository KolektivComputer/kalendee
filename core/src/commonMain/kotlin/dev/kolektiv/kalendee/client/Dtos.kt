package dev.kolektiv.kalendee.client

import dev.kolektiv.kalendee.auth.User
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
data class HiddenBody(
    val hidden: Boolean,
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
