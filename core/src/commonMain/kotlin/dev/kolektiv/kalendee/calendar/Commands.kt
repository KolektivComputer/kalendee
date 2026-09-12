package dev.kolektiv.kalendee.calendar

import kotlin.time.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class CreateCalendar(
    val displayName: String,
    val description: String? = null,
    val timeZone: String? = null,
    val color: String? = null,
    val organizationId: OrganizationId? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateCalendar(
    val displayName: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val description: OptionalField<String?> = OptionalField.Absent,
    val timeZone: String? = null,
    val color: String? = null,
)

@Serializable
data class CreateEvent(
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val url: String? = null,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean = false,
    val timeZone: String? = null,
    val status: EventStatus = EventStatus.CONFIRMED,
    val recurrence: Recurrence? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateEvent(
    val title: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val description: OptionalField<String?> = OptionalField.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val location: OptionalField<String?> = OptionalField.Absent,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val url: OptionalField<String?> = OptionalField.Absent,
    val start: Instant? = null,
    val end: Instant? = null,
    val allDay: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val timeZone: OptionalField<String?> = OptionalField.Absent,
    val status: EventStatus? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val recurrence: OptionalField<Recurrence?> = OptionalField.Absent,
)
