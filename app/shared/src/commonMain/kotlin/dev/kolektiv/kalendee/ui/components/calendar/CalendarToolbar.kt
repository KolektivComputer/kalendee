package dev.kolektiv.kalendee.ui.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.calendar.CalendarView
import dev.kolektiv.kalendee.ui.ServerUi
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.design.medium
import dev.kolektiv.kalendee.ui.design.rememberCalendarColorSpec
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.design.components.DAlert
import dev.kolektiv.kalendee.ui.design.components.DAlertColor
import dev.kolektiv.kalendee.ui.design.components.DButton
import dev.kolektiv.kalendee.ui.design.components.DButtonSize
import dev.kolektiv.kalendee.ui.design.components.DButtonVariant
import dev.kolektiv.kalendee.ui.design.components.DCheckbox
import dev.kolektiv.kalendee.ui.design.components.DIconButton
import dev.kolektiv.kalendee.ui.design.components.DSpinner
import dev.kolektiv.kalendee.ui.design.components.DTabs
import dev.kolektiv.kalendee.ui.icons.Lucide

/**
 * Period header: the core view label, a Today action, refresh/filter icon buttons and
 * the Day/Week/Month switcher. There are deliberately no prev/next buttons — changing
 * periods is a swipe on the pager behind this toolbar.
 */
@Composable
fun CalendarToolbar(
    label: String,
    view: CalendarView,
    filtersOpen: Boolean,
    loading: Boolean,
    onToday: () -> Unit,
    onSelectView: (CalendarView) -> Unit,
    onToggleFilters: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.base200)
            .padding(horizontal = dimens.space3, vertical = dimens.space2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DButton(
                onClick = onToday,
                variant = DButtonVariant.Ghost,
                size = DButtonSize.Sm,
            ) {
                DText(text = "Today", style = DType.sm.medium())
            }
            Row(
                modifier = Modifier.weight(1f).padding(horizontal = dimens.space2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                DText(
                    text = label,
                    style = DType.lg.semibold(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (loading) {
                    Spacer(modifier = Modifier.width(dimens.space2))
                    DSpinner(size = 14.dp)
                }
            }
            DIconButton(
                icon = Lucide.RefreshCw,
                contentDescription = "Refresh",
                onClick = onRefresh,
                tint = colors.mutedContent,
                iconSize = 18.dp,
            )
            DIconButton(
                icon = Lucide.SlidersHorizontal,
                contentDescription = "Calendars",
                onClick = onToggleFilters,
                tint = if (filtersOpen) colors.primary else colors.mutedContent,
                iconSize = 18.dp,
            )
        }
        DTabs(
            items = ViewLabels,
            selectedIndex = view.ordinal,
            onSelect = { index -> onSelectView(CalendarView.entries[index]) },
            modifier = Modifier.fillMaxWidth().padding(top = dimens.space2),
        )
    }
}

private val ViewLabels = listOf("Day", "Week", "Month")

/** Checkbox list of calendars grouped by server, wired to per-calendar visibility. */
@Composable
fun CalendarFilterPanel(
    servers: List<ServerUi>,
    onToggleVisibility: (serverId: String, calendarId: String, visible: Boolean) -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.base200)
            .padding(horizontal = dimens.space4, vertical = dimens.space2),
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        val signedIn = servers.filter { it.signedIn }
        if (signedIn.isEmpty()) {
            DText(text = "No signed-in servers yet.", style = DType.sm, color = colors.mutedContent)
            return@Column
        }
        signedIn.forEach { server ->
            DText(
                text = server.account.profile.name.uppercase(),
                style = DType.sectionLabel,
                color = colors.mutedContent,
                modifier = Modifier.padding(top = dimens.space2),
            )
            if (server.calendars.isEmpty()) {
                DText(
                    text = "No calendars loaded yet.",
                    style = DType.sm,
                    color = colors.mutedContent,
                )
            } else {
                server.calendars.forEach { calendar ->
                    CalendarToggleRow(
                        color = calendar.calendar.color,
                        calendarId = calendar.calendar.id.value,
                        name = calendar.calendar.displayName,
                        visible = calendar.visible,
                        onToggle = { visible ->
                            onToggleVisibility(
                                server.account.profile.id,
                                calendar.calendar.id.value,
                                visible,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarToggleRow(
    color: String,
    calendarId: String,
    name: String,
    visible: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val spec = rememberCalendarColorSpec(color, calendarId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(dimens.fieldShape)
            .calendarClickable { onToggle(!visible) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DCheckbox(checked = visible, onCheckedChange = onToggle)
        Spacer(modifier = Modifier.width(dimens.space3))
        Spacer(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(spec.fill),
        )
        Spacer(modifier = Modifier.width(dimens.space2))
        DText(
            text = name,
            style = DType.sm,
            color = colors.baseContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Non-blocking error surface for servers that failed their last refresh. */
@Composable
fun ServerIssues(servers: List<ServerUi>, modifier: Modifier = Modifier) {
    val dimens = LocalKalendeeDimens.current
    val issues = servers.filter { it.signedIn && !it.error.isNullOrBlank() }
    if (issues.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.space4, vertical = dimens.space1),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        issues.forEach { server ->
            DAlert(
                color = DAlertColor.Error,
                icon = Lucide.AlertCircle,
                title = server.account.profile.name,
            ) {
                DText(text = server.error.orEmpty(), style = DType.sm)
            }
        }
    }
}
