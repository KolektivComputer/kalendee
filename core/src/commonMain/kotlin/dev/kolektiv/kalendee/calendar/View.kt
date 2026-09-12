package dev.kolektiv.kalendee.calendar

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

enum class CalendarView {
    Day,
    Week,
    Month,
    ;

    companion object {
        fun parse(raw: String?): CalendarView = when (raw?.trim()?.lowercase()) {
            "day" -> Day
            "month" -> Month
            else -> Week
        }
    }
}

data class ViewWindow(
    val view: CalendarView,
    val date: LocalDate,
    val timeZone: TimeZone,
) {
    val weekStart: LocalDate
        get() = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

    val monthStart: LocalDate
        get() = LocalDate(date.year, date.month, 1)

    val gridStart: LocalDate
        get() = when (view) {
            CalendarView.Day -> date
            CalendarView.Week -> weekStart
            CalendarView.Month -> monthStart.minus(monthStart.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
        }

    val gridEnd: LocalDate
        get() = when (view) {
            CalendarView.Day -> date.plus(1, DateTimeUnit.DAY)
            CalendarView.Week -> weekStart.plus(7, DateTimeUnit.DAY)
            CalendarView.Month -> {
                val last = monthStart.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
                last.plus(8 - last.dayOfWeek.isoDayNumber, DateTimeUnit.DAY)
            }
        }

    val range: InstantRange
        get() = InstantRange(
            start = gridStart.atStartOfDayIn(timeZone),
            end = gridEnd.atStartOfDayIn(timeZone),
        )

    val previous: LocalDate
        get() = when (view) {
            CalendarView.Day -> date.minus(1, DateTimeUnit.DAY)
            CalendarView.Week -> weekStart.minus(7, DateTimeUnit.DAY)
            CalendarView.Month -> monthStart.minus(1, DateTimeUnit.MONTH)
        }

    val next: LocalDate
        get() = when (view) {
            CalendarView.Day -> date.plus(1, DateTimeUnit.DAY)
            CalendarView.Week -> weekStart.plus(7, DateTimeUnit.DAY)
            CalendarView.Month -> monthStart.plus(1, DateTimeUnit.MONTH)
        }

    val label: String
        get() = when (view) {
            CalendarView.Day -> "${ShortMonths[date.month.ordinal]} ${date.day}"
            CalendarView.Week -> weekLabel(weekStart)
            CalendarView.Month -> "${FullMonths[date.month.ordinal]} ${date.year}"
        }

    companion object {
        fun of(
            view: String?,
            date: String?,
            week: String?,
            timeZoneId: String,
            now: Instant,
        ): ViewWindow {
            val zone = TimeZone.of(requireTimeZone(timeZoneId))
            val parsedView = CalendarView.parse(view)
            val anchor = parseDate(date ?: week, now, zone)
            return ViewWindow(view = parsedView, date = snap(parsedView, anchor), timeZone = zone)
        }

        private fun snap(view: CalendarView, date: LocalDate): LocalDate = when (view) {
            CalendarView.Day -> date
            CalendarView.Week -> date
            CalendarView.Month -> LocalDate(date.year, date.month, 1)
        }

        private fun parseDate(raw: String?, now: Instant, zone: TimeZone): LocalDate {
            if (raw.isNullOrBlank()) return now.toLocalDateTime(zone).date
            return try {
                LocalDate.parse(raw)
            } catch (_: IllegalArgumentException) {
                throw CalendarException.Invalid("invalid date: $raw")
            }
        }
    }
}

private val ShortMonths = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private val FullMonths = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private fun weekLabel(weekStart: LocalDate): String {
    val end = weekStart.plus(6, DateTimeUnit.DAY)
    val startMonth = ShortMonths[weekStart.month.ordinal]
    val endMonth = ShortMonths[end.month.ordinal]
    return if (weekStart.month == end.month) {
        "$startMonth ${weekStart.day}–${end.day}"
    } else {
        "$startMonth ${weekStart.day} – $endMonth ${end.day}"
    }
}
