package dev.kolektiv.kalendee.calendar

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

const val HOLIDAY_CALENDAR_ID = "holiday"
const val HOLIDAY_COLOR = "secondary"

@Serializable
data class CustomHoliday(
    val id: String,
    val title: String,
    val month: Int,
    val day: Int,
)

@Serializable
data class CreateCustomHoliday(
    val title: String,
    val month: Int,
    val day: Int,
)

data class HolidayPrefs(
    val showHolidays: Boolean,
    val subscribedIds: List<String>,
    val custom: List<CustomHoliday>,
)

data class HolidayDefinition(
    val id: String,
    val name: String,
    val region: String,
    val rule: HolidayRule,
)

data class HolidayOccurrence(
    val id: String,
    val title: String,
    val date: LocalDate,
)

sealed class HolidayRule {
    data class Fixed(val month: Int, val day: Int) : HolidayRule()
    data class NthWeekday(val month: Int, val weekday: DayOfWeek, val nth: Int) : HolidayRule()
    data class WeekdayOnOrBefore(val month: Int, val day: Int, val weekday: DayOfWeek) : HolidayRule()
    data class EasterOffset(val offsetDays: Int) : HolidayRule()
}

val WellKnownHolidays: List<HolidayDefinition> = listOf(
    holiday("new-years-day", "New Year's Day", "International", HolidayRule.Fixed(1, 1)),
    holiday("valentines-day", "Valentine's Day", "International", HolidayRule.Fixed(2, 14)),
    holiday("international-workers-day", "International Workers' Day", "International", HolidayRule.Fixed(5, 1)),
    holiday("halloween", "Halloween", "International", HolidayRule.Fixed(10, 31)),
    holiday("christmas-eve", "Christmas Eve", "International", HolidayRule.Fixed(12, 24)),
    holiday("christmas-day", "Christmas Day", "International", HolidayRule.Fixed(12, 25)),
    holiday("boxing-day", "Boxing Day", "International", HolidayRule.Fixed(12, 26)),
    holiday("new-years-eve", "New Year's Eve", "International", HolidayRule.Fixed(12, 31)),
    holiday("good-friday", "Good Friday", "International", HolidayRule.EasterOffset(-2)),
    holiday("easter-sunday", "Easter Sunday", "International", HolidayRule.EasterOffset(0)),
    holiday("easter-monday", "Easter Monday", "International", HolidayRule.EasterOffset(1)),
    holiday(
        "us-mlk-day",
        "Martin Luther King Jr. Day",
        "United States",
        HolidayRule.NthWeekday(1, DayOfWeek.MONDAY, 3),
    ),
    holiday(
        "us-presidents-day",
        "Presidents' Day",
        "United States",
        HolidayRule.NthWeekday(2, DayOfWeek.MONDAY, 3),
    ),
    holiday(
        "us-memorial-day",
        "Memorial Day",
        "United States",
        HolidayRule.NthWeekday(5, DayOfWeek.MONDAY, -1),
    ),
    holiday("us-juneteenth", "Juneteenth", "United States", HolidayRule.Fixed(6, 19)),
    holiday("us-independence-day", "Independence Day", "United States", HolidayRule.Fixed(7, 4)),
    holiday(
        "us-labor-day",
        "Labor Day",
        "United States",
        HolidayRule.NthWeekday(9, DayOfWeek.MONDAY, 1),
    ),
    holiday(
        "us-indigenous-peoples-day",
        "Indigenous Peoples' Day",
        "United States",
        HolidayRule.NthWeekday(10, DayOfWeek.MONDAY, 2),
    ),
    holiday("us-veterans-day", "Veterans Day", "United States", HolidayRule.Fixed(11, 11)),
    holiday(
        "us-thanksgiving",
        "Thanksgiving",
        "United States",
        HolidayRule.NthWeekday(11, DayOfWeek.THURSDAY, 4),
    ),
    holiday(
        "uk-early-may",
        "Early May bank holiday",
        "United Kingdom",
        HolidayRule.NthWeekday(5, DayOfWeek.MONDAY, 1),
    ),
    holiday(
        "uk-spring-bank",
        "Spring bank holiday",
        "United Kingdom",
        HolidayRule.NthWeekday(5, DayOfWeek.MONDAY, -1),
    ),
    holiday(
        "uk-summer-bank",
        "Summer bank holiday",
        "United Kingdom",
        HolidayRule.NthWeekday(8, DayOfWeek.MONDAY, -1),
    ),
    holiday(
        "ca-victoria-day",
        "Victoria Day",
        "Canada",
        HolidayRule.WeekdayOnOrBefore(5, 24, DayOfWeek.MONDAY),
    ),
    holiday("ca-canada-day", "Canada Day", "Canada", HolidayRule.Fixed(7, 1)),
    holiday(
        "ca-labour-day",
        "Labour Day",
        "Canada",
        HolidayRule.NthWeekday(9, DayOfWeek.MONDAY, 1),
    ),
    holiday(
        "ca-thanksgiving",
        "Thanksgiving",
        "Canada",
        HolidayRule.NthWeekday(10, DayOfWeek.MONDAY, 2),
    ),
    holiday("ca-remembrance-day", "Remembrance Day", "Canada", HolidayRule.Fixed(11, 11)),
)

private fun holiday(id: String, name: String, region: String, rule: HolidayRule) =
    HolidayDefinition(id = id, name = name, region = region, rule = rule)

fun HolidayDefinition.dateIn(year: Int): LocalDate? = rule.dateIn(year)

fun HolidayDefinition.datesIn(start: LocalDate, endExclusive: LocalDate): List<LocalDate> =
    yearsIn(start, endExclusive).mapNotNull { year ->
        dateIn(year)?.takeIf { it >= start && it < endExclusive }
    }

fun CustomHoliday.datesIn(start: LocalDate, endExclusive: LocalDate): List<LocalDate> =
    yearsIn(start, endExclusive).mapNotNull { year ->
        runCatching { LocalDate(year, month, day) }.getOrNull()
            ?.takeIf { it >= start && it < endExclusive }
    }

fun HolidayPrefs.occurrences(start: LocalDate, endExclusive: LocalDate): List<HolidayOccurrence> {
    val catalog = WellKnownHolidays.associateBy { it.id }
    val fromCatalog = subscribedIds.mapNotNull(catalog::get).flatMap { definition ->
        definition.datesIn(start, endExclusive).map { date ->
            HolidayOccurrence(
                id = "holiday:${definition.id}:$date",
                title = definition.name,
                date = date,
            )
        }
    }
    val fromCustom = custom.flatMap { holiday ->
        holiday.datesIn(start, endExclusive).map { date ->
            HolidayOccurrence(
                id = "holiday:custom:${holiday.id}:$date",
                title = holiday.title,
                date = date,
            )
        }
    }
    return (fromCatalog + fromCustom).sortedWith(compareBy({ it.date }, { it.title }, { it.id }))
}

fun CreateCustomHoliday.validated(): CreateCustomHoliday {
    val name = requireNonBlank(title, "title")
    if (month !in 1..12) {
        throw CalendarException.Invalid("month must be between 1 and 12")
    }
    val maxDay = daysInMonth(month)
    if (day !in 1..maxDay) {
        throw CalendarException.Invalid("day must be between 1 and $maxDay")
    }
    return copy(title = name, month = month, day = day)
}

fun requireKnownHolidayIds(ids: List<String>): List<String> {
    val known = WellKnownHolidays.map { it.id }.toSet()
    val unique = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    unique.forEach { id ->
        if (id !in known) {
            throw CalendarException.Invalid("unknown holiday: $id")
        }
    }
    return unique
}

internal fun easterSunday(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = (h + l - 7 * m + 114) % 31 + 1
    return LocalDate(year, month, day)
}

private fun HolidayRule.dateIn(year: Int): LocalDate? = when (this) {
    is HolidayRule.Fixed -> runCatching { LocalDate(year, month, day) }.getOrNull()
    is HolidayRule.NthWeekday -> nthWeekday(year, month, weekday, nth)
    is HolidayRule.WeekdayOnOrBefore -> weekdayOnOrBefore(year, month, day, weekday)
    is HolidayRule.EasterOffset -> easterSunday(year).plus(offsetDays, DateTimeUnit.DAY)
}

private fun nthWeekday(year: Int, month: Int, weekday: DayOfWeek, nth: Int): LocalDate {
    if (nth > 0) {
        val first = LocalDate(year, month, 1)
        val delta = (weekday.ordinal - first.dayOfWeek.ordinal + 7) % 7
        return first.plus(delta + (nth - 1) * 7, DateTimeUnit.DAY)
    }
    val last = LocalDate(year, month, 1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
    val delta = (last.dayOfWeek.ordinal - weekday.ordinal + 7) % 7
    return last.minus(delta, DateTimeUnit.DAY)
}

private fun weekdayOnOrBefore(year: Int, month: Int, day: Int, weekday: DayOfWeek): LocalDate {
    val date = LocalDate(year, month, day)
    val delta = (date.dayOfWeek.ordinal - weekday.ordinal + 7) % 7
    return date.minus(delta, DateTimeUnit.DAY)
}

private fun yearsIn(start: LocalDate, endExclusive: LocalDate): IntRange {
    if (endExclusive <= start) return IntRange.EMPTY
    val last = endExclusive.minus(1, DateTimeUnit.DAY)
    return start.year..last.year
}

private fun daysInMonth(month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> 29
    else -> 0
}
