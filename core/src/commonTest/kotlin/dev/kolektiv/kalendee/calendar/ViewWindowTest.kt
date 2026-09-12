package dev.kolektiv.kalendee.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class ViewWindowTest {
    private val wednesday = Instant.parse("2026-09-09T15:00:00Z")

    @Test
    fun weekViewMatchesWeekWindowBounds() {
        val window = ViewWindow.of(
            view = "week",
            date = "2026-09-11",
            week = null,
            timeZoneId = "UTC",
            now = wednesday,
        )
        assertEquals(CalendarView.Week, window.view)
        assertEquals(LocalDate.parse("2026-09-07"), window.weekStart)
        assertEquals(LocalDate.parse("2026-09-07"), window.gridStart)
        assertEquals(LocalDate.parse("2026-09-14"), window.gridEnd)
        assertEquals(Instant.parse("2026-09-07T00:00:00Z"), window.range.start)
        assertEquals(Instant.parse("2026-09-14T00:00:00Z"), window.range.end)
        assertEquals(LocalDate.parse("2026-08-31"), window.previous)
        assertEquals(LocalDate.parse("2026-09-14"), window.next)
        assertEquals("Sep 7–13", window.label)
    }

    @Test
    fun dayViewCoversOneLocalDay() {
        val window = ViewWindow.of(
            view = "day",
            date = "2026-09-11",
            week = null,
            timeZoneId = "America/New_York",
            now = wednesday,
        )
        assertEquals(CalendarView.Day, window.view)
        assertEquals(LocalDate.parse("2026-09-11"), window.date)
        assertEquals(LocalDate.parse("2026-09-11"), window.gridStart)
        assertEquals(LocalDate.parse("2026-09-12"), window.gridEnd)
        assertEquals(Instant.parse("2026-09-11T04:00:00Z"), window.range.start)
        assertEquals(Instant.parse("2026-09-12T04:00:00Z"), window.range.end)
        assertEquals(LocalDate.parse("2026-09-10"), window.previous)
        assertEquals(LocalDate.parse("2026-09-12"), window.next)
        assertEquals("Sep 11", window.label)
    }

    @Test
    fun monthViewPadsToWeekBounds() {
        val window = ViewWindow.of(
            view = "month",
            date = "2026-09-11",
            week = null,
            timeZoneId = "UTC",
            now = wednesday,
        )
        assertEquals(CalendarView.Month, window.view)
        assertEquals(LocalDate.parse("2026-09-01"), window.date)
        assertEquals(LocalDate.parse("2026-08-31"), window.gridStart)
        assertEquals(LocalDate.parse("2026-10-05"), window.gridEnd)
        assertEquals(LocalDate.parse("2026-08-01"), window.previous)
        assertEquals(LocalDate.parse("2026-10-01"), window.next)
        assertEquals("September 2026", window.label)
    }

    @Test
    fun weekQueryAliasStillWorks() {
        val window = ViewWindow.of(
            view = null,
            date = null,
            week = "2026-09-07",
            timeZoneId = "UTC",
            now = wednesday,
        )
        assertEquals(CalendarView.Week, window.view)
        assertEquals(LocalDate.parse("2026-09-07"), window.weekStart)
    }

    @Test
    fun rejectsUnknownTimeZonesAndDates() {
        assertFailsWith<CalendarException.Invalid> {
            ViewWindow.of("week", "2026-09-07", null, "Not/AZone", wednesday)
        }
        assertFailsWith<CalendarException.Invalid> {
            ViewWindow.of("week", "09-07-2026", null, "UTC", wednesday)
        }
    }
}
