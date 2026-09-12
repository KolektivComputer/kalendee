package dev.kolektiv.kalendee.calendar

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

@Serializable
enum class RecurrenceFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
}

@Serializable
data class Recurrence(
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val until: Instant? = null,
    val count: Int? = null,
)

fun Recurrence.validated(): Recurrence {
    if (interval < 1) {
        throw CalendarException.Invalid("interval must be at least 1")
    }
    if (interval > 99) {
        throw CalendarException.Invalid("interval must be at most 99")
    }
    if (count != null && count < 1) {
        throw CalendarException.Invalid("count must be at least 1")
    }
    if (count != null && count > 999) {
        throw CalendarException.Invalid("count must be at most 999")
    }
    return copy(until = until)
}

fun Event.occurrencesIn(range: InstantRange, zone: TimeZone): List<Event> {
    val rule = recurrence ?: return if (range.overlaps(start, end)) listOf(this) else emptyList()
    val duration = end - start
    if (duration <= Duration.ZERO) return emptyList()
    val occurrences = ArrayList<Event>()
    var cursor = start
    var index = 0
    var steps = 0
    while (cursor < range.end && steps < 400) {
        if (rule.count != null && index >= rule.count) break
        if (rule.until != null && cursor >= rule.until) break
        val occurrenceEnd = cursor + duration
        if (range.overlaps(cursor, occurrenceEnd)) {
            occurrences.add(copy(start = cursor, end = occurrenceEnd))
        }
        val next = cursor.advance(rule.frequency, rule.interval, zone)
        if (next <= cursor) break
        cursor = next
        index += 1
        steps += 1
    }
    return occurrences
}

data class SeriesSplit(
    val occurrenceIndex: Int,
    val truncated: Recurrence?,
    val following: Recurrence,
)

fun Recurrence.splitAt(
    seriesStart: Instant,
    occurrenceStart: Instant,
    zone: TimeZone,
): SeriesSplit? {
    if (occurrenceStart < seriesStart) return null
    if (occurrenceStart == seriesStart) {
        return SeriesSplit(occurrenceIndex = 0, truncated = null, following = this)
    }
    var cursor = seriesStart
    var index = 0
    while (index < 1000) {
        val next = cursor.advance(frequency, interval, zone)
        if (next <= cursor) return null
        val nextIndex = index + 1
        if (count != null && nextIndex >= count) return null
        if (until != null && next >= until) return null
        cursor = next
        index = nextIndex
        if (cursor == occurrenceStart) {
            val truncated = if (count != null) {
                copy(count = index)
            } else {
                copy(until = occurrenceStart)
            }
            val following = if (count != null) copy(count = count - index) else this
            return SeriesSplit(occurrenceIndex = index, truncated = truncated, following = following)
        }
        if (cursor > occurrenceStart) return null
    }
    return null
}

internal fun Instant.advance(
    frequency: RecurrenceFrequency,
    interval: Int,
    zone: TimeZone,
): Instant {
    val local = toLocalDateTime(zone)
    val nextDate = when (frequency) {
        RecurrenceFrequency.DAILY -> local.date.plus(interval, DateTimeUnit.DAY)
        RecurrenceFrequency.WEEKLY -> local.date.plus(interval, DateTimeUnit.WEEK)
        RecurrenceFrequency.MONTHLY -> local.date.plus(interval, DateTimeUnit.MONTH)
        RecurrenceFrequency.YEARLY -> local.date.plus(interval, DateTimeUnit.YEAR)
    }
    return nextDate.atTime(local.time).toInstant(zone)
}
