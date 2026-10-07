package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalDContentColor
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.icons.Lucide

enum class DAlertColor { Info, Success, Warning, Error }

/**
 * daisyUI `alert`: color-tinted background (~15% alpha), border at ~40%,
 * `base-content` text.
 */
@Composable
fun DAlert(
    color: DAlertColor = DAlertColor.Info,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val role = color.role
    val tint = role.fill(colors)
    val shape = dimens.boxShape
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint.copy(alpha = 0.15f))
            .border(dimens.border, tint.copy(alpha = 0.4f), shape)
            .padding(dimens.space3),
    ) {
        if (icon != null) {
            DIcon(
                icon = icon,
                tint = tint,
                size = 18.dp,
                modifier = Modifier.padding(top = 2.dp, end = dimens.space3),
            )
        }
        CompositionLocalProvider(LocalDContentColor provides colors.baseContent) {
            Column(modifier = Modifier.weight(1f)) {
                if (!title.isNullOrBlank()) {
                    DText(
                        text = title,
                        style = DType.sm.semibold(),
                        color = colors.baseContent,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                content()
            }
        }
    }
}

@Composable
fun DInfoAlert(text: String, modifier: Modifier = Modifier) {
    DAlert(color = DAlertColor.Info, modifier = modifier, icon = Lucide.AlertCircle) {
        DText(text = text, style = DType.sm)
    }
}

@Composable
fun DErrorAlert(text: String, modifier: Modifier = Modifier) {
    DAlert(color = DAlertColor.Error, modifier = modifier, icon = Lucide.AlertCircle) {
        DText(text = text, style = DType.sm)
    }
}

private val DAlertColor.role: DColorRole
    get() = when (this) {
        DAlertColor.Info -> DColorRole.Info
        DAlertColor.Success -> DColorRole.Success
        DAlertColor.Warning -> DColorRole.Warning
        DAlertColor.Error -> DColorRole.Error
    }
