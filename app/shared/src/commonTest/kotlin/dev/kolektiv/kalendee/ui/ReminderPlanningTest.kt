package dev.kolektiv.kalendee.ui

import dev.kolektiv.kalendee.client.ReminderInstanceResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

class ReminderPlanningTest {

    private fun reminder(
        eventId: String = "event-1",
        calendarId: String = "cal-1",
        calendarName: String = "Work",
        calendarColor: String = "#3b82f6",
        title: String = "Standup",
        start: String = "2026-10-06T09:00:00Z",
        offsetSeconds: Int = 600,
        remindAt: String = "2026-10-06T08:50:00Z",
    ): ReminderInstanceResponse = ReminderInstanceResponse(
        eventId = eventId,
        calendarId = calendarId,
        calendarName = calendarName,
        calendarColor = calendarColor,
        title = title,
        start = start,
        allDay = false,
        offsetSeconds = offsetSeconds,
        remindAt = remindAt,
    )

    @Test
    fun mapsServerFieldsAndBuildsStableId() {
        val planned = planReminders("server-1", "Alpha", listOf(reminder()), TimeZone.UTC)

        assertEquals(1, planned.size)
        val item = planned.single()
        assertEquals("server-1:event-1:600", item.id)
        assertEquals("server-1", item.serverId)
        assertEquals("Alpha", item.serverName)
        assertEquals("cal-1", item.calendarId)
        assertEquals("Work", item.calendarName)
        assertEquals("#3b82f6", item.calendarColor)
        assertEquals("Standup", item.title)
        assertEquals("Work \u2022 2026-10-06 09:00", item.body)
        assertEquals(Instant.parse("2026-10-06T08:50:00Z"), item.at)
        assertEquals("event-1", item.eventId)
        assertEquals(600, item.offsetSeconds)
    }

    @Test
    fun fallsBackToCalendarNameWhenStartCannotBeParsed() {
        val planned = planReminders(
            serverId = "server-1",
            serverName = "Alpha",
            reminders = listOf(reminder(start = "not-a-timestamp")),
            zone = TimeZone.UTC,
        )

        assertEquals("Work", planned.single().body)
    }

    @Test
    fun dropsRemindersWithoutParsableRemindAt() {
        val planned = planReminders(
            serverId = "server-1",
            serverName = "Alpha",
            reminders = listOf(
                reminder(eventId = "ok", remindAt = "2026-10-06T08:50:00Z"),
                reminder(eventId = "broken", remindAt = "later"),
            ),
            zone = TimeZone.UTC,
        )

        assertEquals(listOf("server-1:ok:600"), planned.map { it.id })
    }

    @Test
    fun usesFallbackColorWhenServerSendsBlank() {
        val planned = planReminders(
            serverId = "server-1",
            serverName = "Alpha",
            reminders = listOf(reminder(calendarColor = " ")),
            zone = TimeZone.UTC,
        )

        assertTrue(planned.single().calendarColor.isNotBlank())
    }

    @Test
    fun capKeepsEarliestSixtySorted() {
        val base = Instant.parse("2026-10-06T00:00:00Z")
        val reminders = (0 until 70).map { index ->
            reminder(
                eventId = "event-$index",
                remindAt = (base + index.minutes).toString(),
            )
        }

        val capped = capReminders(planReminders("server-1", "Alpha", reminders, TimeZone.UTC))

        assertEquals(ReminderLimit, capped.size)
        assertEquals("server-1:event-0:600", capped.first().id)
        assertEquals("server-1:event-59:600", capped.last().id)
    }

    @Test
    fun mapsToPendingReminderForTheScheduler() {
        val pending = planReminders("server-1", "Alpha", listOf(reminder()), TimeZone.UTC)
            .single()
            .toPendingReminder()

        assertEquals("server-1:event-1:600", pending.id)
        assertEquals("Standup", pending.title)
        assertEquals("Work \u2022 2026-10-06 09:00", pending.body)
        assertEquals(Instant.parse("2026-10-06T08:50:00Z"), pending.at)
        assertEquals("event-1", pending.eventId)
    }
}
