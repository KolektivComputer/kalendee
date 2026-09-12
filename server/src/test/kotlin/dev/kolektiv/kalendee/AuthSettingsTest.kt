package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.RegistrationPolicy
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class AuthSettingsTest {
    @Test
    fun defaultsToFirstUserAndSecureOutsideDevelopment() {
        val settings = AuthSettings.from(MapApplicationConfig(), developmentMode = false)
        assertEquals(RegistrationPolicy.FirstUser, settings.registration)
        assertEquals(30.days, settings.sessionTtl)
        assertEquals("kalendee_session", settings.cookieName)
        assertTrue(settings.cookieSecure)
        assertEquals(19_456, settings.argon2MemoryKib)
        assertEquals("admin", settings.adminUsername)
        assertNull(settings.adminPassword)
        assertEquals(24.hours, settings.emailVerificationTtl)
    }

    @Test
    fun developmentDefaultsToInsecureCookies() {
        val settings = AuthSettings.from(MapApplicationConfig(), developmentMode = true)
        assertFalse(settings.cookieSecure)
    }

    @Test
    fun readsRegistrationAndArgon2FromConfig() {
        val settings = AuthSettings.from(
            MapApplicationConfig(
                "auth.registration" to "open",
                "auth.cookieSecure" to "false",
                "auth.argon2.memoryKib" to "8",
                "auth.argon2.iterations" to "1",
                "auth.emailVerificationTtlHours" to "48",
            ),
            developmentMode = false,
        )
        assertEquals(RegistrationPolicy.Open, settings.registration)
        assertFalse(settings.cookieSecure)
        assertEquals(8, settings.argon2MemoryKib)
        assertEquals(1, settings.argon2Iterations)
        assertEquals(48.hours, settings.emailVerificationTtl)
    }

    @Test
    fun readsAdminSeedFromConfig() {
        val settings = AuthSettings.from(
            MapApplicationConfig(
                "auth.adminUsername" to "admin",
                "auth.adminPassword" to "adminpass1012",
            ),
            developmentMode = true,
        )
        assertEquals("admin", settings.adminUsername)
        assertEquals("adminpass1012", settings.adminPassword)
    }

    @Test
    fun blankAdminPasswordInConfigDisablesSeed() {
        val settings = AuthSettings.from(
            MapApplicationConfig("auth.adminPassword" to ""),
            developmentMode = true,
        )
        assertNull(settings.adminPassword)
    }
}
