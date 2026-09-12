package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.OptionalField
import dev.kolektiv.kalendee.calendar.Recurrence
import dev.kolektiv.kalendee.calendar.RecurrenceFrequency
import dev.kolektiv.kalendee.calendar.UpdateEvent
import dev.kolektiv.keel.KeelAction
import kotlin.time.Instant

class EventActions(
    private val store: CalendarStore,
    private val auth: AuthService,
    private val settings: AuthSettings,
) {
    @KeelAction("kalendee.createEvent")
    suspend fun create(input: CreateEventIn): EventSummary = mapDomainErrors("title") {
        val user = requireSessionUser(auth, settings)
        store.createEvent(
            CalendarId.parse(input.calendarId),
            user.id,
            CreateEvent(
                title = input.title,
                description = input.description?.trim()?.ifEmpty { null },
                location = input.location?.trim()?.ifEmpty { null },
                url = input.url?.trim()?.ifEmpty { null },
                start = parseInstant(input.start),
                end = parseInstant(input.end),
                allDay = input.allDay,
                timeZone = input.timeZone?.trim()?.ifEmpty { null },
                recurrence = input.recurrence?.toDomain(),
            ),
        ).toSummary()
    }

    @KeelAction("kalendee.updateEvent")
    suspend fun update(input: UpdateEventIn): EventSummary = mapDomainErrors("title") {
        val user = requireSessionUser(auth, settings)
        store.updateEvent(
            EventId.parse(input.id),
            user.id,
            UpdateEvent(
                title = input.title,
                description = optionalText(input.description),
                location = optionalText(input.location),
                url = optionalText(input.url),
                start = input.start?.let(::parseInstant),
                end = input.end?.let(::parseInstant),
                allDay = input.allDay,
                recurrence = when {
                    input.clearRecurrence -> OptionalField.Present(null)
                    input.recurrence != null -> OptionalField.Present(input.recurrence.toDomain())
                    else -> OptionalField.Absent
                },
            ),
            expectedEtag = input.etag,
        )?.toSummary() ?: throw CalendarException.NotFound("event not found")
    }

    @KeelAction("kalendee.moveEvent")
    suspend fun move(input: MoveEventIn): MoveEventOut = mapDomainErrors("calendarId") {
        val user = requireSessionUser(auth, settings)
        if (input.scope != "following") {
            throw CalendarException.Invalid("scope must be following")
        }
        val events = store.moveEvent(
            EventId.parse(input.id),
            user.id,
            CalendarId.parse(input.calendarId),
            occurrenceStart = input.from?.let(::parseFromInstant),
            expectedEtag = input.etag,
        ) ?: throw CalendarException.NotFound("event not found")
        MoveEventOut(events.map { it.toSummary() })
    }

    @KeelAction("kalendee.deleteEvent")
    suspend fun delete(input: DeleteEventIn): DeletedOut = mapDomainErrors("id") {
        val user = requireSessionUser(auth, settings)
        if (!store.deleteEvent(EventId.parse(input.id), user.id, input.etag)) {
            throw CalendarException.NotFound("event not found")
        }
        DeletedOut()
    }
}

private fun optionalText(value: String?): OptionalField<String?> = when (value) {
    null -> OptionalField.Absent
    else -> OptionalField.Present(value.trim().ifEmpty { null })
}

private fun RecurrenceIn.toDomain(): Recurrence {
    val parsed = RecurrenceFrequency.entries.find { it.name.equals(frequency, ignoreCase = true) }
        ?: throw CalendarException.Invalid("unknown recurrence frequency: $frequency")
    return Recurrence(
        frequency = parsed,
        interval = interval,
        until = until?.let(::parseInstant),
        count = count,
    )
}

private fun parseInstant(raw: String): Instant = try {
    Instant.parse(raw)
} catch (_: IllegalArgumentException) {
    throw CalendarException.Invalid("invalid instant: $raw")
}

private fun parseFromInstant(raw: String): Instant = try {
    parseInstant(raw)
} catch (_: CalendarException.Invalid) {
    throw CalendarException.Invalid("invalid from: $raw")
}
