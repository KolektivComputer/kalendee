package dev.kolektiv.kalendee.ui.screens.events

import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.calendar.OptionalField
import dev.kolektiv.kalendee.calendar.Recurrence
import dev.kolektiv.kalendee.calendar.RecurrenceFrequency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

private val Utc = TimeZone.UTC
private val Day = LocalDate(2026, 10, 6)

private fun draft(
    title: String = "Meeting",
    location: String = "",
    description: String = "",
    allDay: Boolean = false,
    start: LocalDateTime = LocalDateTime(Day, LocalTime(9, 0)),
    end: LocalDateTime = LocalDateTime(Day, LocalTime(10, 0)),
    recurrence: RecurrenceChoice = RecurrenceChoice.None,
    untilDate: LocalDate? = null,
): EventDraft = EventDraft(
    title = title,
    location = location,
    description = description,
    allDay = allDay,
    start = start,
    end = end,
    recurrence = recurrence,
    untilDate = untilDate,
)

private fun storedEvent(
    start: Instant = Instant.parse("2026-10-06T09:00:00Z"),
    end: Instant = Instant.parse("2026-10-06T10:00:00Z"),
    allDay: Boolean = false,
    recurrence: Recurrence? = null,
): Event = Event(
    id = EventId("event-1"),
    calendarId = CalendarId("cal-1"),
    title = "Meeting",
    start = start,
    end = end,
    allDay = allDay,
    status = EventStatus.CONFIRMED,
    recurrence = recurrence,
    etag = "etag-1",
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
)

class EventEditorLogicTest {

    @Test
    fun validationRequiresTitle() {
        val errors = draft(title = "   ").validate()

        assertEquals("Title is required.", errors.title)
        assertFalse(errors.isValid)
    }

    @Test
    fun validationRejectsEndBeforeStart() {
        val errors = draft(
            start = LocalDateTime(Day, LocalTime(10, 0)),
            end = LocalDateTime(Day, LocalTime(9, 0)),
        ).validate()

        assertEquals("End must be after the start.", errors.whenError)
        assertFalse(errors.isValid)
    }

    @Test
    fun validationRejectsZeroLengthAllDayDraft() {
        val errors = draft(
            allDay = true,
            start = LocalDateTime(Day, LocalTime(0, 0)),
            end = LocalDateTime(Day, LocalTime(0, 0)),
        ).validate()

        assertTrue(errors.whenError != null)
    }

    @Test
    fun validationRejectsUntilBeforeStart() {
        val errors = draft(
            recurrence = RecurrenceChoice.Weekly,
            untilDate = LocalDate(2026, 10, 5),
        ).validate()

        assertTrue(errors.recurrence != null)
        assertFalse(errors.isValid)
    }

    @Test
    fun createCommandMapsEveryField() {
        val command = draft(
            title = "  Meeting  ",
            location = "Room 1",
            description = "",
            recurrence = RecurrenceChoice.Weekly,
            untilDate = LocalDate(2026, 10, 20),
        ).toCreateEvent(Utc)

        assertEquals("Meeting", command.title)
        assertEquals("Room 1", command.location)
        assertNull(command.description)
        assertEquals(Instant.parse("2026-10-06T09:00:00Z"), command.start)
        assertEquals(Instant.parse("2026-10-06T10:00:00Z"), command.end)
        assertFalse(command.allDay)
        assertEquals("UTC", command.timeZone)
        assertEquals(RecurrenceFrequency.WEEKLY, command.recurrence?.frequency)
        assertEquals(Instant.parse("2026-10-21T00:00:00Z"), command.recurrence?.until)
    }

    @Test
    fun createCommandMapsAllDayExclusiveEnd() {
        val command = draft(
            allDay = true,
            start = LocalDateTime(Day, LocalTime(0, 0)),
            end = LocalDateTime(LocalDate(2026, 10, 8), LocalTime(0, 0)),
        ).toCreateEvent(Utc)

        assertEquals(Instant.parse("2026-10-06T00:00:00Z"), command.start)
        assertEquals(Instant.parse("2026-10-08T00:00:00Z"), command.end)
        assertTrue(command.allDay)
        assertNull(command.recurrence)
    }

    @Test
    fun updateCommandClearsRecurrenceAndBlankFields() {
        val update = draft(
            title = "Moved",
            location = "  ",
            description = "  ",
            recurrence = RecurrenceChoice.None,
        ).toUpdateEvent(Utc)

        assertEquals("Moved", update.title)
        val location = assertIs<OptionalField.Present<String?>>(update.location)
        assertNull(location.value)
        val description = assertIs<OptionalField.Present<String?>>(update.description)
        assertNull(description.value)
        val recurrence = assertIs<OptionalField.Present<Recurrence?>>(update.recurrence)
        assertNull(recurrence.value)
        assertEquals(OptionalField.Absent, update.url)
    }

    @Test
    fun toAllDayKeepsEveryTouchedDay() {
        val overnight = draft(
            start = LocalDateTime(Day, LocalTime(22, 0)),
            end = LocalDateTime(LocalDate(2026, 10, 7), LocalTime(1, 0)),
        ).toAllDay()

        assertTrue(overnight.allDay)
        assertEquals(LocalDateTime(Day, LocalTime(0, 0)), overnight.start)
        assertEquals(LocalDateTime(LocalDate(2026, 10, 8), LocalTime(0, 0)), overnight.end)
    }

    @Test
    fun toTimedUsesSensibleDefaults() {
        val single = draft(
            allDay = true,
            start = LocalDateTime(Day, LocalTime(0, 0)),
            end = LocalDateTime(LocalDate(2026, 10, 7), LocalTime(0, 0)),
        ).toTimed()
        assertEquals(LocalDateTime(Day, LocalTime(9, 0)), single.start)
        assertEquals(LocalDateTime(Day, LocalTime(10, 0)), single.end)
        assertFalse(single.allDay)

        val multi = draft(
            allDay = true,
            start = LocalDateTime(Day, LocalTime(0, 0)),
            end = LocalDateTime(LocalDate(2026, 10, 8), LocalTime(0, 0)),
        ).toTimed()
        assertEquals(LocalDateTime(Day, LocalTime(9, 0)), multi.start)
        assertEquals(LocalDateTime(LocalDate(2026, 10, 7), LocalTime(17, 0)), multi.end)
    }

    @Test
    fun draftFromRestoresTimedEvent() {
        val restored = draftFrom(storedEvent(), Utc)

        assertEquals("Meeting", restored.title)
        assertEquals(LocalDateTime(Day, LocalTime(9, 0)), restored.start)
        assertEquals(LocalDateTime(Day, LocalTime(10, 0)), restored.end)
        assertFalse(restored.allDay)
        assertEquals(RecurrenceChoice.None, restored.recurrence)
    }

    @Test
    fun draftFromRestoresAllDayAndRecurrence() {
        val restored = draftFrom(
            storedEvent(
                start = Instant.parse("2026-10-06T00:00:00Z"),
                end = Instant.parse("2026-10-07T00:00:00Z"),
                allDay = true,
                recurrence = Recurrence(
                    frequency = RecurrenceFrequency.DAILY,
                    until = Instant.parse("2026-10-21T00:00:00Z"),
                ),
            ),
            Utc,
        )

        assertTrue(restored.allDay)
        assertEquals(LocalDateTime(Day, LocalTime(0, 0)), restored.start)
        assertEquals(LocalDateTime(LocalDate(2026, 10, 7), LocalTime(0, 0)), restored.end)
        assertEquals(RecurrenceChoice.Daily, restored.recurrence)
        assertEquals(LocalDate(2026, 10, 20), restored.untilDate)
    }

    @Test
    fun defaultSlotRoundsUpToTheNextHalfHour() {
        val (start, end) = nextDefaultSlot(
            now = Instant.parse("2026-10-06T09:07:00Z"),
            zone = Utc,
        )

        assertEquals(Instant.parse("2026-10-06T09:30:00Z"), start)
        assertEquals(Instant.parse("2026-10-06T10:00:00Z"), end)
    }

    @Test
    fun defaultSlotRollsIntoTheNextDay() {
        val (start, end) = nextDefaultSlot(
            now = Instant.parse("2026-10-06T23:50:00Z"),
            zone = Utc,
        )

        assertEquals(Instant.parse("2026-10-07T00:00:00Z"), start)
        assertEquals(Instant.parse("2026-10-07T00:30:00Z"), end)
    }

    @Test
    fun seededDraftUsesTheTappedSlot() {
        val seed = Instant.parse("2026-10-08T14:30:00Z") to Instant.parse("2026-10-08T15:00:00Z")

        val seeded = defaultEventDraft(Utc, seed)

        assertEquals(LocalDateTime(LocalDate(2026, 10, 8), LocalTime(14, 30)), seeded.start)
        assertEquals(LocalDateTime(LocalDate(2026, 10, 8), LocalTime(15, 0)), seeded.end)
    }

    @Test
    fun shiftLocalMinutesCrossesMidnight() {
        val shifted = shiftLocalMinutes(LocalDateTime(Day, LocalTime(23, 45)), 30, Utc)

        assertEquals(LocalDateTime(LocalDate(2026, 10, 7), LocalTime(0, 15)), shifted)
    }
}
