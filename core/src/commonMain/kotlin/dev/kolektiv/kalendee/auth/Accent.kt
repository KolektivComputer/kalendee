package dev.kolektiv.kalendee.auth

import dev.kolektiv.kalendee.calendar.CalendarException

object Accent {
    const val Default = "primary"

    val ids = listOf("primary", "secondary", "accent", "info", "success", "error")

    fun require(value: String): String {
        val id = value.trim().lowercase()
        if (id !in ids) {
            throw CalendarException.Invalid("accent must be one of ${ids.joinToString()}")
        }
        return id
    }
}
