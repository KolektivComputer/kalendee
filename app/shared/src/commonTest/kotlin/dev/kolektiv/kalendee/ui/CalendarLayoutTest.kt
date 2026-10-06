package dev.kolektiv.kalendee.ui

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarView
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.calendar.ViewWindow
import dev.kolektiv.kalendee.client.ServerAccount
import dev.kolektiv.kalendee.client.ServerProfile
import dev.kolektiv.kalendee.ui.components.calendar.CalendarEventItem
import dev.kolektiv.kalendee.ui.components.calendar.TimedSpan
import dev.kolektiv.kalendee.ui.components.calendar.allDayOverlapsDay
import dev.kolektiv.kalendee.ui.components.calendar.assignColumns
import dev.kolektiv.kalendee.ui.components.calendar.daysOf
import dev.kolektiv.kalendee.ui.components.calendar.minutesOfDay
import dev.kolektiv.kalendee.ui.components.calendar.minutesToInstant
import dev.kolektiv.kalendee.ui.components.calendar.placeDayEvents
import dev.kolektiv.kalendee.ui.components.calendar.placementForDay
import dev.kolektiv.kalendee.ui.components.calendar.roundDownToSlot
import dev.kolektiv.kalendee.ui.components.calendar.shiftDate
import dev.kolektiv.kalendee.ui.screens.calendar.filterVisibleEvents
import dev.kolektiv.kalendee.ui.screens.calendar.firstWritableCalendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

private val UTC = TimeZone.UTC
private val Day = LocalDate(2026, 10, 6)

private fun instantAt(day: LocalDate, minutes: Int): Instant = minutesToInstant(day, minutes, UTC)

private fun item(
    id: String,
    startMinutes: Int,
    endMinutes: Int,
    allDay: Boolean = false,
    color: String = "primary",
): CalendarEventItem = CalendarEventItem(
    serverId = "s1",
    eventId = id,
    title = id,
    color = color,
    start = instantAt(Day, startMinutes),
    end = instantAt(Day, endMinutes),
    allDay = allDay,
    status = EventStatus.CONFIRMED,
)

private fun testCalendar(
    id: String = "cal-1",
    hidden: Boolean = false,
    permission: CalendarPermission = CalendarPermission.OWNER,
): Calendar = Calendar(
    id = CalendarId(id),
    ownerId = UserId("user-1"),
    displayName = "Work",
    color = "primary",
    hidden = hidden,
    permission = permission,
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
)

private fun testEvent(
    calendarId: CalendarId = CalendarId("cal-1"),
    id: String = "event-1",
): Event = Event(
    id = EventId(id),
    calendarId = calendarId,
    title = "Standup",
    start = Instant.parse("2026-10-06T09:00:00Z"),
    end = Instant.parse("2026-10-06T10:00:00Z"),
    allDay = false,
    status = EventStatus.CONFIRMED,
    etag = "etag-1",
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
)

class CalendarLayoutTest {

    @Test
    fun nonOverlappingSpansUseTheFullWidth() {
        val result = assignColumns(
            listOf(
                TimedSpan(540, 600),
                TimedSpan(660, 720),
            ),
        )

        assertEquals(2, result.size)
        assertEquals(0, result[0].column)
        assertEquals(1, result[0].columnCount)
        assertEquals(0, result[1].column)
        assertEquals(1, result[1].columnCount)
    }

    @Test
    fun overlappingSpansSplitSideBySide() {
        val result = assignColumns(
            listOf(
                TimedSpan(540, 600),
                TimedSpan(570, 630),
            ),
        )

        assertEquals(0, result[0].column)
        assertEquals(1, result[1].column)
        assertEquals(2, result[0].columnCount)
        assertEquals(2, result[1].columnCount)
    }

    @Test
    fun chainedOverlapsShareOneClusterButReuseFreeColumns() {
        val result = assignColumns(
            listOf(
                TimedSpan(540, 600),
                TimedSpan(570, 630),
                TimedSpan(600, 660),
            ),
        )

        assertEquals(2, result[0].columnCount)
        assertEquals(0, result[0].column)
        assertEquals(1, result[1].column)
        assertEquals(0, result[2].column)
    }

    @Test
    fun clustersAreIndependent() {
        val result = assignColumns(
            listOf(
                TimedSpan(540, 600),
                TimedSpan(570, 630),
                TimedSpan(900, 960),
            ),
        )

        assertEquals(2, result[0].columnCount)
        assertEquals(2, result[1].columnCount)
        assertEquals(1, result[2].columnCount)
        assertEquals(0, result[2].column)
    }

    @Test
    fun zeroLengthSpansStillGetAColumn() {
        val result = assignColumns(listOf(TimedSpan(540, 540)))

        assertEquals(1, result.size)
        assertEquals(1, result[0].columnCount)
    }

    @Test
    fun placementClipsToDayBounds() {
        val placement = placementForDay(
            start = Instant.parse("2026-10-05T22:00:00Z"),
            end = Instant.parse("2026-10-06T02:00:00Z"),
            day = Day,
            zone = UTC,
        )

        assertNotNull(placement)
        assertEquals(0, placement.startMinutes)
        assertEquals(120, placement.endMinutes)
        assertEquals(120, placement.durationMinutes)
    }

    @Test
    fun placementCoversWholeDayForMultiDayEvent() {
        val placement = placementForDay(
            start = Instant.parse("2026-10-01T00:00:00Z"),
            end = Instant.parse("2026-10-20T00:00:00Z"),
            day = Day,
            zone = UTC,
        )

        assertNotNull(placement)
        assertEquals(0, placement.startMinutes)
        assertEquals(24 * 60, placement.endMinutes)
    }

    @Test
    fun placementIsNullOutsideTheDay() {
        assertNull(
            placementForDay(
                start = Instant.parse("2026-10-07T09:00:00Z"),
                end = Instant.parse("2026-10-07T10:00:00Z"),
                day = Day,
                zone = UTC,
            ),
        )
        assertNull(
            placementForDay(
                start = Instant.parse("2026-10-06T10:00:00Z"),
                end = Instant.parse("2026-10-06T10:00:00Z"),
                day = Day,
                zone = UTC,
            ),
        )
    }

    @Test
    fun placeDayEventsPositionsAndLaysOutColumns() {
        val events = listOf(
            item("a", 9 * 60, 10 * 60),
            item("b", 9 * 60 + 30, 10 * 60 + 30),
            item("c", 11 * 60, 12 * 60),
        )

        val placed = placeDayEvents(Day, events, UTC).associateBy { it.event.eventId }

        assertEquals(3, placed.size)
        assertEquals(540, placed.getValue("a").placement.startMinutes)
        assertEquals(60, placed.getValue("b").placement.durationMinutes)
        assertEquals(2, placed.getValue("a").column.columnCount)
        assertEquals(2, placed.getValue("b").column.columnCount)
        assertEquals(1, placed.getValue("c").column.columnCount)
        assertTrue(placed.getValue("a").column.column != placed.getValue("b").column.column)
    }

    @Test
    fun allDayEventsCoverEveryDayTheySpan() {
        val spanning = CalendarEventItem(
            serverId = "s1",
            eventId = "offsite",
            title = "Offsite",
            color = "primary",
            start = Instant.parse("2026-10-06T00:00:00Z"),
            end = Instant.parse("2026-10-08T00:00:00Z"),
            allDay = true,
        )

        assertTrue(allDayOverlapsDay(spanning, Day, UTC))
        assertTrue(allDayOverlapsDay(spanning, Day.plus(1, DateTimeUnit.DAY), UTC))
        assertTrue(!allDayOverlapsDay(spanning, Day.plus(2, DateTimeUnit.DAY), UTC))
    }

    @Test
    fun weekWindowCoversMondayThroughSunday() {
        val window = ViewWindow(
            view = CalendarView.Week,
            date = LocalDate(2026, 10, 7),
            timeZone = UTC,
        )

        assertEquals(listOf(5, 6, 7, 8, 9, 10, 11).map { LocalDate(2026, 10, it) }, daysOf(window))
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), window.range.start)
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), window.range.end)
    }

    @Test
    fun dayWindowCoversASingleDay() {
        val window = ViewWindow(view = CalendarView.Day, date = Day, timeZone = UTC)

        assertEquals(listOf(Day), daysOf(window))
        assertEquals(Instant.parse("2026-10-06T00:00:00Z"), window.range.start)
        assertEquals(Instant.parse("2026-10-07T00:00:00Z"), window.range.end)
    }

    @Test
    fun shiftDateClampsMonthLength() {
        assertEquals(LocalDate(2026, 2, 28), shiftDate(LocalDate(2026, 1, 31), months = 1))
        assertEquals(LocalDate(2027, 1, 31), shiftDate(LocalDate(2026, 12, 31), months = 1))
        assertEquals(LocalDate(2026, 10, 1), shiftDate(LocalDate(2026, 9, 30), days = 1))
        assertEquals(LocalDate(2025, 10, 6), shiftDate(Day, years = -1))
    }

    @Test
    fun roundDownToSlotClampsToTheDay() {
        assertEquals(540, roundDownToSlot(547))
        assertEquals(570, roundDownToSlot(570))
        assertEquals(0, roundDownToSlot(0))
        assertEquals(23 * 60 + 30, roundDownToSlot(24 * 60 - 1))
    }

    @Test
    fun minutesConversionsRoundTrip() {
        assertEquals(570, minutesOfDay(minutesToInstant(Day, 570, UTC), UTC))
        assertEquals(Instant.parse("2026-10-07T00:00:00Z"), minutesToInstant(Day, 24 * 60, UTC))
        assertEquals(Instant.parse("2026-10-05T23:30:00Z"), minutesToInstant(Day, -30, UTC))
    }

    @Test
    fun hiddenCalendarsAndDisabledServersAreFilteredOut() {
        val visibleCalendar = testCalendar(id = "cal-visible")
        val hiddenCalendar = testCalendar(id = "cal-hidden", hidden = true)
        val servers = listOf(
            ServerUi(
                account = ServerAccount(
                    profile = ServerProfile(id = "s1", name = "Local", baseUrl = "https://cal"),
                    token = "token",
                ),
                username = "alice",
                calendars = listOf(
                    CalendarUi(serverId = "s1", calendar = visibleCalendar, visible = true),
                    CalendarUi(serverId = "s1", calendar = hiddenCalendar, visible = false),
                ),
            ),
            ServerUi(
                account = ServerAccount(
                    profile = ServerProfile(id = "s2", name = "Off", baseUrl = "https://cal2", enabled = false),
                    token = "token",
                ),
                username = "bob",
                calendars = emptyList(),
            ),
        )
        val events = listOf(
            AggregatedEvent("s1", "Local", visibleCalendar, testEvent(visibleCalendar.id, "e-visible")),
            AggregatedEvent("s1", "Local", hiddenCalendar, testEvent(hiddenCalendar.id, "e-hidden")),
            AggregatedEvent(
                "s2",
                "Off",
                testCalendar(id = "cal-off"),
                testEvent(CalendarId("cal-off"), "e-off"),
            ),
        )

        val filtered = filterVisibleEvents(events, servers)

        assertEquals(listOf("e-visible"), filtered.map { it.event.id.value })
    }

    @Test
    fun firstWritableCalendarPrefersWritableVisible() {
        val readOnly = testCalendar(id = "cal-read", permission = CalendarPermission.READ)
        val writable = testCalendar(id = "cal-write", permission = CalendarPermission.WRITE)
        val servers = listOf(
            ServerUi(
                account = ServerAccount(
                    profile = ServerProfile(id = "s1", name = "Local", baseUrl = "https://cal"),
                    token = null,
                ),
                calendars = listOf(CalendarUi("s1", writable)),
            ),
            ServerUi(
                account = ServerAccount(
                    profile = ServerProfile(id = "s2", name = "Work", baseUrl = "https://cal2"),
                    token = "token",
                ),
                username = "alice",
                calendars = listOf(
                    CalendarUi("s2", readOnly),
                    CalendarUi("s2", writable),
                ),
            ),
        )

        assertEquals("s2" to "cal-write", firstWritableCalendar(servers))
    }
}
