package dev.kolektiv.kalendee.ui

import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.ui.format.allDayEndDate
import dev.kolektiv.kalendee.ui.format.formatClock
import dev.kolektiv.kalendee.ui.format.formatEventWhen
import dev.kolektiv.kalendee.ui.format.formatLongDate
import dev.kolektiv.kalendee.ui.format.formatTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

private val Utc = TimeZone.UTC

private fun formatEvent(
    start: Instant,
    end: Instant,
    allDay: Boolean = false,
): Event = Event(
    id = EventId("event-1"),
    calendarId = CalendarId("cal-1"),
    title = "Event",
    start = start,
    end = end,
    allDay = allDay,
    status = EventStatus.CONFIRMED,
    etag = "etag-1",
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
)

class DateTimeFormatTest {

    @Test
    fun formatsClockMinutes() {
        assertEquals("00:00", formatClock(0))
        assertEquals("09:30", formatClock(570))
        assertEquals("23:59", formatClock(1439))
        assertEquals("00:00", formatClock(1440))
    }

    @Test
    fun formatsTimes() {
        assertEquals("09:05", formatTime(LocalTime(9, 5)))
        assertEquals(
            "09:05",
            formatTime(Instant.parse("2026-10-06T09:05:00Z"), Utc),
        )
    }

    @Test
    fun formatsLongDates() {
        assertEquals("Tue, Oct 6, 2026", formatLongDate(LocalDate(2026, 10, 6)))
        assertEquals("Sun, Feb 1, 2026", formatLongDate(LocalDate(2026, 2, 1)))
    }

    @Test
    fun allDayEndDateExcludesTheExclusiveMidnight() {
        assertEquals(
            LocalDate(2026, 10, 6),
            allDayEndDate(Instant.parse("2026-10-07T00:00:00Z"), Utc),
        )
        assertEquals(
            LocalDate(2026, 10, 7),
            allDayEndDate(Instant.parse("2026-10-07T10:00:00Z"), Utc),
        )
    }

    @Test
    fun formatsTimedEventWhen() {
        val event = formatEvent(
            start = Instant.parse("2026-10-06T09:00:00Z"),
            end = Instant.parse("2026-10-06T10:00:00Z"),
        )

        assertEquals("Tue, Oct 6, 2026, 09:00 – 10:00", formatEventWhen(event, Utc))
    }

    @Test
    fun formatsTimedEventCrossingMidnight() {
        val event = formatEvent(
            start = Instant.parse("2026-10-06T22:00:00Z"),
            end = Instant.parse("2026-10-07T01:00:00Z"),
        )

        assertEquals(
            "Tue, Oct 6, 2026 22:00 – Wed, Oct 7, 2026 01:00",
            formatEventWhen(event, Utc),
        )
    }

    @Test
    fun formatsSingleDayAllDayEvent() {
        val event = formatEvent(
            start = Instant.parse("2026-10-06T00:00:00Z"),
            end = Instant.parse("2026-10-07T00:00:00Z"),
            allDay = true,
        )

        assertEquals("Tue, Oct 6, 2026 (all day)", formatEventWhen(event, Utc))
    }

    @Test
    fun formatsMultiDayAllDayEvent() {
        val event = formatEvent(
            start = Instant.parse("2026-10-06T00:00:00Z"),
            end = Instant.parse("2026-10-08T00:00:00Z"),
            allDay = true,
        )

        assertEquals(
            "Tue, Oct 6, 2026 – Wed, Oct 7, 2026 (all day)",
            formatEventWhen(event, Utc),
        )
    }

    @Test
    fun formatDateTimeKeepsDateAndTimeTogether() {
        assertEquals(
            "Tue, Oct 6, 2026, 09:30",
            dev.kolektiv.kalendee.ui.format.formatDateTime(
                LocalDateTime(LocalDate(2026, 10, 6), LocalTime(9, 30)),
            ),
        )
    }
}
