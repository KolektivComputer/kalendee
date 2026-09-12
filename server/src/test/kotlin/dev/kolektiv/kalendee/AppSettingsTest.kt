package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.config.AppSettings
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {
    @Test
    fun explicitBaseUrlWinsAndTrailingSlashIsStripped() {
        val settings = AppSettings.from(
            MapApplicationConfig("app.baseUrl" to "https://calendar.example/"),
            developmentMode = true,
        )
        assertEquals("https://calendar.example", settings.baseUrl)
    }

    @Test
    fun blankBaseUrlInDevelopmentDerivesFromDeploymentPort() {
        val settings = AppSettings.from(
            MapApplicationConfig(
                "app.baseUrl" to "",
                "ktor.deployment.port" to "9090",
            ),
            developmentMode = true,
        )
        assertEquals("http://127.0.0.1:9090", settings.baseUrl)
    }

    @Test
    fun blankBaseUrlInDevelopmentFallsBackToDefaultPort() {
        val settings = AppSettings.from(MapApplicationConfig(), developmentMode = true)
        assertEquals("http://127.0.0.1:8080", settings.baseUrl)
    }

    @Test
    fun blankBaseUrlOutsideDevelopmentStaysBlank() {
        val settings = AppSettings.from(MapApplicationConfig(), developmentMode = false)
        assertEquals("", settings.baseUrl)
    }

    @Test
    fun nonHttpBaseUrlIsReturnedAsIs() {
        val settings = AppSettings.from(
            MapApplicationConfig("app.baseUrl" to "calendar.example"),
            developmentMode = false,
        )
        assertEquals("calendar.example", settings.baseUrl)
    }

    @Test
    fun appDevelopmentConfigOverridesDevelopmentMode() {
        val enabled = AppSettings.from(
            MapApplicationConfig("app.development" to "true"),
            developmentMode = false,
        )
        assertEquals(true, enabled.development)
        assertEquals("http://127.0.0.1:8080", enabled.baseUrl)

        val disabled = AppSettings.from(
            MapApplicationConfig("app.development" to "false"),
            developmentMode = true,
        )
        assertEquals(false, disabled.development)
        assertEquals("", disabled.baseUrl)
    }

    @Test
    fun developmentFallsBackToDevelopmentModeWhenUnset() {
        assertEquals(true, AppSettings.from(MapApplicationConfig(), developmentMode = true).development)
        assertEquals(false, AppSettings.from(MapApplicationConfig(), developmentMode = false).development)
    }
}
