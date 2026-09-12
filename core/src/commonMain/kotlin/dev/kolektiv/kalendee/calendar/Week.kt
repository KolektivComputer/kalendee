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

data class WeekWindow(
    val weekStart: LocalDate,
    val timeZone: TimeZone,
) {
    val range: InstantRange
        get() = InstantRange(
            start = weekStart.atStartOfDayIn(timeZone),
            end = weekStart.plus(7, DateTimeUnit.DAY).atStartOfDayIn(timeZone),
        )

    val previous: LocalDate get() = weekStart.minus(7, DateTimeUnit.DAY)
    val next: LocalDate get() = weekStart.plus(7, DateTimeUnit.DAY)

    companion object {
        fun of(week: String?, timeZoneId: String, now: Instant): WeekWindow {
            val zone = TimeZone.of(requireTimeZone(timeZoneId))
            val date = parseWeekDate(week, now, zone)
            val monday = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
            return WeekWindow(weekStart = monday, timeZone = zone)
        }

        private fun parseWeekDate(week: String?, now: Instant, zone: TimeZone): LocalDate {
            if (week.isNullOrBlank()) return now.toLocalDateTime(zone).date
            return try {
                LocalDate.parse(week)
            } catch (_: IllegalArgumentException) {
                throw CalendarException.Invalid("invalid week: $week")
            }
        }
    }
}
