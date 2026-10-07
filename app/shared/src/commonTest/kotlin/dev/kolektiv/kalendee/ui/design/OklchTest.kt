package dev.kolektiv.kalendee.ui.design

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OklchTest {

    @Test
    fun whiteIsWhite() {
        assertColorEquals(Color.White, oklch(1.0, 0.0, 0.0))
    }

    @Test
    fun blackIsBlack() {
        assertColorEquals(Color.Black, oklch(0.0, 0.0, 0.0))
    }

    @Test
    fun cssRedMatchesSrgbRed() {
        // CSS `red` (#ff0000) expressed in oklch; reference from the CSS Color 4 spec.
        val red = oklch(0.62796, 0.25768, 29.234)
        assertEquals(1.0f, red.red, 0.005f)
        assertEquals(0.0f, red.green, 0.005f)
        assertEquals(0.0f, red.blue, 0.005f)
    }

    @Test
    fun neutralLightnessMatchesReferenceGamma() {
        // oklch(26% 0 0): linear 0.01758 -> sRGB 0.140869 (computed independently).
        val gray = oklch(0.26, 0.0, 0.0)
        assertEquals(0.140869f, gray.red, 0.005f)
        assertEquals(0.140869f, gray.green, 0.005f)
        assertEquals(0.140869f, gray.blue, 0.005f)
    }

    @Test
    fun chromaticPrimaryMatchesReference() {
        // oklch(66% 0.179 58.318), clipped to sRGB.
        val primary = oklch(0.66, 0.179, 58.318)
        assertEquals(0.875607f, primary.red, 0.005f)
        assertEquals(0.435883f, primary.green, 0.005f)
        assertEquals(0.0f, primary.blue, 0.005f)
    }

    @Test
    fun parsesHex() {
        assertEquals(Color(0xFFFF8800), parseHexColor("#ff8800"))
        assertEquals(Color(0xFF00FF00), parseHexColor("  #00FF00 "))
        assertNull(parseHexColor("#abc"))
        assertNull(parseHexColor("primary"))
    }

    @Test
    fun parsesOklchWithPercentAndDeg() {
        val percent = parseOklchColor("oklch(66% 0.179 58.318)")
        val plain = parseOklchColor("oklch(0.66 0.179 58.318)")
        assertNotNull(percent)
        assertNotNull(plain)
        assertColorEquals(plain, percent)
        assertColorEquals(oklch(0.66, 0.179, 58.318), percent)
        assertNotNull(parseOklchColor("oklch(0.7 0.1 250deg)"))
        assertNull(parseOklchColor("oklch(bogus)"))
    }

    @Test
    fun parseColorAcceptsHexAndOklch() {
        assertEquals(Color(0xFF112233), parseColor("#112233"))
        assertNotNull(parseColor("oklch(0.5 0.05 200)"))
        assertNull(parseColor("chartreuse"))
    }

    @Test
    fun darkTokensMatchWebValues() {
        // base-300 and base-content sanity checks against the CSS source values.
        assertEquals(0.140869f, kalendeeDarkColors.base300.red, 0.005f)
        assertEquals(0.960585f, kalendeeDarkColors.baseContent.red, 0.005f)
        assertTrue(kalendeeDarkColors.primary.red > kalendeeDarkColors.primary.green)
    }

    @Test
    fun lightVariantInvertsSurfacesAndKeepsAccents() {
        assertEquals(kalendeeDarkColors.primary, kalendeeLightColors.primary)
        assertEquals(kalendeeDarkColors.error, kalendeeLightColors.error)
        assertTrue(kalendeeLightColors.base100.red > 0.9f)
        assertTrue(kalendeeLightColors.baseContent.red < 0.2f)
    }

    private fun assertColorEquals(expected: Color, actual: Color, tolerance: Float = 0.002f) {
        assertTrue(
            abs(expected.red - actual.red) <= tolerance &&
                abs(expected.green - actual.green) <= tolerance &&
                abs(expected.blue - actual.blue) <= tolerance,
            "expected $expected but was $actual",
        )
    }
}
