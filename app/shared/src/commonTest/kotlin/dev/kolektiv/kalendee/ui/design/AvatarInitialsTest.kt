package dev.kolektiv.kalendee.ui.design

import dev.kolektiv.kalendee.ui.design.components.avatarInitials
import kotlin.test.Test
import kotlin.test.assertEquals

class AvatarInitialsTest {

    @Test
    fun takesFirstLettersOfTwoWords() {
        assertEquals("AL", avatarInitials("ada lovelace"))
        assertEquals("KC", avatarInitials("Kalendee Calendar"))
    }

    @Test
    fun handlesSingleWordAndWhitespace() {
        assertEquals("A", avatarInitials("ada"))
        assertEquals("A", avatarInitials("   ada   "))
        assertEquals("", avatarInitials("   "))
    }

    @Test
    fun usesAtMostTwoInitials() {
        assertEquals("AB", avatarInitials("a b c d"))
    }

    @Test
    fun uppercases() {
        assertEquals("JD", avatarInitials("john doe"))
    }
}
