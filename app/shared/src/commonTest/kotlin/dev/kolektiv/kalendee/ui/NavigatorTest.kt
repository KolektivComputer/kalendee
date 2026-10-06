package dev.kolektiv.kalendee.ui

import dev.kolektiv.kalendee.ui.nav.Navigator
import dev.kolektiv.kalendee.ui.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigatorTest {

    @Test
    fun startsAtServers() {
        assertEquals(Route.Servers, Navigator().current)
    }

    @Test
    fun pushAndPopMaintainBackStack() {
        val navigator = Navigator()
        navigator.push(Route.Calendar)
        navigator.push(Route.Settings)

        assertEquals(listOf(Route.Servers, Route.Calendar, Route.Settings), navigator.stack.value)
        assertEquals(Route.Settings, navigator.current)
        assertTrue(navigator.canPop)

        assertTrue(navigator.pop())
        assertEquals(Route.Calendar, navigator.current)
        assertTrue(navigator.pop())
        assertFalse(navigator.pop())
        assertEquals(Route.Servers, navigator.current)
    }

    @Test
    fun replaceRootClearsTheStack() {
        val navigator = Navigator()
        navigator.push(Route.Calendar)
        navigator.push(Route.Reminders)
        navigator.replaceRoot(Route.Calendar)

        assertEquals(listOf(Route.Calendar), navigator.stack.value)
        assertFalse(navigator.canPop)
    }

    @Test
    fun routesCarryTheirArguments() {
        val login = Route.Login("server-1")
        val editor = Route.EventEditor(serverId = "server-1", calendarId = "cal-1", eventId = null)
        val detail = Route.EventDetail(serverId = "server-1", eventId = "event-1")

        assertEquals("server-1", login.serverId)
        assertEquals(null, editor.eventId)
        assertEquals("cal-1", editor.calendarId)
        assertEquals("event-1", detail.eventId)
    }
}
