package dev.kolektiv.kalendee.ui.components.calendar

import dev.kolektiv.kalendee.calendar.CalendarView
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.calendar.ViewWindow
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

const val MinutesPerDay: Int = 24 * 60
const val DefaultSlotMinutes: Int = 30

/** One timed event reduced to its local start/end minutes within a day. */
data class TimedSpan(val startMinutes: Int, val endMinutes: Int)

/** Column placement for one span; [columnCount] is shared by every span in its cluster. */
data class ColumnAssignment(val column: Int, val columnCount: Int)

/**
 * Assigns side-by-side columns to arbitrary [spans] (minutes from midnight, input order
 * preserved). Spans that do not overlap any other span occupy the full width; spans in a
 * chain of overlaps share the width evenly. Pure so the grid layout can be unit tested.
 */
fun assignColumns(spans: List<TimedSpan>): List<ColumnAssignment> {
    if (spans.isEmpty()) return emptyList()
    val result = MutableList(spans.size) { ColumnAssignment(column = 0, columnCount = 1) }
    val order = spans.indices.sortedWith(
        compareBy({ spans[it].startMinutes }, { spans[it].endMinutes }, { it }),
    )
    var cluster = mutableListOf<Int>()
    var clusterEnd = Int.MIN_VALUE
    var columnEnds = mutableListOf<Int>()

    fun flush() {
        val count = columnEnds.size.coerceAtLeast(1)
        for (index in cluster) {
            result[index] = result[index].copy(columnCount = count)
        }
        cluster = mutableListOf()
        columnEnds = mutableListOf()
        clusterEnd = Int.MIN_VALUE
    }

    for (index in order) {
        val span = spans[index]
        val start = span.startMinutes
        val end = maxOf(span.endMinutes, start + 1)
        if (cluster.isNotEmpty() && start >= clusterEnd) flush()
        var column = columnEnds.indexOfFirst { it <= start }
        if (column == -1) {
            column = columnEnds.size
            columnEnds.add(end)
        } else {
            columnEnds[column] = end
        }
        result[index] = ColumnAssignment(column = column, columnCount = 1)
        cluster.add(index)
        clusterEnd = maxOf(clusterEnd, end)
    }
    flush()
    return result
}

/** Vertical slice of an event inside one day column, in minutes from midnight. */
data class DayPlacement(val startMinutes: Int, val endMinutes: Int) {
    val durationMinutes: Int get() = endMinutes - startMinutes
}

/**
 * Clips [start]–[end] to the visible [dayStartMinutes]–[dayEndMinutes] window of [day] in
 * [zone]. Returns null when the event does not intersect the window at all. Multi-day
 * events are clamped to the day edges so they render as full-height blocks.
 */
fun placementForDay(
    start: Instant,
    end: Instant,
    day: LocalDate,
    zone: TimeZone,
    dayStartMinutes: Int = 0,
    dayEndMinutes: Int = MinutesPerDay,
): DayPlacement? {
    if (end <= start) return null
    val windowStart = minutesToInstant(day, dayStartMinutes, zone)
    val windowEnd = minutesToInstant(day, dayEndMinutes, zone)
    if (end <= windowStart || start >= windowEnd) return null
    val top = (maxOf(start, windowStart) - windowStart).inWholeMinutes.toInt()
    val bottom = (minOf(end, windowEnd) - windowStart).inWholeMinutes.toInt()
    if (bottom <= top) return null
    return DayPlacement(
        startMinutes = (top + dayStartMinutes).coerceIn(0, MinutesPerDay),
        endMinutes = (bottom + dayStartMinutes).coerceIn(0, MinutesPerDay),
    )
}

/** Local minutes after midnight for [instant] in [zone]. */
fun minutesOfDay(instant: Instant, zone: TimeZone): Int {
    val local = instant.toLocalDateTime(zone)
    return local.hour * 60 + local.minute
}

/**
 * [minutes] after midnight on [day] in [zone]; values outside `0..1439` roll into
 * neighbouring days, so `minutesToInstant(day, 1440, zone)` is the next midnight.
 */
fun minutesToInstant(day: LocalDate, minutes: Int, zone: TimeZone): Instant {
    val date = day.plus(minutes.floorDiv(MinutesPerDay), DateTimeUnit.DAY)
    val minuteOfDay = minutes.mod(MinutesPerDay)
    return date.atTime(minuteOfDay / 60, minuteOfDay % 60).toInstant(zone)
}

/** Rounds [minutes] down to the previous multiple of [slotMinutes], clamped to the day. */
fun roundDownToSlot(minutes: Int, slotMinutes: Int = DefaultSlotMinutes): Int =
    (minutes.coerceIn(0, MinutesPerDay - slotMinutes) / slotMinutes) * slotMinutes

/**
 * Days covered by [window]: one for Day view, seven for Week view and every cell of the
 * month grid for Month view (whole weeks between [ViewWindow.gridStart] and
 * [ViewWindow.gridEnd], typically 4–6 rows).
 */
fun daysOf(window: ViewWindow): List<LocalDate> {
    val count = when (window.view) {
        CalendarView.Day -> 1
        CalendarView.Week -> 7
        CalendarView.Month -> window.gridStart.daysUntil(window.gridEnd)
    }
    return List(count) { window.gridStart.plus(it, DateTimeUnit.DAY) }
}

/**
 * Anchor date [steps] periods away from [anchor] for [view]: one day, one week or one
 * month at a time, matching [ViewWindow.previous]/[ViewWindow.next] semantics. Month
 * steps clamp the day of month (Jan 31 + 1 month is Feb 28/29).
 */
fun shiftAnchor(view: CalendarView, anchor: LocalDate, steps: Int): LocalDate = when (view) {
    CalendarView.Day -> anchor.plus(steps, DateTimeUnit.DAY)
    CalendarView.Week -> anchor.plus(steps * 7, DateTimeUnit.DAY)
    CalendarView.Month -> shiftDate(anchor, months = steps)
}

/**
 * Shifts [date] by [years]/[months]/[days], clamping the day of month (Jan 31 + 1 month
 * is Feb 28/29). Month/year shifts apply before the day shift.
 */
fun shiftDate(date: LocalDate, years: Int = 0, months: Int = 0, days: Int = 0): LocalDate {
    var result = date
    if (years != 0 || months != 0) {
        val firstOfMonth = LocalDate(result.year, result.month, 1)
        val shifted = firstOfMonth.plus(years * 12 + months, DateTimeUnit.MONTH)
        val lastDay = shifted.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
        result = LocalDate(shifted.year, shifted.month, minOf(result.day, lastDay))
    }
    if (days != 0) {
        result = result.plus(days, DateTimeUnit.DAY)
    }
    return result
}

/** One renderable event: merged from AppState but decoupled from it for testability. */
data class CalendarEventItem(
    val serverId: String,
    val eventId: String,
    val title: String,
    val color: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val status: EventStatus = EventStatus.CONFIRMED,
    /** Calendar id, used to resolve a fallback color when [color] is blank. */
    val calendarId: String = "",
)

/** [event] with its column and vertical placement inside [day]. */
data class PlacedCalendarEvent(
    val event: CalendarEventItem,
    val placement: DayPlacement,
    val column: ColumnAssignment,
)

/** Timed events for [day], positioned and laid out side by side. */
fun placeDayEvents(
    day: LocalDate,
    events: List<CalendarEventItem>,
    zone: TimeZone,
): List<PlacedCalendarEvent> {
    val placements = events.asSequence()
        .filterNot { it.allDay }
        .mapNotNull { event ->
            placementForDay(event.start, event.end, day, zone)?.let { event to it }
        }
        .toList()
    val columns = assignColumns(placements.map { TimedSpan(it.second.startMinutes, it.second.endMinutes) })
    return placements.mapIndexed { index, (event, placement) ->
        PlacedCalendarEvent(event = event, placement = placement, column = columns[index])
    }
}

/** True when an all-day [event] covers [day] in [zone]. */
fun allDayOverlapsDay(event: CalendarEventItem, day: LocalDate, zone: TimeZone): Boolean {
    val dayStart = day.atStartOfDayIn(zone)
    val dayEnd = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
    return event.start < dayEnd && event.end > dayStart
}
