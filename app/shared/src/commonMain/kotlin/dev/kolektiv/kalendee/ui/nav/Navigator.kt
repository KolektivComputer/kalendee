package dev.kolektiv.kalendee.ui.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every screen the app can show. Wave 3 fills in the event routes. */
sealed interface Route {
    data object Servers : Route

    data class Login(val serverId: String) : Route

    data class Register(val serverId: String) : Route

    data object Calendar : Route

    data object Reminders : Route

    data object Settings : Route

    data class EventEditor(val serverId: String, val calendarId: String, val eventId: String? = null) : Route

    data class EventDetail(val serverId: String, val eventId: String) : Route
}

/**
 * Minimal hand-rolled back stack. All mutations are synchronous and expected to be
 * driven from the UI thread; [stack] exposes the list for Compose collection.
 */
class Navigator(initial: Route = Route.Servers) {
    private val backStack = MutableStateFlow(listOf(initial))

    val stack: StateFlow<List<Route>> = backStack.asStateFlow()

    val current: Route
        get() = backStack.value.last()

    val canPop: Boolean
        get() = backStack.value.size > 1

    fun push(route: Route) {
        backStack.value = backStack.value + route
    }

    fun pop(): Boolean {
        val stack = backStack.value
        if (stack.size <= 1) return false
        backStack.value = stack.dropLast(1)
        return true
    }

    /** Clears the stack and makes [route] the only entry (tab switching, sign-in). */
    fun replaceRoot(route: Route) {
        backStack.value = listOf(route)
    }
}
