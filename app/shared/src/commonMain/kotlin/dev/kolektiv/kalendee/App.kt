package dev.kolektiv.kalendee

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.kolektiv.kalendee.ui.ThemeMode
import dev.kolektiv.kalendee.ui.design.KalendeeTheme
import dev.kolektiv.kalendee.ui.nav.Route
import dev.kolektiv.kalendee.ui.rememberAppState
import dev.kolektiv.kalendee.ui.screens.auth.LoginScreen
import dev.kolektiv.kalendee.ui.screens.auth.RegisterScreen
import dev.kolektiv.kalendee.ui.screens.calendar.CalendarScreen
import dev.kolektiv.kalendee.ui.screens.events.EventDetailScreen
import dev.kolektiv.kalendee.ui.screens.events.EventEditorScreen
import dev.kolektiv.kalendee.ui.screens.reminders.RemindersScreen
import dev.kolektiv.kalendee.ui.screens.servers.AddServerScreen
import dev.kolektiv.kalendee.ui.screens.servers.ServerDetailScreen
import dev.kolektiv.kalendee.ui.screens.servers.ServersScreen
import dev.kolektiv.kalendee.ui.screens.settings.AppearanceScreen
import dev.kolektiv.kalendee.ui.screens.settings.BehaviorScreen
import dev.kolektiv.kalendee.ui.screens.settings.SettingsScreen
import dev.kolektiv.kalendee.ui.shell.AppShell

@Composable
fun App() {
    val state = rememberAppState()
    val ui by state.state.collectAsState()
    val stack by state.navigator.stack.collectAsState()
    val route: Route = stack.lastOrNull() ?: Route.Servers
    val dark = when (ui.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    KalendeeTheme(accent = ui.accent, dark = dark) {
        AppShell(state = state, route = route) {
            when (route) {
                Route.Servers -> ServersScreen(state)
                is Route.Login -> LoginScreen(state, route.serverId)
                is Route.Register -> RegisterScreen(state, route.serverId)
                Route.Calendar -> CalendarScreen(state)
                Route.Upcoming -> RemindersScreen(state)
                Route.Settings -> SettingsScreen(state)
                is Route.ServerDetail -> ServerDetailScreen(state, route.serverId)
                Route.Appearance -> AppearanceScreen(state)
                Route.Behavior -> BehaviorScreen(state)
                Route.AddServer -> AddServerScreen(state)
                is Route.EventEditor ->
                    EventEditorScreen(state, route.serverId, route.calendarId, route.eventId)
                is Route.EventDetail -> EventDetailScreen(state, route.serverId, route.eventId)
            }
        }
    }
}
