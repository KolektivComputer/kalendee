package dev.kolektiv.kalendee.ui.format

import dev.kolektiv.kalendee.calendar.Event
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

private val ShortWeekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** `Mon`, `Tue`, … for the ISO weekday of [date]. */
fun shortWeekday(date: LocalDate): String = ShortWeekdays[date.dayOfWeek.isoDayNumber - 1]

/** `October` for [month]; always English so labels are stable across platforms. */
fun monthName(month: Month): String {
    val raw = month.name.lowercase()
    return raw.replaceFirstChar { it.uppercase() }
}

/** `Oct` for [month]. */
fun shortMonthName(month: Month): String = monthName(month).take(3)

/** `09:30` for [minutes] after midnight; wraps around a 24h clock. */
fun formatClock(minutes: Int): String {
    val clamped = ((minutes % 1440) + 1440) % 1440
    return "${(clamped / 60).pad2()}:${(clamped % 60).pad2()}"
}

/** `09:30` for [time]. */
fun formatTime(time: LocalTime): String = "${time.hour.pad2()}:${time.minute.pad2()}"

/** `09:30` for [instant] in [zone]. */
fun formatTime(instant: Instant, zone: TimeZone): String = formatTime(instant.toLocalDateTime(zone).time)

/** `09:30 – 10:00` for [start]–[end] in [zone]. */
fun formatTimeRange(start: Instant, end: Instant, zone: TimeZone): String =
    "${formatTime(start, zone)} – ${formatTime(end, zone)}"

/** `Mon 6` for [date]. */
fun formatDayHeading(date: LocalDate): String = "${shortWeekday(date)} ${date.day}"

/** `Mon, Oct 6, 2026` for [date]. */
fun formatLongDate(date: LocalDate): String =
    "${shortWeekday(date)}, ${shortMonthName(date.month)} ${date.day}, ${date.year}"

/** `Mon, Oct 6, 2026, 09:30` for [value]. */
fun formatDateTime(value: LocalDateTime): String =
    "${formatLongDate(value.date)}, ${formatTime(value.time)}"

/** `Mon, Oct 6, 2026, 09:30` for [instant] in [zone]. */
fun formatDateTime(instant: Instant, zone: TimeZone): String =
    formatDateTime(instant.toLocalDateTime(zone))

/**
 * Last calendar day covered by an all-day event that ends at [end]. All-day events are
 * stored with an exclusive end, so a midnight end means the previous day is the last one.
 */
fun allDayEndDate(end: Instant, zone: TimeZone): LocalDate {
    val local = end.toLocalDateTime(zone)
    return if (local.time == LocalTime(0, 0)) {
        local.date.minus(1, DateTimeUnit.DAY)
    } else {
        local.date
    }
}

/** Human-readable local "when" line for [event] in [zone], including the all-day marker. */
fun formatEventWhen(event: Event, zone: TimeZone): String {
    val startDate = event.start.toLocalDateTime(zone).date
    if (event.allDay) {
        val endDate = allDayEndDate(event.end, zone)
        val span = if (startDate == endDate) {
            formatLongDate(startDate)
        } else {
            "${formatLongDate(startDate)} – ${formatLongDate(endDate)}"
        }
        return "$span (all day)"
    }
    val endDate = event.end.toLocalDateTime(zone).date
    return if (startDate == endDate) {
        "${formatLongDate(startDate)}, ${formatTimeRange(event.start, event.end, zone)}"
    } else {
        "${formatLongDate(startDate)} ${formatTime(event.start, zone)} – " +
            "${formatLongDate(endDate)} ${formatTime(event.end, zone)}"
    }
}

private fun Int.pad2(): String = toString().padStart(2, '0')
