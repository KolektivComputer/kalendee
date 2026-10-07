package dev.kolektiv.kalendee.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.getPlatform
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.ThemeMode
import dev.kolektiv.kalendee.ui.components.Notice
import dev.kolektiv.kalendee.ui.components.PageColumn
import dev.kolektiv.kalendee.ui.components.ScreenTitle
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.components.DIcon
import dev.kolektiv.kalendee.ui.design.components.DListSection
import dev.kolektiv.kalendee.ui.design.components.DListItem
import dev.kolektiv.kalendee.ui.icons.Lucide
import dev.kolektiv.kalendee.ui.nav.Route

internal const val AppVersion = "0.1.0"

/** Settings hub; each row opens a focused sub-page instead of one mega page. */
@Composable
fun SettingsScreen(state: AppState) {
    val ui by state.state.collectAsState()
    val stack by state.navigator.stack.collectAsState()
    val colors = LocalKalendeeColors.current

    PageColumn {
        ScreenTitle(
            title = "Settings",
            onBack = if (stack.size > 1) ({ state.navigator.pop() }) else null,
        )
        Notice(text = ui.notice, onDismiss = state::clearNotice)

        DListSection(label = "Account") {
            DListItem(
                title = "Servers",
                subtitle = when {
                    ui.servers.isEmpty() -> "No servers connected"
                    ui.servers.size == 1 -> ui.servers.single().account.profile.name
                    else -> "${ui.servers.size} configured"
                },
                leading = { DIcon(icon = Lucide.Building2, size = 18.dp) },
                trailing = { DIcon(icon = Lucide.ChevronRight, tint = colors.mutedContent, size = 18.dp) },
                onClick = { state.navigator.push(Route.Servers) },
            )
        }

        DListSection(label = "Preferences") {
            DListItem(
                title = "Appearance",
                subtitle = "${ui.accent.replaceFirstChar { it.uppercaseChar() }} • ${themeLabel(ui.themeMode)}",
                leading = { DIcon(icon = Lucide.Palette, size = 18.dp) },
                trailing = { DIcon(icon = Lucide.ChevronRight, tint = colors.mutedContent, size = 18.dp) },
                onClick = { state.navigator.push(Route.Appearance) },
            )
            DListItem(
                title = "Behavior",
                subtitle = "${ui.defaultView.name} view • local reminders",
                leading = { DIcon(icon = Lucide.SlidersHorizontal, size = 18.dp) },
                trailing = { DIcon(icon = Lucide.ChevronRight, tint = colors.mutedContent, size = 18.dp) },
                onClick = { state.navigator.push(Route.Behavior) },
            )
        }

        DListSection(label = "About") {
            DListItem(
                title = "Kalendee",
                subtitle = "Version $AppVersion",
                leading = { DIcon(icon = Lucide.Calendar, size = 18.dp) },
            )
            DListItem(
                title = "Platform",
                subtitle = getPlatform().name,
                leading = { DIcon(icon = Lucide.Monitor, size = 18.dp) },
            )
        }

        DText(
            text = "Self-hosted calendar client",
            style = DType.xs,
            color = colors.mutedContent,
            modifier = Modifier.padding(top = 24.dp, start = 4.dp),
        )
    }
}

internal fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> "System"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}
