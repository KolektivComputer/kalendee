package dev.kolektiv.kalendee.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.auth.Accent
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.ThemeMode
import dev.kolektiv.kalendee.ui.components.PageColumn
import dev.kolektiv.kalendee.ui.components.ScreenTitle
import dev.kolektiv.kalendee.ui.components.SectionTitle
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.design.components.DCard
import dev.kolektiv.kalendee.ui.design.components.DIcon
import dev.kolektiv.kalendee.ui.design.components.DJoinGroup
import dev.kolektiv.kalendee.ui.design.components.DJoinItem
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.icons.Lucide

@Composable
fun AppearanceScreen(state: AppState) {
    val ui by state.state.collectAsState()
    val colors = LocalKalendeeColors.current

    PageColumn {
        ScreenTitle(title = "Appearance", onBack = { state.navigator.pop() })

        SectionTitle("Accent")
        DCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Accent.ids.forEach { id ->
                    AccentSwatch(
                        id = id,
                        selected = ui.accent.equals(id, ignoreCase = true),
                        onClick = { state.setAccent(id) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            DText(
                text = "The accent colors primary buttons, links, and selections.",
                style = DType.xs,
                color = colors.mutedContent,
            )
        }

        SectionTitle("Theme")
        DJoinGroup(modifier = Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                DJoinItem(
                    onClick = { state.setThemeMode(mode) },
                    selected = ui.themeMode == mode,
                    leadingDivider = index > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    DIcon(icon = mode.icon(), size = 16.dp, modifier = Modifier.padding(end = 6.dp))
                    DText(themeLabel(mode), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        DText(
            text = "System follows this device's light or dark setting. Dark is the Kalendee brand look.",
            style = DType.xs,
            color = colors.mutedContent,
        )

        SectionTitle("Preview")
        DCard(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .clip(LocalKalendeeDimens.current.fieldShape)
                    .background(colors.primary)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                DText(
                    text = "Team standup",
                    style = DType.sm.semibold(),
                    color = colors.primaryContent,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(8.dp))
            DText(
                text = "Buttons, links, and selections preview using this accent.",
                style = DType.sm,
                color = colors.mutedContent,
            )
        }
    }
}

@Composable
private fun AccentSwatch(id: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalKalendeeColors.current
    val pair = colors.colorPair(id) ?: (colors.primary to colors.primaryContent)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .then(
                if (selected) {
                    Modifier.border(2.dp, colors.baseContent, CircleShape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(pair.first),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                DIcon(icon = Lucide.Check, tint = pair.second, size = 14.dp)
            }
        }
    }
}

private fun ThemeMode.icon() = when (this) {
    ThemeMode.System -> Lucide.Monitor
    ThemeMode.Light -> Lucide.Sun
    ThemeMode.Dark -> Lucide.Moon
}
