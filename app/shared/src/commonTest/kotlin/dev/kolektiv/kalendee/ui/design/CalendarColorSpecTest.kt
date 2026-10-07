package dev.kolektiv.kalendee.ui.design

import androidx.compose.ui.graphics.Color
import dev.kolektiv.kalendee.calendar.colorFor
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarColorSpecTest {

    private val colors = kalendeeDarkColors

    @Test
    fun namedPaletteColorsUseTokens() {
        val primary = calendarColorSpec("primary", colors)
        assertEquals(colors.primary, primary.fill)
        assertEquals(colors.primaryContent, primary.content)

        val warning = calendarColorSpec("warning", colors)
        assertEquals(colors.warning, warning.fill)
        assertEquals(colors.warningContent, warning.content)

        val neutral = calendarColorSpec("neutral", colors)
        assertEquals(colors.neutral, neutral.fill)
        assertEquals(colors.neutralContent, neutral.content)
    }

    @Test
    fun namesAreTrimmedAndCaseInsensitive() {
        val spec = calendarColorSpec("  Error ", colors)
        assertEquals(colors.error, spec.fill)
        assertEquals(colors.errorContent, spec.content)
    }

    @Test
    fun hexColorsComputeContent() {
        val spec = calendarColorSpec("#ff8800", colors)
        assertEquals(Color(0xFFFF8800), spec.fill)
        assertEquals(colors.contentFor(Color(0xFFFF8800)), spec.content)
    }

    @Test
    fun oklchColorsComputeContent() {
        val spec = calendarColorSpec("oklch(66% 0.179 58.318)", colors)
        assertEquals(oklch(0.66, 0.179, 58.318), spec.fill)
        assertEquals(colors.contentFor(spec.fill), spec.content)
    }

    @Test
    fun unknownFallsBackToInfo() {
        val spec = calendarColorSpec("chartreuse", colors)
        assertEquals(colors.info, spec.fill)
        assertEquals(colors.infoContent, spec.content)
    }

    @Test
    fun blankFallsBackToInfoWithoutCalendarId() {
        val spec = calendarColorSpec("   ", colors)
        assertEquals(colors.info, spec.fill)
        assertEquals(colors.infoContent, spec.content)
    }

    @Test
    fun blankUsesCalendarIdHashWhenAvailable() {
        val spec = calendarColorSpec("", colors, calendarId = "work")
        val expected = colors.colorPair(colorFor("work"))
        assertEquals(expected?.first, spec.fill)
        assertEquals(expected?.second, spec.content)
    }

    @Test
    fun resolvedColorUsesAccentRemap() {
        val remapped = colors.withAccent("error")
        val spec = calendarColorSpec("primary", remapped)
        assertEquals(remapped.primary, spec.fill)
    }
}
