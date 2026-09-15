package dev.kolektiv.kalendee.ui

import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

internal fun demoCalendars(): List<CalendarUi> = listOf(
    CalendarUi("personal", "Personal", "primary"),
    CalendarUi("kolektiv", "Kolektiv", "secondary"),
    CalendarUi("kalendee", "Kalendee", "accent"),
)

internal fun demoEvents(): List<EventUi> {
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(tz).date
    val monday = today.plus(DatePeriod(days = 1 - today.dayOfWeek.isoDayNumber))
    fun at(day: LocalDate, hour: Int, minute: Int = 0): Long {
        return day.atStartOfDayIn(tz).toEpochMilliseconds() + hour * 3_600_000L + minute * 60_000L
    }
    fun day(offset: Int) = monday.plus(DatePeriod(days = offset))
    return listOf(
        EventUi("e1", "kolektiv", "Standup", startEpochMs = at(day(0), 9), endEpochMs = at(day(0), 9, 30)),
        EventUi("e2", "kalendee", "Compose desktop kickoff", notes = "Match the Keel pack week view.", location = "Springfield", startEpochMs = at(day(0), 10), endEpochMs = at(day(0), 12)),
        EventUi("e3", "personal", "Lunch", location = "Downtown", startEpochMs = at(day(0), 12, 15), endEpochMs = at(day(0), 13)),
        EventUi("e4", "kalendee", "Blawk tokens pass", startEpochMs = at(day(0), 14), endEpochMs = at(day(0), 16, 30)),
        EventUi("e5", "kolektiv", "TimezoneDB triage", startEpochMs = at(day(1), 11), endEpochMs = at(day(1), 12)),
        EventUi("e6", "kalendee", "Week grid packing", startEpochMs = at(day(2), 9, 30), endEpochMs = at(day(2), 12)),
        EventUi("e7", "kolektiv", "Keel pack review", startEpochMs = at(day(2), 14), endEpochMs = at(day(2), 15, 30)),
        EventUi("e8", "personal", "Office hours", startEpochMs = at(day(3), 16), endEpochMs = at(day(3), 17)),
        EventUi("e9", "kalendee", "Event dialog polish", startEpochMs = at(day(3), 10), endEpochMs = at(day(3), 11, 30)),
        EventUi("e10", "kolektiv", "Wake player sync", startEpochMs = at(day(4), 11), endEpochMs = at(day(4), 12, 15)),
    )
}
