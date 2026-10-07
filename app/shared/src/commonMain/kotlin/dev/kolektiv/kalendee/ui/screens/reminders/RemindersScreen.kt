package dev.kolektiv.kalendee.ui.screens.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.components.DButton
import dev.kolektiv.kalendee.ui.design.components.DButtonVariant
import dev.kolektiv.kalendee.ui.design.components.DCard
import dev.kolektiv.kalendee.ui.design.components.DIcon
import dev.kolektiv.kalendee.ui.design.components.DListItem
import dev.kolektiv.kalendee.ui.design.components.DListSection
import dev.kolektiv.kalendee.ui.design.components.DSpinner
import dev.kolektiv.kalendee.ui.design.components.DSwitch
import dev.kolektiv.kalendee.ui.design.components.DTabs
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.design.rememberCalendarColorSpec
import dev.kolektiv.kalendee.ui.icons.Lucide
import dev.kolektiv.kalendee.ui.nav.Route
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Upcoming reminders and in-app notifications.
 *
 * Reminders fire as on-device notifications; the notification list mirrors the
 * server feed (`/api/v1/notifications`). Notification hrefs are never opened
 * directly — only resolvable event ids navigate.
 */
@Composable
fun RemindersScreen(state: AppState) {
    val ui by state.state.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val unread = unreadNotificationCount(ui.notifications)

    PageColumn {
        ScreenTitle(
            title = "Upcoming",
            subtitle = when {
                tab == 0 && ui.upcoming.isNotEmpty() -> "${ui.upcoming.size} upcoming"
                tab == 1 && ui.notifications.isNotEmpty() ->
                    if (unread > 0) "$unread unread" else "All read"

                else -> null
            },
        )
        DTabs(
            items = listOf(
                "Upcoming",
                if (unread > 0) "Notifications ($unread)" else "Notifications",
            ),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        when (tab) {
            0 -> UpcomingTab(state, ui)
            else -> NotificationsTab(state, ui)
        }
    }
}

@Composable
private fun UpcomingTab(state: AppState, ui: AppUiState) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var rescheduling by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var permission by remember { mutableStateOf<Boolean?>(null) }
    var requesting by remember { mutableStateOf(false) }
    var hideInactive by remember { mutableStateOf(false) }
    val colors = LocalKalendeeColors.current

    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.now().toLocalDateTime(zone).date

    fun refresh(force: Boolean) {
        if (refreshing || rescheduling) return
        if (force) rescheduling = true else refreshing = true
        refreshError = null
        scope.launch {
            runCatching { state.refreshReminders(forceReschedule = force) }
                .onFailure { refreshError = messageOf(it) }
            if (force) rescheduling = false else refreshing = false
        }
    }

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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DButton(
            onClick = { refresh(force = false) },
            loading = refreshing,
            variant = DButtonVariant.Secondary,
        ) {
            DText("Refresh")
        }
        DButton(
            onClick = { refresh(force = true) },
            loading = rescheduling,
            variant = DButtonVariant.Ghost,
        ) {
            DText("Reschedule")
        }
    }
    Spacer(Modifier.height(8.dp))
    DText(
        text = "${scheduledReminderCount(ui.upcoming)} of $ReminderLimit notification(s) scheduled",
        style = DType.xs,
        color = colors.mutedContent,
    )
    InlineError(refreshError)

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DText(
            text = "Hide signed-out servers",
            style = DType.sm,
            modifier = Modifier.weight(1f),
        )
        DSwitch(checked = hideInactive, onCheckedChange = { hideInactive = it })
    }

    val visible = if (hideInactive) {
        filterRemindersForActiveServers(ui.upcoming, ui.servers)
    } else {
        ui.upcoming
    }
    val days = groupRemindersByDay(visible, zone, today)
    if (days.isEmpty()) {
        Spacer(Modifier.height(12.dp))
        EmptyCard(
            title = "No upcoming reminders",
            body = "Reminders come from the reminder settings on your calendar events. Add a " +
                "reminder to an event on your server and it will show up here.",
        ) {
            DButton(
                onClick = { state.navigator.replaceRoot(Route.Settings) },
                variant = DButtonVariant.Secondary,
                ) {
                DText("Open Settings")
            }
        }
    } else {
        days.forEach { day ->
            DListSection(label = day.label) {
                DCard(contentPadding = PaddingValues(0.dp)) {
                    day.reminders.forEach { reminder ->
                        ReminderRow(
                            reminder = reminder,
                            zone = zone,
                            onOpen = {
                                state.navigator.push(
                                    Route.EventDetail(reminder.serverId, reminder.eventId),
                                )
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
    val colors = LocalKalendeeColors.current
    DCard(modifier = Modifier.fillMaxWidth()) {
        DText(text = "Reminders as notifications", style = DType.base.semibold())
        Spacer(Modifier.height(4.dp))
        DText(
            text = "Upcoming reminders are delivered as on-device notifications so they can fire " +
                "while the app is closed. Allow notifications to keep them coming.",
            style = DType.sm,
            color = colors.mutedContent,
        )
        Spacer(Modifier.height(12.dp))
        DButton(
            onClick = onRequest,
            loading = requesting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            DText("Enable notifications")
        }
        permission?.let { granted ->
            Spacer(Modifier.height(8.dp))
            DText(
                text = if (granted) {
                    "Notifications are enabled on this device."
                } else {
                    "Notifications are not available on this device. You can still see every " +
                        "reminder below."
                },
                style = DType.xs,
                color = if (granted) colors.success else colors.mutedContent,
            )
        }
    }
}

@Composable
private fun ReminderRow(reminder: AggregatedReminder, zone: TimeZone, onOpen: () -> Unit) {
    val colors = LocalKalendeeColors.current
    val spec = rememberCalendarColorSpec(reminder.calendarColor, reminder.calendarId)
    val canOpen = reminder.eventId.isNotBlank()
    DListItem(
        title = reminder.title.ifBlank { "Untitled event" },
        subtitle = "${reminder.serverName} • ${reminder.calendarName}",
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(spec.fill),
                )
                Spacer(Modifier.width(8.dp))
                DText(
                    text = reminderTime(reminder.at, zone),
                    style = DType.sm.semibold(),
                    modifier = Modifier.width(44.dp),
                )
            }
        },
        trailing = if (canOpen) {
            {
                DIcon(icon = Lucide.ChevronRight, tint = colors.mutedContent, size = 16.dp)
            }
        } else {
            null
        },
        onClick = if (canOpen) onOpen else null,
    )
}

@Composable
private fun NotificationsTab(state: AppState, ui: AppUiState) {
    val scope = rememberCoroutineScope()
    val colors = LocalKalendeeColors.current
    var refreshing by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        error = null
        scope.launch {
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DButton(
            onClick = { refresh() },
            loading = refreshing,
            variant = DButtonVariant.Secondary,
        ) {
            DText("Refresh")
        }
        DButton(
            onClick = { state.scope.launch { runCatching { state.markAllNotificationsRead() } } },
            enabled = !refreshing && unread > 0,
            variant = DButtonVariant.Ghost,
        ) {
            DText("Mark all read")
        }
    }
    InlineError(error)

    val items = sortNotificationsNewestFirst(ui.notifications)
    when {
        items.isEmpty() && refreshing -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                DSpinner(size = 24.dp)
            }
        }

        items.isEmpty() -> {
            Spacer(Modifier.height(12.dp))
            EmptyCard(
                title = "No notifications yet",
                body = "Invites, RSVPs, and other activity from your servers will show up here.",
            )
        }

        else -> {
            val now = Clock.System.now()
            Spacer(Modifier.height(12.dp))
            items.forEach { item ->
                NotificationCard(
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
private fun NotificationCard(
    item: AggregatedNotification,
    now: Instant,
    onClick: () -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val notification = item.notification
    val unread = !notification.read
    DCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (unread) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp, end = 8.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.primary),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                DText(
                    text = notification.title.ifBlank { "Notification" },
                    style = if (unread) DType.base.semibold() else DType.base,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                notification.body?.takeIf { it.isNotBlank() }?.let { body ->
                    Spacer(Modifier.height(2.dp))
                    DText(
                        text = body,
                        style = DType.sm,
                        color = colors.mutedContent,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                DText(
                    text = "${item.serverName} • ${notificationTimestamp(notification.createdAt, now)}",
                    style = DType.xs,
                    color = colors.mutedContent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyCard(
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = LocalKalendeeColors.current
    DCard(modifier = Modifier.fillMaxWidth()) {
        DText(text = title, style = DType.base.semibold())
        Spacer(Modifier.height(4.dp))
        DText(
            text = body,
            style = DType.sm,
            color = colors.mutedContent,
        )
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}
