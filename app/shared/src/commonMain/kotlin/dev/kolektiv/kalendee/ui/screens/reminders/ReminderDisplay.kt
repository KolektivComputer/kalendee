package dev.kolektiv.kalendee.ui.screens.reminders

import dev.kolektiv.kalendee.ui.AggregatedNotification
import dev.kolektiv.kalendee.ui.AggregatedReminder
import dev.kolektiv.kalendee.ui.ReminderLimit
import dev.kolektiv.kalendee.ui.ServerUi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** One local calendar day of reminders plus the label shown above the group. */
data class ReminderDay(
    val date: LocalDate,
    val label: String,
    val reminders: List<AggregatedReminder>,
)

private val WeekdayAbbreviations = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MonthAbbreviations =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Locale-independent short date such as `Oct 6`. */
fun shortDate(date: LocalDate): String =
    "${MonthAbbreviations[date.month.ordinal]} ${date.day}"

/** Group header: `Today`, `Tomorrow`, or `Thu, Oct 8` for later dates. */
fun reminderDayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
    else -> "${WeekdayAbbreviations[date.dayOfWeek.isoDayNumber - 1]}, ${shortDate(date)}"
}

/**
 * Buckets [reminders] by local calendar day in [zone], earliest day first and
 * earliest time first inside each day. [today] drives the Today/Tomorrow labels
 * so callers (and tests) do not depend on the wall clock.
 */
fun groupRemindersByDay(
    reminders: List<AggregatedReminder>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate = Clock.System.now().toLocalDateTime(zone).date,
): List<ReminderDay> =
    reminders
        .sortedWith(compareBy({ it.at }, { it.id }))
        .groupBy { it.at.toLocalDateTime(zone).date }
        .map { (date, items) -> ReminderDay(date, reminderDayLabel(date, today), items) }
        .sortedBy { it.date }

/** `HH:mm` in [zone]. */
fun reminderTime(at: Instant, zone: TimeZone): String {
    val local = at.toLocalDateTime(zone)
    return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}

/** How many OS notifications are planned, capped at [ReminderLimit]. */
fun scheduledReminderCount(reminders: List<AggregatedReminder>, limit: Int = ReminderLimit): Int =
    reminders.size.coerceAtMost(limit)

/** Keeps reminders whose server is enabled and still signed in. */
fun filterRemindersForActiveServers(
    reminders: List<AggregatedReminder>,
    servers: List<ServerUi>,
): List<AggregatedReminder> {
    val active = servers
        .filter { it.account.profile.enabled && it.signedIn }
        .map { it.account.profile.id }
        .toSet()
    return reminders.filter { it.serverId in active }
}

fun unreadNotificationCount(notifications: List<AggregatedNotification>): Int =
    notifications.count { !it.notification.read }

private val EventIdQueryKeys = setOf("event", "eventid", "event_id")

/**
 * Extracts an event id from a notification [href] (`/events/{id}`, `/event/{id}`,
 * `?eventId={id}`) without ever opening the URL. Server notifications currently
 * link to web pages, so this returns null for those and the row is only marked
 * read.
 */
fun notificationEventId(href: String?): String? {
    val raw = href?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val path = raw.substringBefore('?').substringBefore('#')
    val segments = path.split('/').filter { it.isNotBlank() }
    if (segments.size >= 2 && segments[segments.size - 2].lowercase() in setOf("events", "event")) {
        val id = segments.last()
        if (id.isNotBlank()) return id
    }
    val query = raw.substringAfter('?', missingDelimiterValue = "")
    for (pair in query.split('&')) {
        val key = pair.substringBefore('=').trim().lowercase()
        if (key in EventIdQueryKeys) {
            val value = pair.substringAfter('=', missingDelimiterValue = "").trim()
            if (value.isNotEmpty()) return value
        }
    }
    return null
}

/**
 * Compact notification age: `Just now`, `15m ago`, `3h ago`, `2d ago`, then a
 * short date. Unparsable timestamps are returned unchanged.
 */
fun notificationTimestamp(
    createdAt: String,
    now: Instant,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val instant = runCatching { Instant.parse(createdAt) }.getOrNull() ?: return createdAt
    val elapsed = now - instant
    return when {
        elapsed.isNegative() -> shortDate(instant.toLocalDateTime(zone).date)
        elapsed < 1.minutes -> "Just now"
        elapsed < 1.hours -> "${elapsed.inWholeMinutes}m ago"
        elapsed < 1.days -> "${elapsed.inWholeHours}h ago"
        elapsed < 7.days -> "${elapsed.inWholeDays}d ago"
        else -> shortDate(instant.toLocalDateTime(zone).date)
    }
}

/** Newest first; notifications with unparsable timestamps sort last. */
fun sortNotificationsNewestFirst(
    notifications: List<AggregatedNotification>,
): List<AggregatedNotification> =
    notifications.sortedWith(
        compareByDescending<AggregatedNotification> {
            runCatching { Instant.parse(it.notification.createdAt) }.getOrNull()
                ?: Instant.DISTANT_PAST
        }.thenByDescending { it.notification.id },
    )
