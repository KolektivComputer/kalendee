package dev.kolektiv.kalendee.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.calendar.CalendarView
import dev.kolektiv.kalendee.notify.platformNotificationScheduler
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.components.PageColumn
import dev.kolektiv.kalendee.ui.components.ScreenTitle
import dev.kolektiv.kalendee.ui.components.SectionTitle
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.components.DButton
import dev.kolektiv.kalendee.ui.design.components.DButtonVariant
import dev.kolektiv.kalendee.ui.design.components.DCard
import dev.kolektiv.kalendee.ui.design.components.DJoinGroup
import dev.kolektiv.kalendee.ui.design.components.DJoinItem
import dev.kolektiv.kalendee.ui.design.semibold
import kotlinx.coroutines.launch

private val DefaultViewOptions = listOf(CalendarView.Day, CalendarView.Week, CalendarView.Month)

@Composable
fun BehaviorScreen(state: AppState) {
    val ui by state.state.collectAsState()
    val colors = LocalKalendeeColors.current
    val scope = rememberCoroutineScope()
    var permission by remember { mutableStateOf<Boolean?>(null) }
    var requesting by remember { mutableStateOf(false) }
    var rescheduling by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    PageColumn {
        ScreenTitle(title = "Behavior", onBack = { state.navigator.pop() })

        SectionTitle("Default calendar view")
        DJoinGroup(modifier = Modifier.fillMaxWidth()) {
            DefaultViewOptions.forEachIndexed { index, view ->
                DJoinItem(
                    onClick = { state.setDefaultView(view) },
                    selected = ui.defaultView == view,
                    leadingDivider = index > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    DText(view.name, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        DText(
            text = "The calendar opens in this view. Month is a preview and falls back to Week.",
            style = DType.xs,
            color = colors.mutedContent,
        )

        SectionTitle("Notifications")
        DCard(modifier = Modifier.fillMaxWidth()) {
            DText(text = "On-device reminders", style = DType.base.semibold())
            Spacer(Modifier.height(4.dp))
            DText(
                text = "Upcoming reminders are delivered as local notifications so they can fire " +
                    "while the app is closed. Nothing extra is stored on your server.",
                style = DType.sm,
                color = colors.mutedContent,
            )
            Spacer(Modifier.height(12.dp))
            val status = when (permission) {
                true -> "Notifications are allowed on this device."
                false -> "Notifications are not allowed. You can still see reminders in Upcoming."
                null -> "Permission not requested yet."
            }
            DText(
                text = status,
                style = DType.xs,
                color = if (permission == false) colors.error else colors.mutedContent,
            )
            Spacer(Modifier.height(12.dp))
            DButton(
                onClick = {
                    requesting = true
                    scope.launch {
                        permission = runCatching { platformNotificationScheduler().requestPermission() }
                            .getOrDefault(false)
                        requesting = false
                    }
                },
                loading = requesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                DText("Enable notifications")
            }
            Spacer(Modifier.height(8.dp))
            DButton(
                onClick = {
                    rescheduling = true
                    message = null
                    scope.launch {
                        runCatching { state.refreshReminders(forceReschedule = true) }
                        val count = state.state.value.upcoming.size
                        message = "Scheduled $count reminder(s) on this device."
                        rescheduling = false
                    }
                },
                loading = rescheduling,
                variant = DButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                DText("Reschedule reminders")
            }
            message?.let { text ->
                Spacer(Modifier.height(8.dp))
                DText(
                    text = text,
                    style = DType.xs,
                    color = colors.mutedContent,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
