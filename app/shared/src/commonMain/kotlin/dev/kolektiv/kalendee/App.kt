package dev.kolektiv.kalendee

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.ThemeMode
import dev.kolektiv.kalendee.ui.nav.Route
import dev.kolektiv.kalendee.ui.rememberAppState
import dev.kolektiv.kalendee.ui.screens.auth.LoginScreen
import dev.kolektiv.kalendee.ui.screens.auth.RegisterScreen
import dev.kolektiv.kalendee.ui.screens.calendar.CalendarScreen
import dev.kolektiv.kalendee.ui.screens.events.EventDetailScreen
import dev.kolektiv.kalendee.ui.screens.events.EventEditorScreen
import dev.kolektiv.kalendee.ui.screens.reminders.RemindersScreen
import dev.kolektiv.kalendee.ui.screens.servers.ServersScreen
import dev.kolektiv.kalendee.ui.screens.settings.SettingsScreen
import dev.kolektiv.kalendee.ui.theme.KalendeeTheme

@Composable
@Preview
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
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Box(modifier = Modifier.weight(1f)) {
                    when (route) {
                        Route.Servers -> ServersScreen(state)
                        is Route.Login -> LoginScreen(state, route.serverId)
                        is Route.Register -> RegisterScreen(state, route.serverId)
                        Route.Calendar -> CalendarScreen(state)
                        Route.Reminders -> RemindersScreen(state)
                        Route.Settings -> SettingsScreen(state)
                        is Route.EventEditor ->
                            EventEditorScreen(state, route.serverId, route.calendarId, route.eventId)
                        is Route.EventDetail -> EventDetailScreen(state, route.serverId, route.eventId)
                    }
                }
                if (showBottomBar(hasServers = ui.servers.isNotEmpty(), route = route)) {
                    BottomBar(
                        current = route,
                        onSelect = { tab -> state.navigator.replaceRoot(tab) },
                    )
                }
            }
        }
    }
}

private fun showBottomBar(hasServers: Boolean, route: Route): Boolean =
    hasServers && when (route) {
        Route.Calendar, Route.Reminders, Route.Settings -> true
        else -> false
    }

@Composable
private fun BottomBar(current: Route, onSelect: (Route) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BottomBarItem("Calendar", current == Route.Calendar) { onSelect(Route.Calendar) }
            BottomBarItem("Reminders", current == Route.Reminders) { onSelect(Route.Reminders) }
            BottomBarItem("Settings", current == Route.Settings) { onSelect(Route.Settings) }
        }
    }
}

@Composable
private fun BottomBarItem(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Text(label)
    }
}
