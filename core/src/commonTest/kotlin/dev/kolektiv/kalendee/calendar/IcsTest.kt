package dev.kolektiv.kalendee.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class IcsTest {
    @Test
    fun parseTimedAndAllDay() {
        val parsed = parseIcs(
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            X-WR-CALNAME:Team
            BEGIN:VEVENT
            UID:standup-1
            SUMMARY:Standup
            DTSTART:20260914T140000Z
            DTEND:20260914T143000Z
            LOCATION:Voice
            DESCRIPTION:Daily
            END:VEVENT
            BEGIN:VEVENT
            UID:holiday-1
            SUMMARY:Labor Day
            DTSTART;VALUE=DATE:20260907
            DTEND;VALUE=DATE:20260908
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
        )
        assertEquals("Team", parsed.name)
        assertEquals(2, parsed.events.size)
        assertEquals("Standup", parsed.events[0].title)
        assertEquals("Voice", parsed.events[0].location)
        assertEquals(false, parsed.events[0].allDay)
        assertEquals(Instant.parse("2026-09-14T14:00:00Z"), parsed.events[0].start)
        assertEquals("Labor Day", parsed.events[1].title)
        assertTrue(parsed.events[1].allDay)
        assertEquals(Instant.parse("2026-09-07T00:00:00Z"), parsed.events[1].start)
    }

    @Test
    fun unfoldSummary() {
        val parsed = parseIcs(
            """
            BEGIN:VEVENT
            UID:x
            SUMMARY:Hello
              world
            DTSTART:20260914T090000Z
            DTEND:20260914T100000Z
            END:VEVENT
            """.trimIndent(),
        )
        assertEquals("Hello world", parsed.events.single().title)
    }

    @Test
    fun rejectPrivateHosts() {
        assertEquals("https://example.com/cal.ics", assertPublicHttpUrl("webcal://example.com/cal.ics"))
        assertFailsWith<CalendarException.Invalid> { assertPublicHttpUrl("http://127.0.0.1/secret.ics") }
        assertFailsWith<CalendarException.Invalid> { assertPublicHttpUrl("https://192.168.1.4/cal.ics") }
        assertFailsWith<CalendarException.Invalid> { assertPublicHttpUrl("file:///etc/passwd") }
    }
}
