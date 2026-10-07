package dev.kolektiv.kalendee.ui.components.calendar

import dev.kolektiv.kalendee.calendar.ViewWindow
import dev.kolektiv.kalendee.ui.format.formatTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** How many event chips a month cell shows before collapsing the rest into `+N`. */
const val DefaultMonthChipLimit: Int = 2

/**
 * One month-grid day cell: [events] holds at most the visible chips and [overflow] the
 * number of events that were hidden. Pure so the grid content can be unit tested.
 */
data class MonthDayCell(
    val date: LocalDate,
    val inMonth: Boolean,
    val isToday: Boolean,
    val events: List<CalendarEventItem>,
    val overflow: Int,
)

/**
 * Events rendered inside [day]'s month cell. Mirrors the web `eventsOnDate`: all-day
 * events cover every day they span, timed events appear only on the day they start
 * (an occurrence crossing midnight still belongs to its start day). All-day events
 * sort first, then timed events by start instant.
 */
fun monthEventsOnDay(
    day: LocalDate,
    events: List<CalendarEventItem>,
    zone: TimeZone,
): List<CalendarEventItem> = events
    .filter { event ->
        if (event.allDay) {
            allDayOverlapsDay(event, day, zone)
        } else {
            event.start.toLocalDateTime(zone).date == day
        }
    }
    .sortedWith(compareBy({ !it.allDay }, { it.start }, { it.title }))

/**
 * Builds every cell of the month window (the core [daysOf] grid, typically 4–6 whole
 * weeks). [today] tints its cell; [maxChips] caps the chips per cell.
 */
fun monthCells(
    window: ViewWindow,
    events: List<CalendarEventItem>,
    zone: TimeZone,
    today: LocalDate? = null,
    maxChips: Int = DefaultMonthChipLimit,
): List<MonthDayCell> = daysOf(window).map { day ->
    val dayEvents = monthEventsOnDay(day, events, zone)
    MonthDayCell(
        date = day,
        inMonth = day.year == window.date.year && day.month == window.date.month,
        isToday = day == today,
        events = dayEvents.take(maxChips),
        overflow = (dayEvents.size - maxChips).coerceAtLeast(0),
    )
}

/** Chip label: timed chips show `HH:mm title`, all-day chips only the title. */
fun monthChipLabel(event: CalendarEventItem, zone: TimeZone): String =
    if (event.allDay) event.title else "${formatTime(event.start, zone)} ${event.title}"
