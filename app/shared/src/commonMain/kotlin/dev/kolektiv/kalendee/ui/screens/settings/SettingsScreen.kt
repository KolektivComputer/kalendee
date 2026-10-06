package dev.kolektiv.kalendee.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.calendar.CalendarView
import dev.kolektiv.kalendee.notify.platformNotificationScheduler
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.ThemeMode
import dev.kolektiv.kalendee.ui.components.Notice
import dev.kolektiv.kalendee.ui.components.PageColumn
import dev.kolektiv.kalendee.ui.components.ScreenTitle
import dev.kolektiv.kalendee.ui.components.SectionTitle
import dev.kolektiv.kalendee.ui.nav.Route
import kotlinx.coroutines.launch

private const val AppVersion = "0.1.0"

@Composable
fun SettingsScreen(state: AppState) {
    val ui by state.state.collectAsState()
    val scope = rememberCoroutineScope()
    var permission by remember { mutableStateOf<Boolean?>(null) }
    var notificationsMessage by remember { mutableStateOf<String?>(null) }
    var requesting by remember { mutableStateOf(false) }
    var rescheduling by remember { mutableStateOf(false) }

    PageColumn {
        ScreenTitle(title = "Settings")
        Notice(text = ui.notice, onDismiss = state::clearNotice)

        SectionTitle("Appearance")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                ThemeMode.entries.forEach { mode ->
                    SelectableRow(
                        label = when (mode) {
                            ThemeMode.System -> "System"
                            ThemeMode.Light -> "Light"
                            ThemeMode.Dark -> "Dark"
                        },
                        selected = ui.themeMode == mode,
                        onClick = { state.setThemeMode(mode) },
                    )
                }
            }
        }

        SectionTitle("Default view")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                listOf(CalendarView.Week, CalendarView.Day).forEach { view ->
                    SelectableRow(
                        label = view.name,
                        selected = ui.defaultView == view,
                        onClick = { state.setDefaultView(view) },
                    )
                }
            }
        }

        SectionTitle("Notifications")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val status = when (permission) {
                    true -> "Permission granted"
                    false -> "Permission not granted"
                    null -> "Permission not requested yet"
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        requesting = true
                        scope.launch {
                            permission = runCatching {
                                platformNotificationScheduler().requestPermission()
                            }.getOrDefault(false)
                            requesting = false
                        }
                    },
                    enabled = !requesting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (requesting) "Requesting…" else "Request permission")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        rescheduling = true
                        notificationsMessage = null
                        scope.launch {
                            runCatching { state.refreshReminders(forceReschedule = true) }
                            notificationsMessage =
                                "Scheduled ${state.state.value.upcoming.size} reminder(s)."
                            rescheduling = false
                        }
                    },
                    enabled = !rescheduling,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (rescheduling) "Rescheduling…" else "Reschedule reminders")
                }
                notificationsMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionTitle("Servers")
        Button(
            onClick = { state.navigator.push(Route.Servers) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Manage servers")
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Kalendee $AppVersion",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Self-hosted calendar client",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
