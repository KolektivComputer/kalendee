package dev.kolektiv.kalendee.ui

import dev.kolektiv.kalendee.client.NotificationOut
import dev.kolektiv.kalendee.client.ServerAccount
import dev.kolektiv.kalendee.client.ServerProfile
import dev.kolektiv.kalendee.ui.screens.reminders.filterRemindersForActiveServers
import dev.kolektiv.kalendee.ui.screens.reminders.groupRemindersByDay
import dev.kolektiv.kalendee.ui.screens.reminders.notificationEventId
import dev.kolektiv.kalendee.ui.screens.reminders.notificationTimestamp
import dev.kolektiv.kalendee.ui.screens.reminders.reminderDayLabel
import dev.kolektiv.kalendee.ui.screens.reminders.reminderTime
import dev.kolektiv.kalendee.ui.screens.reminders.scheduledReminderCount
import dev.kolektiv.kalendee.ui.screens.reminders.sortNotificationsNewestFirst
import dev.kolektiv.kalendee.ui.screens.reminders.unreadNotificationCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class RemindersScreenTest {

    private fun reminder(
        id: String = "r1",
        serverId: String = "server-1",
        at: String = "2026-10-06T08:00:00Z",
        eventId: String = "event-1",
    ): AggregatedReminder = AggregatedReminder(
        id = id,
        serverId = serverId,
        serverName = "Alpha",
        calendarId = "cal-1",
        calendarName = "Work",
        calendarColor = "primary",
        title = "Standup",
        body = "Work",
        at = Instant.parse(at),
        eventId = eventId,
        offsetSeconds = 600,
    )

    private fun server(id: String, enabled: Boolean = true, token: String? = "token"): ServerUi =
        ServerUi(
            account = ServerAccount(
                profile = ServerProfile(
                    id = id,
                    name = id,
                    baseUrl = "https://$id.example",
                    enabled = enabled,
                ),
                token = token,
            ),
        )

    private fun notification(
        id: String = "n1",
        serverId: String = "server-1",
        read: Boolean = false,
        createdAt: String = "2026-10-06T08:00:00Z",
        href: String? = null,
    ): AggregatedNotification = AggregatedNotification(
        serverId = serverId,
        serverName = "Alpha",
        notification = NotificationOut(
            id = id,
            kind = "event.invite",
            title = "Invite",
            body = null,
            href = href,
            read = read,
            createdAt = createdAt,
        ),
    )

    @Test
    fun groupsByLocalDayWithTodayTomorrowAndWeekdayLabels() {
        val reminders = listOf(
            reminder(id = "b", at = "2026-10-06T10:00:00Z"),
            reminder(id = "a", at = "2026-10-06T08:00:00Z"),
            reminder(id = "c", at = "2026-10-07T09:00:00Z"),
            reminder(id = "d", at = "2026-10-08T09:00:00Z"),
        )

        val days = groupRemindersByDay(reminders, TimeZone.UTC, LocalDate(2026, 10, 6))

        assertEquals(listOf("Today", "Tomorrow", "Thu, Oct 8"), days.map { it.label })
        assertEquals(listOf(LocalDate(2026, 10, 6), LocalDate(2026, 10, 7), LocalDate(2026, 10, 8)), days.map { it.date })
        assertEquals(listOf("a", "b"), days[0].reminders.map { it.id })
        assertEquals(listOf("c"), days[1].reminders.map { it.id })
        assertEquals(listOf("d"), days[2].reminders.map { it.id })
    }

    @Test
    fun groupingUsesTheProvidedTimeZone() {
        val reminders = listOf(reminder(at = "2026-10-06T23:30:00Z"))

        val days = groupRemindersByDay(reminders, TimeZone.of("Europe/Berlin"), LocalDate(2026, 10, 6))

        assertEquals(LocalDate(2026, 10, 7), days.single().date)
        assertEquals("Tomorrow", days.single().label)
    }

    @Test
    fun formatsReminderTimeInTheLocalZone() {
        assertEquals("10:30", reminderTime(Instant.parse("2026-10-06T08:30:00Z"), TimeZone.of("Europe/Berlin")))
        assertEquals("08:30", reminderTime(Instant.parse("2026-10-06T08:30:00Z"), TimeZone.UTC))
    }

    @Test
    fun dayLabelsFallBackToWeekdayAndShortDate() {
        assertEquals("Today", reminderDayLabel(LocalDate(2026, 10, 6), LocalDate(2026, 10, 6)))
        assertEquals("Tomorrow", reminderDayLabel(LocalDate(2026, 10, 7), LocalDate(2026, 10, 6)))
        assertEquals("Mon, Oct 12", reminderDayLabel(LocalDate(2026, 10, 12), LocalDate(2026, 10, 6)))
    }

    @Test
    fun scheduledCountIsCappedAtTheLimit() {
        val many = (0 until 70).map { reminder(id = "r$it") }

        assertEquals(ReminderLimit, scheduledReminderCount(many))
        assertEquals(0, scheduledReminderCount(emptyList()))
        assertEquals(3, scheduledReminderCount(many.take(3)))
    }

    @Test
    fun filterKeepsOnlyEnabledSignedInServers() {
        val servers = listOf(
            server("server-1"),
            server("server-2", enabled = false),
            server("server-3", token = null),
        )
        val reminders = listOf(
            reminder(id = "keep", serverId = "server-1"),
            reminder(id = "disabled", serverId = "server-2"),
            reminder(id = "signedOut", serverId = "server-3"),
        )

        val filtered = filterRemindersForActiveServers(reminders, servers)

        assertEquals(listOf("keep"), filtered.map { it.id })
    }

    @Test
    fun countsUnreadNotifications() {
        val items = listOf(
            notification(id = "a", read = false),
            notification(id = "b", read = true),
            notification(id = "c", read = false),
        )

        assertEquals(2, unreadNotificationCount(items))
        assertEquals(0, unreadNotificationCount(emptyList()))
    }

    @Test
    fun extractsEventIdFromNotificationHref() {
        assertEquals("event-9", notificationEventId("/events/event-9"))
        assertEquals("event-9", notificationEventId("https://cal.example/events/event-9?x=1"))
        assertEquals("42", notificationEventId("/?eventId=42"))
        assertEquals("abc", notificationEventId("/?event=abc&x=1"))
        assertNull(notificationEventId("/?date=2026-10-06"))
        assertNull(notificationEventId("/events"))
        assertNull(notificationEventId(""))
        assertNull(notificationEventId(null))
    }

    @Test
    fun formatsRelativeNotificationTimestamps() {
        val now = Instant.parse("2026-10-06T12:00:00Z")
        val zone = TimeZone.UTC

        assertEquals("Just now", notificationTimestamp("2026-10-06T11:59:30Z", now, zone))
        assertEquals("15m ago", notificationTimestamp("2026-10-06T11:45:00Z", now, zone))
        assertEquals("3h ago", notificationTimestamp("2026-10-06T09:00:00Z", now, zone))
        assertEquals("2d ago", notificationTimestamp("2026-10-04T12:00:00Z", now, zone))
        assertEquals("Sep 29", notificationTimestamp("2026-09-29T12:00:00Z", now, zone))
        assertEquals("not-a-date", notificationTimestamp("not-a-date", now, zone))
    }

    @Test
    fun sortsNotificationsNewestFirstWithBrokenDatesLast() {
        val items = listOf(
            notification(id = "old", createdAt = "2026-10-01T08:00:00Z"),
            notification(id = "new", createdAt = "2026-10-06T08:00:00Z"),
            notification(id = "broken", createdAt = "whenever"),
        )

        assertEquals(
            listOf("new", "old", "broken"),
            sortNotificationsNewestFirst(items).map { it.notification.id },
        )
    }
}
