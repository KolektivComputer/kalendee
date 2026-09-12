package dev.kolektiv.kalendee.auth

import dev.kolektiv.kalendee.calendar.CalendarException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthValidationTest {
    @Test
    fun registerNormalizesUsername() {
        val validated = RegisterUser(username = "  Mey.User  ", password = "password12").validated()
        assertEquals("mey.user", validated.username)
    }

    @Test
    fun registerRejectsShortUsername() {
        val error = assertFailsWith<CalendarException.Invalid> {
            RegisterUser(username = "ab", password = "password12").validated()
        }
        assertEquals(true, error.message?.contains("username"))
    }

    @Test
    fun registerRejectsShortPassword() {
        val error = assertFailsWith<CalendarException.Invalid> {
            RegisterUser(username = "mey", password = "short").validated()
        }
        assertEquals("password must be at least 8 characters", error.message)
    }

    @Test
    fun updateUserTrimsDisplayName() {
        val validated = UpdateUser(displayName = "  Ada  ", timeZone = "America/New_York", accent = " Info ").validated()
        assertEquals("Ada", validated.displayName)
        assertEquals("America/New_York", validated.timeZone)
        assertEquals("info", validated.accent)
    }

    @Test
    fun updateUserRejectsUnknownAccent() {
        val error = assertFailsWith<CalendarException.Invalid> {
            UpdateUser(accent = "yellow").validated()
        }
        assertEquals(true, error.message?.startsWith("accent"))
    }

    @Test
    fun loginTrimsAndLowercasesUsername() {
        val normalized = LoginUser(username = "  Mey  ", password = " password ").normalized()
        assertEquals("mey", normalized.username)
        assertEquals(" password ", normalized.password)
    }
}
