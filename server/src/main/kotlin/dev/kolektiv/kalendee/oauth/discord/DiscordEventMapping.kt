package dev.kolektiv.kalendee.oauth.discord

import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.oauth.providers.DiscordRecurrenceRule
import dev.kolektiv.kalendee.oauth.providers.DiscordScheduledEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * A Discord scheduled event prepared for storage in a mirrored Kalendee calendar.
 * Recurring Discord events are materialized into one entry per occurrence.
 */
data class ImportedCalendarEvent(
    val uid: String,
    val title: String,
    val description: String?,
    val location: String?,
    val start: Instant,
    val end: Instant,
    val status: EventStatus,
    val allDay: Boolean = false,
)

const val UntitledEventTitle: String = "(untitled event)"

const val UnsupportedRecurrenceNote: String =
    "This event repeats on Discord, but its recurrence rule could not be expanded automatically."

const val DiscordOccurrenceWindowPastDays: Int = 30
const val DiscordOccurrenceWindowFutureDays: Int = 180
const val DiscordOccurrenceCap: Int = 400

const val DiscordFrequencyYearly: Int = 0
const val DiscordFrequencyMonthly: Int = 1
const val DiscordFrequencyWeekly: Int = 2
const val DiscordFrequencyDaily: Int = 3

fun discordEventUid(guildId: String, eventId: String): String = "discord:$guildId:$eventId"

fun discordOccurrenceUid(guildId: String, eventId: String, occurrenceStart: Instant): String =
    "${discordEventUid(guildId, eventId)}:$occurrenceStart"

fun discordOccurrenceUidPrefix(guildId: String, eventId: String): String =
    "${discordEventUid(guildId, eventId)}:"

fun discordBaseEventId(guildId: String, uid: String): String? {
    val prefix = "discord:$guildId:"
    if (!uid.startsWith(prefix)) return null
    return uid.removePrefix(prefix).substringBefore(':').takeIf { it.isNotEmpty() }
}

/**
 * Maps one Discord scheduled event to the local events that should exist for it.
 * Single events produce one entry; recurring events produce one entry per
 * occurrence inside the materialization window. Unsupported recurrence rules
 * never fail the sync: the master event is emitted once with an explanatory note.
 */
fun mapDiscordEvent(
    event: DiscordScheduledEvent,
    guildName: String?,
    now: Instant,
): List<ImportedCalendarEvent> {
    val scheduledStart = parseDiscordInstant(event.scheduledStartTime) ?: return emptyList()
    val scheduledEnd = parseDiscordInstant(event.scheduledEndTime)
    val duration = (scheduledEnd?.minus(scheduledStart))?.takeIf { it > Duration.ZERO } ?: 1.hours
    val baseUid = discordEventUid(event.guildId, event.id)
    val rule = event.recurrenceRule
    if (rule == null) {
        return listOf(importedEvent(event, guildName, baseUid, scheduledStart, duration, note = null))
    }
    val ruleStart = parseDiscordInstant(rule.start)
    val seriesStart = ruleStart ?: scheduledStart
    val occurrences = expandRule(rule, seriesStart, now)
    if (occurrences == null) {
        val fallbackStart = when {
            ruleStart == null -> scheduledStart
            ruleStart > scheduledStart -> ruleStart
            else -> scheduledStart
        }
        return listOf(
            importedEvent(event, guildName, baseUid, fallbackStart, duration, UnsupportedRecurrenceNote),
        )
    }
    return occurrences.map { occurrence ->
        val uid = discordOccurrenceUid(event.guildId, event.id, occurrence)
        importedEvent(event, guildName, uid, occurrence, duration, note = null)
    }
}

private fun importedEvent(
    event: DiscordScheduledEvent,
    guildName: String?,
    uid: String,
    start: Instant,
    duration: Duration,
    note: String?,
): ImportedCalendarEvent = ImportedCalendarEvent(
    uid = uid,
    title = event.name.takeIf { it.isNotBlank() } ?: UntitledEventTitle,
    description = describe(event, guildName, note),
    location = if (event.entityType == DiscordScheduledEvent.ENTITY_TYPE_EXTERNAL) {
        event.entityMetadata?.location?.takeIf { it.isNotBlank() }
    } else {
        null
    },
    start = start,
    end = start + duration,
    status = if (event.status == DiscordScheduledEvent.STATUS_CANCELED) {
        EventStatus.CANCELLED
    } else {
        EventStatus.CONFIRMED
    },
    allDay = false,
)

private fun describe(event: DiscordScheduledEvent, guildName: String?, note: String?): String {
    val sections = mutableListOf<String>()
    event.description?.trim()?.takeIf { it.isNotEmpty() }?.let(sections::add)
    val details = buildString {
        append("Discord event: https://discord.com/events/")
        append(event.guildId)
        append('/')
        append(event.id)
        guildName?.takeIf { it.isNotBlank() }?.let {
            append('\n')
            append("Guild: ")
            append(it)
        }
        event.userCount?.let {
            append('\n')
            append(it)
            append(" interested")
        }
    }
    sections += details
    note?.let(sections::add)
    return sections.joinToString("\n\n")
}

private fun parseDiscordInstant(raw: String?): Instant? =
    raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun expandRule(
    rule: DiscordRecurrenceRule,
    seriesStart: Instant,
    now: Instant,
): List<Instant>? {
    val frequency = rule.frequency ?: return null
    val interval = rule.interval ?: 1
    if (interval < 1 || interval > MaxInterval) return null
    val ruleEnd = if (rule.end == null) null else parseDiscordInstant(rule.end) ?: return null
    if (rule.count != null && rule.count < 1) return null
    val zone = TimeZone.UTC
    val windowStart = maxOf(seriesStart, now - DiscordOccurrenceWindowPastDays.days)
    val windowEnd = now + DiscordOccurrenceWindowFutureDays.days
    val starts = when (frequency) {
        DiscordFrequencyDaily -> dailyStarts(rule, seriesStart, interval, zone, windowEnd)
        DiscordFrequencyWeekly -> weeklyStarts(rule, seriesStart, interval, zone, windowEnd)
        DiscordFrequencyMonthly -> monthlyStarts(rule, seriesStart, interval, zone, windowEnd)
        DiscordFrequencyYearly -> yearlyStarts(rule, seriesStart, interval, zone, windowEnd)
        else -> null
    } ?: return null
    val byCount = if (rule.count != null) starts.take(rule.count) else starts
    val byEnd = if (ruleEnd != null) byCount.takeWhile { it <= ruleEnd } else byCount
    return byEnd.filter { it >= windowStart && it <= windowEnd }.take(DiscordOccurrenceCap).toList()
}

private fun dailyStarts(
    rule: DiscordRecurrenceRule,
    seriesStart: Instant,
    interval: Int,
    zone: TimeZone,
    windowEnd: Instant,
): Sequence<Instant>? {
    if (!rule.byNWeekday.isNullOrEmpty() || !rule.byMonth.isNullOrEmpty() ||
        !rule.byMonthDay.isNullOrEmpty() || !rule.byYearDay.isNullOrEmpty()
    ) {
        return null
    }
    val weekdays = rule.byWeekday?.takeIf { it.isNotEmpty() }?.toSet()
    if (weekdays != null && weekdays !in DailyWeekdaySets) return null
    return sequence {
        var cursor = seriesStart
        var steps = 0
        while (cursor <= windowEnd && steps < MaxIterations) {
            if (weekdays == null || cursor.discordWeekday(zone) in weekdays) yield(cursor)
            cursor = cursor.advanceDays(interval, zone)
            steps++
        }
    }
}

private fun weeklyStarts(
    rule: DiscordRecurrenceRule,
    seriesStart: Instant,
    interval: Int,
    zone: TimeZone,
    windowEnd: Instant,
): Sequence<Instant>? {
    if (interval > MaxWeeklyInterval) return null
    if (!rule.byNWeekday.isNullOrEmpty() || !rule.byMonth.isNullOrEmpty() ||
        !rule.byMonthDay.isNullOrEmpty() || !rule.byYearDay.isNullOrEmpty()
    ) {
        return null
    }
    val weekdays = rule.byWeekday?.takeIf { it.isNotEmpty() }
    if (weekdays != null && (weekdays.size != 1 || weekdays.single() !in 0..6)) return null
    val target = weekdays?.single()?.let(::discordDayOfWeek)
    return sequence {
        var cursor = seriesStart.firstOnOrAfter(target, zone)
        var steps = 0
        while (cursor <= windowEnd && steps < MaxIterations) {
            yield(cursor)
            cursor = cursor.advanceDays(7 * interval, zone)
            steps++
        }
    }
}

private fun monthlyStarts(
    rule: DiscordRecurrenceRule,
    seriesStart: Instant,
    interval: Int,
    zone: TimeZone,
    windowEnd: Instant,
): Sequence<Instant>? {
    if (!rule.byWeekday.isNullOrEmpty() || !rule.byMonth.isNullOrEmpty() ||
        !rule.byMonthDay.isNullOrEmpty() || !rule.byYearDay.isNullOrEmpty()
    ) {
        return null
    }
    val entry = rule.byNWeekday?.takeIf { it.size == 1 }?.single() ?: return null
    if (entry.n !in 1..5 || entry.day !in 0..6) return null
    val localStart = seriesStart.toLocalDateTime(zone)
    return sequence {
        var month = LocalDate(localStart.year, localStart.monthNumber, 1)
        var steps = 0
        while (monthStart(month, zone) <= windowEnd && steps < MaxIterations) {
            val date = nthWeekdayOfMonth(month.year, month.monthNumber, entry.n, entry.day)
            if (date != null) {
                val occurrence = date.atTime(localStart.time).toInstant(zone)
                if (occurrence >= seriesStart && occurrence <= windowEnd) yield(occurrence)
            }
            month = month.plus(interval, DateTimeUnit.MONTH)
            steps++
        }
    }
}

private fun yearlyStarts(
    rule: DiscordRecurrenceRule,
    seriesStart: Instant,
    interval: Int,
    zone: TimeZone,
    windowEnd: Instant,
): Sequence<Instant>? {
    if (!rule.byWeekday.isNullOrEmpty() || !rule.byNWeekday.isNullOrEmpty() ||
        !rule.byYearDay.isNullOrEmpty()
    ) {
        return null
    }
    val month = rule.byMonth?.takeIf { it.size == 1 }?.single() ?: return null
    if (month !in 1..12) return null
    val day = rule.byMonthDay?.takeIf { it.size == 1 }?.single() ?: return null
    if (day !in 1..31) return null
    val localStart = seriesStart.toLocalDateTime(zone)
    return sequence {
        var year = localStart.year
        var steps = 0
        while (yearStart(year, zone) <= windowEnd && steps < MaxIterations) {
            val date = runCatching { LocalDate(year, month, day) }.getOrNull()
            if (date != null) {
                val occurrence = date.atTime(localStart.time).toInstant(zone)
                if (occurrence >= seriesStart && occurrence <= windowEnd) yield(occurrence)
            }
            year += interval
            steps++
        }
    }
}

private fun nthWeekdayOfMonth(year: Int, month: Int, n: Int, discordDay: Int): LocalDate? {
    val target = discordDayOfWeek(discordDay)
    val first = LocalDate(year, month, 1)
    val last = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
    if (n == LastWeekdayOfMonth) {
        var date = last
        while (date.dayOfWeek != target) date = date.minus(1, DateTimeUnit.DAY)
        return date
    }
    val offset = (target.isoDayNumber - first.dayOfWeek.isoDayNumber + DaysPerWeek) % DaysPerWeek
    val dayOfMonth = 1 + offset + (n - 1) * DaysPerWeek
    return if (dayOfMonth > last.dayOfMonth) null else LocalDate(year, month, dayOfMonth)
}

private fun Instant.advanceDays(days: Int, zone: TimeZone): Instant {
    val local = toLocalDateTime(zone)
    return local.date.plus(days, DateTimeUnit.DAY).atTime(local.time).toInstant(zone)
}

private fun Instant.firstOnOrAfter(target: DayOfWeek?, zone: TimeZone): Instant {
    if (target == null) return this
    var cursor = this
    var steps = 0
    while (cursor.toLocalDateTime(zone).dayOfWeek != target && steps < DaysPerWeek) {
        cursor = cursor.advanceDays(1, zone)
        steps++
    }
    return cursor
}

private fun Instant.discordWeekday(zone: TimeZone): Int =
    toLocalDateTime(zone).dayOfWeek.isoDayNumber - 1

private fun discordDayOfWeek(day: Int): DayOfWeek = when (day) {
    0 -> DayOfWeek.MONDAY
    1 -> DayOfWeek.TUESDAY
    2 -> DayOfWeek.WEDNESDAY
    3 -> DayOfWeek.THURSDAY
    4 -> DayOfWeek.FRIDAY
    5 -> DayOfWeek.SATURDAY
    else -> DayOfWeek.SUNDAY
}

private fun monthStart(month: LocalDate, zone: TimeZone): Instant =
    month.atTime(LocalTime(0, 0)).toInstant(zone)

private fun yearStart(year: Int, zone: TimeZone): Instant =
    LocalDate(year, 1, 1).atTime(LocalTime(0, 0)).toInstant(zone)

private const val MaxInterval = 99
private const val MaxWeeklyInterval = 2
private const val LastWeekdayOfMonth = 5
private const val DaysPerWeek = 7
private const val MaxIterations = 100_000

private val DailyWeekdaySets: Set<Set<Int>> = setOf(
    setOf(0, 1, 2, 3, 4),
    setOf(1, 2, 3, 4, 5),
    setOf(0, 1, 2, 3, 6),
    setOf(4, 5),
    setOf(5, 6),
    setOf(0, 6),
)
