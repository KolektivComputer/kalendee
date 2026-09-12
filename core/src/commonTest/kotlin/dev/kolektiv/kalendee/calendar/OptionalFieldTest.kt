package dev.kolektiv.kalendee.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class OptionalFieldTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    @Test
    fun omittedPatchFieldStaysAbsent() {
        val decoded = json.decodeFromString(UpdateCalendar.serializer(), """{"displayName":"Home"}""")
        assertEquals("Home", decoded.displayName)
        assertEquals(OptionalField.Absent, decoded.description)
    }

    @Test
    fun explicitNullIsPresentNull() {
        val decoded = json.decodeFromString(
            UpdateCalendar.serializer(),
            """{"description":null}""",
        )
        assertEquals(OptionalField.Present(null), decoded.description)
    }

    @Test
    fun absentIsOmittedEvenWithEncodeDefaults() {
        val encoded = json.encodeToString(
            UpdateCalendar.serializer(),
            UpdateCalendar(displayName = "Home"),
        )
        assertTrue("displayName" in encoded)
        assertFalse("description" in encoded)
    }

    @Test
    fun presentNullIsEncoded() {
        val encoded = json.encodeToString(
            UpdateCalendar.serializer(),
            UpdateCalendar(description = OptionalField.Present(null)),
        )
        assertTrue(""""description":null""" in encoded.replace(" ", ""))
    }

    @Test
    fun getOrElseKeepsExistingWhenAbsent() {
        assertEquals("Office", OptionalField.Absent.getOrElse("Office"))
        assertEquals(null, OptionalField.Present(null).getOrElse("Office"))
        assertEquals("Home", OptionalField.Present("Home").getOrElse("Office"))
    }
}
