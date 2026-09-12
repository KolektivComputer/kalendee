package dev.kolektiv.kalendee.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class HolidayTest {
    @Test
    fun easterSunday2026IsApril5() {
        assertEquals(LocalDate(2026, 4, 5), easterSunday(2026))
    }

    @Test
    fun usFloatingHolidaysFor2026() {
        val byId = WellKnownHolidays.associateBy { it.id }
        assertEquals(LocalDate(2026, 1, 19), byId.getValue("us-mlk-day").dateIn(2026))
        assertEquals(LocalDate(2026, 2, 16), byId.getValue("us-presidents-day").dateIn(2026))
        assertEquals(LocalDate(2026, 5, 25), byId.getValue("us-memorial-day").dateIn(2026))
        assertEquals(LocalDate(2026, 9, 7), byId.getValue("us-labor-day").dateIn(2026))
        assertEquals(LocalDate(2026, 11, 26), byId.getValue("us-thanksgiving").dateIn(2026))
        assertEquals(LocalDate(2026, 4, 3), byId.getValue("good-friday").dateIn(2026))
        assertEquals(LocalDate(2026, 5, 18), byId.getValue("ca-victoria-day").dateIn(2026))
    }

    @Test
    fun occurrencesIncludeCatalogAndCustomAcrossYearBoundary() {
        val prefs = HolidayPrefs(
            showHolidays = true,
            subscribedIds = listOf("new-years-day", "christmas-day"),
            custom = listOf(CustomHoliday(id = "mine", title = "Ada's day", month = 12, day = 31)),
        )
        val found = prefs.occurrences(LocalDate(2025, 12, 29), LocalDate(2026, 1, 5))
        assertEquals(
            listOf("Ada's day", "New Year's Day"),
            found.map { it.title },
        )
        assertEquals(LocalDate(2025, 12, 31), found[0].date)
        assertEquals(LocalDate(2026, 1, 1), found[1].date)
        assertTrue(found[0].id.startsWith("holiday:custom:mine:"))
        assertTrue(found[1].id.startsWith("holiday:new-years-day:"))
    }

    @Test
    fun unknownHolidayIdIsInvalid() {
        val error = assertFailsWith<CalendarException.Invalid> {
            requireKnownHolidayIds(listOf("new-years-day", "not-a-holiday"))
        }
        assertEquals("unknown holiday: not-a-holiday", error.message)
    }

    @Test
    fun customHolidayRejectsImpossibleDay() {
        val error = assertFailsWith<CalendarException.Invalid> {
            CreateCustomHoliday(title = "Nope", month = 11, day = 31).validated()
        }
        assertEquals("day must be between 1 and 30", error.message)
    }
}
