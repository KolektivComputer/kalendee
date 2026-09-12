package dev.kolektiv.kalendee.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class WeekWindowTest {
    private val wednesday = Instant.parse("2026-09-09T15:00:00Z")

    @Test
    fun snapsToMondayInTheRequestedZone() {
        val window = WeekWindow.of(week = "2026-09-11", timeZoneId = "UTC", now = wednesday)
        assertEquals(LocalDate.parse("2026-09-07"), window.weekStart)
        assertEquals(TimeZone.UTC, window.timeZone)
        assertEquals(Instant.parse("2026-09-07T00:00:00Z"), window.range.start)
        assertEquals(Instant.parse("2026-09-14T00:00:00Z"), window.range.end)
        assertEquals(LocalDate.parse("2026-08-31"), window.previous)
        assertEquals(LocalDate.parse("2026-09-14"), window.next)
    }

    @Test
    fun defaultsToTheCurrentWeek() {
        val window = WeekWindow.of(week = null, timeZoneId = "UTC", now = wednesday)
        assertEquals(LocalDate.parse("2026-09-07"), window.weekStart)
    }

    @Test
    fun weekBoundsFollowTheRequestedZone() {
        val window = WeekWindow.of(week = null, timeZoneId = "America/New_York", now = wednesday)
        assertEquals(LocalDate.parse("2026-09-07"), window.weekStart)
        assertEquals(TimeZone.of("America/New_York"), window.timeZone)
        assertEquals(Instant.parse("2026-09-07T04:00:00Z"), window.range.start)
        assertEquals(Instant.parse("2026-09-14T04:00:00Z"), window.range.end)
    }

    @Test
    fun currentWeekUsesLocalDateNotUtc() {
        val stillSundayInNewYork = Instant.parse("2026-09-07T02:00:00Z")
        val window = WeekWindow.of(
            week = null,
            timeZoneId = "America/New_York",
            now = stillSundayInNewYork,
        )
        assertEquals(LocalDate.parse("2026-08-31"), window.weekStart)
    }

    @Test
    fun rejectsUnknownTimeZonesAndDates() {
        assertFailsWith<CalendarException.Invalid> {
            WeekWindow.of(week = "2026-09-07", timeZoneId = "Not/AZone", now = wednesday)
        }
        assertFailsWith<CalendarException.Invalid> {
            WeekWindow.of(week = "09-07-2026", timeZoneId = "UTC", now = wednesday)
        }
    }
}
