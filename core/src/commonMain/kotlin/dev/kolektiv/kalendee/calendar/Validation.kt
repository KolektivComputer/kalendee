package dev.kolektiv.kalendee.calendar

import kotlin.time.Instant
import kotlinx.datetime.TimeZone

fun CreateCalendar.validated(defaultTimeZone: String = "UTC"): CreateCalendar {
    val name = requireNonBlank(displayName, "displayName")
    val zone = timeZone?.trim()?.ifEmpty { null } ?: defaultTimeZone
    return copy(
        displayName = name,
        timeZone = requireTimeZone(zone),
        color = color?.let(::requireColor),
    )
}

fun CreateEvent.validated(): CreateEvent {
    val eventTitle = requireNonBlank(title, "title")
    requireEndAfterStart(start, end)
    recurrence?.let { rule ->
        if (rule.until != null && rule.until <= start) {
            throw CalendarException.Invalid("until must be after start")
        }
    }
    return copy(
        title = eventTitle,
        url = url?.let(::requireUrl),
        timeZone = timeZone?.let(::requireTimeZone),
        recurrence = recurrence?.validated(),
    )
}

fun UpdateCalendar.validated(): UpdateCalendar = copy(
    displayName = displayName?.let { requireNonBlank(it, "displayName") },
    timeZone = timeZone?.let(::requireTimeZone),
    color = color?.let(::requireColor),
)

fun UpdateEvent.validated(existingStart: Instant, existingEnd: Instant): UpdateEvent {
    val nextStart = start ?: existingStart
    val nextEnd = end ?: existingEnd
    requireEndAfterStart(nextStart, nextEnd)
    val nextRecurrence = when (val rule = recurrence) {
        OptionalField.Absent -> OptionalField.Absent
        is OptionalField.Present -> OptionalField.Present(
            rule.value?.validated()?.also {
                if (it.until != null && it.until <= nextStart) {
                    throw CalendarException.Invalid("until must be after start")
                }
            },
        )
    }
    return copy(
        title = title?.let { requireNonBlank(it, "title") },
        url = url.validatedUrl(),
        timeZone = timeZone.validatedTimeZone(),
        recurrence = nextRecurrence,
    )
}

private fun OptionalField<String?>.validatedTimeZone(): OptionalField<String?> = when (this) {
    OptionalField.Absent -> this
    is OptionalField.Present -> OptionalField.Present(value?.let(::requireTimeZone))
}

fun requireNonBlank(value: String, field: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        throw CalendarException.Invalid("$field must not be blank")
    }
    return trimmed
}

fun requireEndAfterStart(start: Instant, end: Instant) {
    if (end <= start) {
        throw CalendarException.Invalid("end must be after start")
    }
}

fun requireUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        throw CalendarException.Invalid("url must not be blank")
    }
    if (trimmed.length > 2048) {
        throw CalendarException.Invalid("url must be at most 2048 characters")
    }
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        throw CalendarException.Invalid("url must start with http:// or https://")
    }
    if (trimmed.any { it.isWhitespace() }) {
        throw CalendarException.Invalid("url must not contain whitespace")
    }
    return trimmed
}

private fun OptionalField<String?>.validatedUrl(): OptionalField<String?> = when (this) {
    OptionalField.Absent -> this
    is OptionalField.Present -> OptionalField.Present(value?.let(::requireUrl))
}

fun requireTimeZone(id: String): String {
    val trimmed = id.trim()
    if (trimmed.isEmpty()) {
        throw CalendarException.Invalid("timeZone must not be blank")
    }
    try {
        TimeZone.of(trimmed)
    } catch (_: IllegalArgumentException) {
        throw CalendarException.Invalid("unknown time zone: $trimmed")
    }
    return trimmed
}
