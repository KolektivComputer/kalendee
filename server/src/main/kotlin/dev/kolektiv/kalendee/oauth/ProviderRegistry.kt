package dev.kolektiv.kalendee.oauth

import dev.kolektiv.kalendee.calendar.CalendarException

class ProviderRegistry(providers: List<CalendarProvider>) {
    private val providers: List<CalendarProvider> = providers.toList()
    private val byId: Map<String, CalendarProvider> = providers.associateBy { it.id }

    fun all(): List<CalendarProvider> = providers

    fun enabled(): List<CalendarProvider> = providers.filter { it.enabled }

    fun byId(id: String): CalendarProvider? = byId[id]

    fun require(id: String): CalendarProvider =
        byId[id] ?: throw CalendarException.NotFound("unknown calendar provider")
}
