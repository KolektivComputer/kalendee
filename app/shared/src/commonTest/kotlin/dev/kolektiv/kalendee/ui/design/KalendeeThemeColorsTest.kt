package dev.kolektiv.kalendee.ui.design

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class KalendeeThemeColorsTest {

    @Test
    fun defaultAccentKeepsBrandPrimary() {
        val colors = kalendeeColorsFor("primary", dark = true)
        assertEquals(kalendeeDarkColors.primary, colors.primary)
        assertEquals(kalendeeDarkColors.primaryContent, colors.primaryContent)
    }

    @Test
    fun errorAccentRemapsPrimary() {
        val colors = kalendeeColorsFor("error", dark = true)
        assertEquals(kalendeeDarkColors.error, colors.primary)
        assertEquals(kalendeeDarkColors.errorContent, colors.primaryContent)
        assertEquals(kalendeeDarkColors.secondary, colors.secondary)
        assertEquals(kalendeeDarkColors.base100, colors.base100)
    }

    @Test
    fun everyWebAccentIdRemapsPrimary() {
        val colors = kalendeeDarkColors
        assertEquals(colors.secondary, kalendeeColorsFor("secondary", true).primary)
        assertEquals(colors.accent, kalendeeColorsFor("accent", true).primary)
        assertEquals(colors.info, kalendeeColorsFor("info", true).primary)
        assertEquals(colors.success, kalendeeColorsFor("success", true).primary)
    }

    @Test
    fun warningIsNotAnAccent() {
        val colors = kalendeeColorsFor("warning", dark = true)
        assertEquals(kalendeeDarkColors.primary, colors.primary)
    }

    @Test
    fun unknownAccentFallsBackToPrimary() {
        val colors = kalendeeColorsFor("chartreuse", dark = true)
        assertEquals(kalendeeDarkColors.primary, colors.primary)
        assertEquals(kalendeeDarkColors.primaryContent, colors.primaryContent)
    }

    @Test
    fun hexAccentIsAcceptedForCompatibility() {
        val colors = kalendeeColorsFor("#ff8800", dark = true)
        assertEquals(Color(0xFFFF8800), colors.primary)
        assertEquals(colors.contentFor(Color(0xFFFF8800)), colors.primaryContent)
    }

    @Test
    fun accentRemapsAroundLightSurfacesToo() {
        val colors = kalendeeColorsFor("accent", dark = false)
        assertEquals(kalendeeLightColors.accent, colors.primary)
        assertEquals(kalendeeLightColors.base100, colors.base100)
    }

    @Test
    fun contentForPicksContrastingInk() {
        // Bright fill -> dark ink; dark fill -> light ink, in both themes.
        assertEquals(
            kalendeeDarkColors.base100,
            kalendeeDarkColors.contentFor(oklch(0.9, 0.1, 90.0)),
        )
        assertEquals(
            kalendeeDarkColors.baseContent,
            kalendeeDarkColors.contentFor(oklch(0.3, 0.05, 250.0)),
        )
        assertEquals(
            kalendeeLightColors.baseContent,
            kalendeeLightColors.contentFor(oklch(0.9, 0.1, 90.0)),
        )
        assertEquals(
            kalendeeLightColors.base100,
            kalendeeLightColors.contentFor(oklch(0.3, 0.05, 250.0)),
        )
    }
}
