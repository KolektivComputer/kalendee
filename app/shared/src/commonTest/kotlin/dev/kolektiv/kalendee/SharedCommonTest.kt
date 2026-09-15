package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.client.parseClientCalendars
import dev.kolektiv.kalendee.client.parseClientEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedCommonTest {

    @Test
    fun parseCalendarsFromArrayAndNestedId() {
        val json = """
            [{"id":{"value":"cal-1"},"displayName":"Work","color":"secondary","hidden":false},
             {"id":"cal-2","name":"Home","color":"primary"}]
        """.trimIndent()
        val calendars = parseClientCalendars(json)
        assertEquals(2, calendars.size)
        assertEquals("cal-1", calendars[0].id)
        assertEquals("Work", calendars[0].name)
        assertEquals("secondary", calendars[0].color)
        assertEquals("Home", calendars[1].name)
    }

    @Test
    fun parseEventsFromItemsEnvelope() {
        val json = """
            {"items":[{"id":"e1","calendarId":{"value":"cal-1"},"title":"Standup",
              "description":"Daily","location":"HQ","start":"2026-09-14T14:00:00Z",
              "end":"2026-09-14T14:30:00Z","allDay":false,"etag":"abc"}]}
        """.trimIndent()
        val events = parseClientEvents(json)
        assertEquals(1, events.size)
        assertEquals("e1", events[0].id)
        assertEquals("cal-1", events[0].calendarId)
        assertEquals("Standup", events[0].title)
        assertEquals("Daily", events[0].notes)
        assertEquals("HQ", events[0].location)
        assertEquals("2026-09-14T14:00:00Z", events[0].startIso)
        assertFalse(events[0].allDay)
        assertEquals("abc", events[0].etag)
    }

    @Test
    fun parseAllDayEventFromDateOnly() {
        val json = """[{"id":"e2","calendarId":"cal-1","title":"Holiday","start":"2026-09-07","end":"2026-09-08","allDay":true}]"""
        val events = parseClientEvents(json)
        assertTrue(events.single().allDay)
        assertEquals("2026-09-07", events.single().startIso)
    }
}
