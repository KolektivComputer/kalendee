package dev.kolektiv.kalendee.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class ValidationTest {
    private val start = Instant.parse("2026-03-01T10:00:00Z")
    private val end = Instant.parse("2026-03-01T11:00:00Z")

    @Test
    fun createCalendarRejectsBlankDisplayName() {
        val error = assertFailsWith<CalendarException.Invalid> {
            CreateCalendar(displayName = "  ").validated()
        }
        assertEquals("displayName must not be blank", error.message)
    }

    @Test
    fun createCalendarTrimsDisplayNameAndDefaultsUtc() {
        val validated = CreateCalendar(displayName = "  Work  ").validated()
        assertEquals("Work", validated.displayName)
        assertEquals("UTC", validated.timeZone)
        assertEquals(null, validated.color)
    }

    @Test
    fun createCalendarNormalizesColor() {
        val validated = CreateCalendar(displayName = "Work", color = "  #3D5A80  ").validated()
        assertEquals("#3d5a80", validated.color)
    }

    @Test
    fun invalidColorIsRejected() {
        val error = assertFailsWith<CalendarException.Invalid> {
            CreateCalendar(displayName = "Work", color = "blue").validated()
        }
        assertEquals("color must be a daisyUI color name or #rrggbb hex value", error.message)
    }

    @Test
    fun createCalendarAcceptsDaisyUiColorName() {
        val validated = CreateCalendar(displayName = "Work", color = " Primary ").validated()
        assertEquals("primary", validated.color)
    }

    @Test
    fun createEventRejectsBlankTitle() {
        val error = assertFailsWith<CalendarException.Invalid> {
            CreateEvent(title = " ", start = start, end = end).validated()
        }
        assertEquals("title must not be blank", error.message)
    }

    @Test
    fun createEventRejectsEndEqualToStart() {
        val error = assertFailsWith<CalendarException.Invalid> {
            CreateEvent(title = "Standup", start = start, end = start).validated()
        }
        assertEquals("end must be after start", error.message)
    }

    @Test
    fun allDayStillRequiresExclusiveEndAfterStart() {
        val error = assertFailsWith<CalendarException.Invalid> {
            CreateEvent(
                title = "Holiday",
                start = Instant.parse("2026-03-01T00:00:00Z"),
                end = Instant.parse("2026-03-01T00:00:00Z"),
                allDay = true,
            ).validated()
        }
        assertEquals("end must be after start", error.message)
    }

    @Test
    fun createCalendarFallsBackToDefaultTimeZone() {
        val validated = CreateCalendar(displayName = "Work").validated("America/New_York")
        assertEquals("America/New_York", validated.timeZone)
    }

    @Test
    fun unknownTimeZoneIsInvalid() {
        val error = assertFailsWith<CalendarException.Invalid> {
            CreateCalendar(displayName = "Work", timeZone = "Not/AZone").validated()
        }
        assertEquals("unknown time zone: Not/AZone", error.message)
    }

    @Test
    fun createEventRejectsNonHttpUrl() {
        val error = assertFailsWith<CalendarException.Invalid> {
            CreateEvent(title = "Call", start = start, end = end, url = "ftp://example.com").validated()
        }
        assertEquals("url must start with http:// or https://", error.message)
    }

    @Test
    fun createEventValidatesRecurrenceInterval() {
        val error = assertFailsWith<CalendarException.Invalid> {
            CreateEvent(
                title = "Call",
                start = start,
                end = end,
                recurrence = Recurrence(frequency = RecurrenceFrequency.DAILY, interval = 0),
            ).validated()
        }
        assertEquals("interval must be at least 1", error.message)
    }

    @Test
    fun updateEventValidatesPresentTimeZoneAndAllowsClearing() {
        val cleared = UpdateEvent(timeZone = OptionalField.Present(null)).validated(start, end)
        assertEquals(OptionalField.Present(null), cleared.timeZone)

        val error = assertFailsWith<CalendarException.Invalid> {
            UpdateEvent(timeZone = OptionalField.Present("Not/AZone")).validated(start, end)
        }
        assertEquals("unknown time zone: Not/AZone", error.message)
    }
}
