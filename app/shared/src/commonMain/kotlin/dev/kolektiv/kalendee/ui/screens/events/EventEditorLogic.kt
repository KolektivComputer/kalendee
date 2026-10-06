package dev.kolektiv.kalendee.ui.screens.events

import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.OptionalField
import dev.kolektiv.kalendee.calendar.Recurrence
import dev.kolektiv.kalendee.calendar.RecurrenceFrequency
import dev.kolektiv.kalendee.calendar.UpdateEvent
import dev.kolektiv.kalendee.calendar.validated
import dev.kolektiv.kalendee.ui.components.calendar.minutesToInstant
import dev.kolektiv.kalendee.ui.format.allDayEndDate
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Recurrence options offered by the editor; maps 1:1 to core [RecurrenceFrequency]. */
enum class RecurrenceChoice(val label: String) {
    None("None"),
    Daily("Daily"),
    Weekly("Weekly"),
    Monthly("Monthly"),
    Yearly("Yearly"),
    ;

    val frequency: RecurrenceFrequency?
        get() = when (this) {
            None -> null
            Daily -> RecurrenceFrequency.DAILY
            Weekly -> RecurrenceFrequency.WEEKLY
            Monthly -> RecurrenceFrequency.MONTHLY
            Yearly -> RecurrenceFrequency.YEARLY
        }

    companion object {
        fun fromFrequency(frequency: RecurrenceFrequency?): RecurrenceChoice = when (frequency) {
            null -> None
            RecurrenceFrequency.DAILY -> Daily
            RecurrenceFrequency.WEEKLY -> Weekly
            RecurrenceFrequency.MONTHLY -> Monthly
            RecurrenceFrequency.YEARLY -> Yearly
        }
    }
}

/**
 * Editor form state. For all-day drafts [start] is midnight of the first day and [end] is
 * the *exclusive* midnight after the last day (core convention), so a one-day event is
 * `start == end - 1 day`.
 */
data class EventDraft(
    val title: String = "",
    val location: String = "",
    val description: String = "",
    val allDay: Boolean = false,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val recurrence: RecurrenceChoice = RecurrenceChoice.None,
    val untilDate: LocalDate? = null,
)

/** Inline validation results, one message per field group. */
data class EventDraftErrors(
    val title: String? = null,
    val whenError: String? = null,
    val recurrence: String? = null,
) {
    val isValid: Boolean get() = title == null && whenError == null && recurrence == null
}

fun EventDraft.validate(): EventDraftErrors = EventDraftErrors(
    title = if (title.isBlank()) "Title is required." else null,
    whenError = when {
        allDay && end.date <= start.date -> "End date must not be before the start date."
        !allDay && end <= start -> "End must be after the start."
        else -> null
    },
    recurrence = if (
        recurrence != RecurrenceChoice.None &&
        untilDate != null &&
        untilDate < start.date
    ) {
        "Repeat until must not be before the start date."
    } else {
        null
    },
)

/** The event's start as an [Instant]; all-day uses local midnight of [start]'s date. */
fun EventDraft.startInstant(zone: TimeZone): Instant =
    if (allDay) start.date.atTime(0, 0).toInstant(zone) else start.toInstant(zone)

/** The event's end as an [Instant]; all-day [end] is already the exclusive midnight. */
fun EventDraft.endInstant(zone: TimeZone): Instant =
    if (allDay) end.date.atTime(0, 0).toInstant(zone) else end.toInstant(zone)

/**
 * Builds the core recurrence rule. An until date is inclusive: it becomes the midnight
 * after that date so occurrences on the chosen day are still included.
 */
fun EventDraft.recurrenceOrNull(zone: TimeZone): Recurrence? {
    val frequency = recurrence.frequency ?: return null
    val until = untilDate?.plus(1, DateTimeUnit.DAY)?.atTime(0, 0)?.toInstant(zone)
    return Recurrence(frequency = frequency, until = until)
}

/** Maps this draft to a validated create command (throws on invalid input). */
fun EventDraft.toCreateEvent(zone: TimeZone): CreateEvent = CreateEvent(
    title = title.trim(),
    description = description.trim().ifEmpty { null },
    location = location.trim().ifEmpty { null },
    start = startInstant(zone),
    end = endInstant(zone),
    allDay = allDay,
    timeZone = zone.id,
    recurrence = recurrenceOrNull(zone),
).validated()

/**
 * Maps this draft to an update command. Title/description/location/start/end/allDay and
 * recurrence are always sent explicitly so clearing fields and clearing recurrence work;
 * `url`, `status` and `timeZone` are left absent to keep their stored values.
 */
fun EventDraft.toUpdateEvent(zone: TimeZone): UpdateEvent = UpdateEvent(
    title = title.trim(),
    description = OptionalField.Present(description.trim().ifEmpty { null }),
    location = OptionalField.Present(location.trim().ifEmpty { null }),
    start = startInstant(zone),
    end = endInstant(zone),
    allDay = allDay,
    recurrence = OptionalField.Present(recurrenceOrNull(zone)),
)

/** Converts a timed draft to all-day, keeping every day the event touches. */
fun EventDraft.toAllDay(): EventDraft {
    if (allDay) return this
    val lastTouched = if (end.time == LocalTime(0, 0)) {
        end.date.minus(1, DateTimeUnit.DAY)
    } else {
        end.date
    }
    val inclusiveEnd = maxOf(lastTouched, start.date)
    return copy(
        allDay = true,
        start = start.date.atTime(0, 0),
        end = inclusiveEnd.plus(1, DateTimeUnit.DAY).atTime(0, 0),
    )
}

/** Converts an all-day draft back to timed: 09:00–10:00 for one day, 09:00–17:00 for several. */
fun EventDraft.toTimed(): EventDraft {
    if (!allDay) return this
    val inclusiveEnd = maxOf(end.date.minus(1, DateTimeUnit.DAY), start.date)
    val endTime = if (inclusiveEnd == start.date) LocalTime(10, 0) else LocalTime(17, 0)
    return copy(
        allDay = false,
        start = start.date.atTime(9, 0),
        end = inclusiveEnd.atTime(endTime.hour, endTime.minute),
    )
}

/** Rebuilds editor state from a stored [event] in [zone]. */
fun draftFrom(event: Event, zone: TimeZone): EventDraft {
    val startLocal = event.start.toLocalDateTime(zone)
    val endLocal = event.end.toLocalDateTime(zone)
    return EventDraft(
        title = event.title,
        location = event.location.orEmpty(),
        description = event.description.orEmpty(),
        allDay = event.allDay,
        start = if (event.allDay) startLocal.date.atTime(0, 0) else startLocal,
        end = if (event.allDay) {
            allDayEndDate(event.end, zone).plus(1, DateTimeUnit.DAY).atTime(0, 0)
        } else {
            endLocal
        },
        recurrence = RecurrenceChoice.fromFrequency(event.recurrence?.frequency),
        untilDate = event.recurrence?.until?.let { allDayEndDate(it, zone) },
    )
}

/** Shifts [value] by [minutes] on the local timeline of [zone]. */
fun shiftLocalMinutes(value: LocalDateTime, minutes: Int, zone: TimeZone): LocalDateTime =
    value.toInstant(zone).plus(minutes, DateTimeUnit.MINUTE).toLocalDateTime(zone)

/** Next half-hour slot (30 minutes long) after [now] in [zone]. */
fun nextDefaultSlot(
    now: Instant = Clock.System.now(),
    zone: TimeZone,
    slotMinutes: Int = 30,
): Pair<Instant, Instant> {
    val local = now.toLocalDateTime(zone)
    val minuteOfDay = local.hour * 60 + local.minute
    val startMinutes = ((minuteOfDay / slotMinutes) + 1) * slotMinutes
    val start = minutesToInstant(local.date, startMinutes, zone)
    return start to start + slotMinutes.minutes
}

/** Editor state for a new event, seeded by a tapped slot when present. */
fun defaultEventDraft(zone: TimeZone, seed: Pair<Instant, Instant>? = null): EventDraft {
    val (start, end) = seed ?: nextDefaultSlot(zone = zone)
    return EventDraft(
        start = start.toLocalDateTime(zone),
        end = end.toLocalDateTime(zone),
    )
}
