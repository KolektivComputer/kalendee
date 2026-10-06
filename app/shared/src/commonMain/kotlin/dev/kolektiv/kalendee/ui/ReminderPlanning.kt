package dev.kolektiv.kalendee.ui

import dev.kolektiv.kalendee.calendar.colorFor
import dev.kolektiv.kalendee.client.ReminderInstanceResponse
import dev.kolektiv.kalendee.notify.PendingReminder
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** How many reminders the app schedules at most (earliest first). */
const val ReminderLimit: Int = 60

/** Stable scheduler id: server, event and offset uniquely identify a reminder. */
fun reminderId(serverId: String, eventId: String, offsetSeconds: Int): String =
    "$serverId:$eventId:$offsetSeconds"

/**
 * Notification body: `calendarName • YYYY-MM-DD HH:mm` in [zone], or just the
 * calendar name when the event start cannot be parsed.
 */
fun reminderBody(calendarName: String, start: String?, zone: TimeZone): String {
    val instant = start?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return calendarName
    return "$calendarName \u2022 ${formatLocalTimestamp(instant, zone)}"
}

fun formatLocalTimestamp(instant: Instant, zone: TimeZone): String {
    val local = instant.toLocalDateTime(zone)
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "${local.date} $hour:$minute"
}

/**
 * Maps server reminder payloads to [AggregatedReminder]s. Reminders whose
 * `remindAt` cannot be parsed are dropped (they cannot be scheduled).
 */
fun planReminders(
    serverId: String,
    serverName: String,
    reminders: List<ReminderInstanceResponse>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<AggregatedReminder> = reminders.mapNotNull { reminder ->
    val at = runCatching { Instant.parse(reminder.remindAt) }.getOrNull() ?: return@mapNotNull null
    AggregatedReminder(
        id = reminderId(serverId, reminder.eventId, reminder.offsetSeconds),
        serverId = serverId,
        serverName = serverName,
        calendarId = reminder.calendarId,
        calendarName = reminder.calendarName,
        calendarColor = reminder.calendarColor.ifBlank { colorFor(reminder.calendarId) },
        title = reminder.title,
        body = reminderBody(reminder.calendarName, reminder.start, zone),
        at = at,
        eventId = reminder.eventId,
        offsetSeconds = reminder.offsetSeconds,
    )
}

/** Keeps the [limit] earliest reminders, sorted by time then id for stability. */
fun capReminders(reminders: List<AggregatedReminder>, limit: Int = ReminderLimit): List<AggregatedReminder> =
    reminders.sortedWith(compareBy({ it.at }, { it.id })).take(limit)

fun AggregatedReminder.toPendingReminder(): PendingReminder = PendingReminder(
    id = id,
    title = title,
    body = body,
    at = at,
    eventId = eventId,
)
