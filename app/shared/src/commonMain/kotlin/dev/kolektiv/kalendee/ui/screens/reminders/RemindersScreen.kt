package dev.kolektiv.kalendee.ui.screens.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.notify.platformNotificationScheduler
import dev.kolektiv.kalendee.ui.AggregatedNotification
import dev.kolektiv.kalendee.ui.AggregatedReminder
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.AppUiState
import dev.kolektiv.kalendee.ui.ReminderLimit
import dev.kolektiv.kalendee.ui.components.InlineError
import dev.kolektiv.kalendee.ui.components.PageColumn
import dev.kolektiv.kalendee.ui.components.ScreenTitle
import dev.kolektiv.kalendee.ui.components.messageOf
import dev.kolektiv.kalendee.ui.nav.Route
import dev.kolektiv.kalendee.ui.theme.accentSpec
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Reminders and in-app notifications. Reminders fire as on-device notifications;
 * the in-app list mirrors the server notification feed (`/api/v1/notifications`).
 */
@Composable
fun RemindersScreen(state: AppState) {
    val ui by state.state.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val unread = unreadNotificationCount(ui.notifications)

    PageColumn {
        ScreenTitle(
            title = "Reminders",
            subtitle = when {
                tab == 0 && ui.upcoming.isNotEmpty() -> "${ui.upcoming.size} upcoming"
                tab == 1 && ui.notifications.isNotEmpty() -> if (unread > 0) "$unread unread" else "All read"
                else -> null
            },
        )
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("Reminders") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(if (unread > 0) "Notifications ($unread)" else "Notifications") },
            )
        }
        Spacer(Modifier.height(12.dp))
        if (tab == 0) {
            RemindersTab(state, ui)
        } else {
            NotificationsTab(state, ui)
        }
    }
}

@Composable
private fun RemindersTab(state: AppState, ui: AppUiState) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var permission by remember { mutableStateOf<Boolean?>(null) }
    var requesting by remember { mutableStateOf(false) }
    var hideInactive by remember { mutableStateOf(false) }

    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.now().toLocalDateTime(zone).date

    PermissionCard(
        permission = permission,
        requesting = requesting,
        onRequest = {
            requesting = true
            scope.launch {
                permission = runCatching { platformNotificationScheduler().requestPermission() }
                    .getOrDefault(false)
                requesting = false
            }
        },
    )

    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                if (refreshing) return@OutlinedButton
                refreshing = true
                refreshError = null
                state.scope.launch {
                    runCatching { state.refreshReminders(forceReschedule = true) }
                        .onFailure { refreshError = messageOf(it) }
                    refreshing = false
                }
            },
            enabled = !refreshing,
        ) {
            Text("Refresh")
        }
        if (refreshing) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = "${scheduledReminderCount(ui.upcoming)} of $ReminderLimit notification(s) scheduled",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    InlineError(refreshError)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(
            text = "Hide signed-out servers",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = hideInactive, onCheckedChange = { hideInactive = it })
    }

    val visible = if (hideInactive) {
        filterRemindersForActiveServers(ui.upcoming, ui.servers)
    } else {
        ui.upcoming
    }
    val days = groupRemindersByDay(visible, zone, today)
    if (days.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        ReminderEmptyCard(state)
    } else {
        days.forEach { day ->
            Text(
                text = day.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    day.reminders.forEachIndexed { index, reminder ->
                        if (index > 0) HorizontalDivider()
                        ReminderRow(
                            reminder = reminder,
                            zone = zone,
                            onOpen = {
                                state.navigator.push(Route.EventDetail(reminder.serverId, reminder.eventId))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(permission: Boolean?, requesting: Boolean, onRequest: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Reminders as notifications", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Upcoming reminders are delivered as on-device notifications so they can " +
                    "fire while the app is closed. Allow notifications to keep them coming.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRequest,
                enabled = !requesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (requesting) "Checking…" else "Enable notifications")
            }
            permission?.let { granted ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (granted) {
                        "Notifications are enabled on this device."
                    } else {
                        "Notifications are not available on this device. You can still see every " +
                            "reminder below."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun ReminderRow(reminder: AggregatedReminder, zone: TimeZone, onOpen: () -> Unit) {
    val canOpen = reminder.eventId.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canOpen) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = reminderTime(reminder.at, zone),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(52.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = reminder.title.ifBlank { "Untitled event" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CalendarColorDot(reminder.calendarColor)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${reminder.serverName} • ${reminder.calendarName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CalendarColorDot(raw: String) {
    val spec = accentSpec(raw)
    val onDarkBackground = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (onDarkBackground) spec.dark else spec.light),
    )
}

@Composable
private fun ReminderEmptyCard(state: AppState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("No upcoming reminders", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Reminders come from the reminder settings on your calendar events. Add a " +
                    "reminder to an event on your server and it will show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { state.navigator.replaceRoot(Route.Settings) }) {
                Text("Open Settings")
            }
        }
    }
}

@Composable
private fun NotificationsTab(state: AppState, ui: AppUiState) {
    var refreshing by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        error = null
        state.scope.launch {
            runCatching { state.refreshNotifications() }
                .onFailure { error = messageOf(it) }
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        if (!loadedOnce) {
            loadedOnce = true
            refresh()
        }
    }

    val unread = unreadNotificationCount(ui.notifications)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { refresh() }, enabled = !refreshing) {
            Text("Refresh")
        }
        if (refreshing) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = { state.scope.launch { runCatching { state.markAllNotificationsRead() } } },
            enabled = !refreshing && unread > 0,
        ) {
            Text("Mark all read")
        }
    }
    InlineError(error)

    val items = sortNotificationsNewestFirst(ui.notifications)
    when {
        items.isEmpty() && refreshing -> {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        }

        items.isEmpty() -> {
            Spacer(Modifier.height(4.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("No notifications yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Invites, RSVPs, and other activity from your servers will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        else -> {
            val now = Clock.System.now()
            Spacer(Modifier.height(4.dp))
            items.forEach { item ->
                NotificationRow(
                    item = item,
                    now = now,
                    onClick = {
                        if (!item.notification.read) {
                            state.scope.launch {
                                runCatching { state.markNotificationRead(item.notification.id) }
                            }
                        }
                        notificationEventId(item.notification.href)?.let { eventId ->
                            state.navigator.push(Route.EventDetail(item.serverId, eventId))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NotificationRow(item: AggregatedNotification, now: Instant, onClick: () -> Unit) {
    val notification = item.notification
    val unread = !notification.read
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unread) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = notification.title.ifBlank { "Notification" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            notification.body?.takeIf { it.isNotBlank() }?.let { body ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${item.serverName} • ${notificationTimestamp(notification.createdAt, now)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
