package dev.kolektiv.kalendee.ui.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.calendar.CalendarView
import dev.kolektiv.kalendee.ui.ServerUi
import dev.kolektiv.kalendee.ui.components.InlineError
import dev.kolektiv.kalendee.ui.theme.accentSpec

/**
 * Period header: range label from the core view window, prev/next/Today controls, a
 * Week/Day toggle and the calendar filter entry point.
 */
@Composable
fun CalendarToolbar(
    label: String,
    view: CalendarView,
    filtersOpen: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onViewSelected: (CalendarView) -> Unit,
    onToggleFilters: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrevious) { Text("Prev") }
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onNext) { Text("Next") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToday) { Text("Today") }
                Spacer(modifier = Modifier.weight(1f))
                ViewToggleButton(
                    text = "Week",
                    selected = view == CalendarView.Week,
                    onClick = { onViewSelected(CalendarView.Week) },
                )
                ViewToggleButton(
                    text = "Day",
                    selected = view == CalendarView.Day,
                    onClick = { onViewSelected(CalendarView.Day) },
                )
                ViewToggleButton(
                    text = "Calendars",
                    selected = filtersOpen,
                    onClick = onToggleFilters,
                )
            }
        }
    }
}

@Composable
private fun ViewToggleButton(text: String, selected: Boolean, onClick: () -> Unit) {
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
        Text(text)
    }
}

/** Checkbox list of calendars grouped by server, wired to per-calendar visibility. */
@Composable
fun CalendarFilterPanel(
    servers: List<ServerUi>,
    onToggleVisibility: (serverId: String, calendarId: String, visible: Boolean) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val signedIn = servers.filter { it.signedIn }
            if (signedIn.isEmpty()) {
                Text(
                    text = "No signed-in servers yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            signedIn.forEach { server ->
                Text(
                    text = server.account.profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                if (server.calendars.isEmpty()) {
                    Text(
                        text = "No calendars loaded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    server.calendars.forEach { calendar ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onToggleVisibility(
                                        server.account.profile.id,
                                        calendar.calendar.id.value,
                                        !calendar.visible,
                                    )
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = calendar.visible,
                                onCheckedChange = { checked ->
                                    onToggleVisibility(
                                        server.account.profile.id,
                                        calendar.calendar.id.value,
                                        checked,
                                    )
                                },
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(accentSpec(calendar.calendar.color).light),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = calendar.calendar.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Non-blocking error lines for servers that failed their last refresh. */
@Composable
fun ServerIssues(servers: List<ServerUi>) {
    val issues = servers.filter { it.signedIn && !it.error.isNullOrBlank() }
    if (issues.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        issues.forEach { server ->
            InlineError("${server.account.profile.name}: ${server.error}")
        }
    }
}
