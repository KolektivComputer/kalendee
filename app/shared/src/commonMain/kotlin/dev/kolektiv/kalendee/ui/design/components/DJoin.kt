package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import dev.kolektiv.kalendee.ui.design.LocalDTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalDContentColor
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.design.medium

/**
 * Segmented control container (daisyUI `join` / `tabs-box`): base-200 row with
 * a field radius and border. Place [DJoinItem]s inside.
 */
@Composable
fun DJoinGroup(
    modifier: Modifier = Modifier,
    background: Color = LocalKalendeeColors.current.base200,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val shape = dimens.fieldShape
    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(dimens.border, colors.base300, shape),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * One segment of a [DJoinGroup]. Selected segments get the base-100 surface
 * with a small shadow; all segments share the hover/pressed overlay.
 *
 * @param leadingDivider draw a hairline on the start edge; pass false on the
 *   first item when the group is separated by dividers.
 */
@Composable
fun DJoinItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    leadingDivider: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val interactionSource = remember { MutableInteractionSource() }
    val shape = dimens.fieldShape
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 34.dp)
            .drawBehind {
                if (leadingDivider) {
                    val stroke = dimens.border.toPx()
                    drawLine(
                        color = colors.base300,
                        start = Offset(0f, size.height * 0.2f),
                        end = Offset(0f, size.height * 0.8f),
                        strokeWidth = stroke,
                    )
                }
            }
            .clip(shape)
            .background(if (selected) colors.base100 else Color.Transparent)
            .then(if (selected) Modifier.shadow(2.dp, shape) else Modifier)
            .hoverSurface(interactionSource, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(
            LocalDContentColor provides colors.baseContent,
            LocalDTextStyle provides DType.sm.medium(),
        ) {
            content()
        }
    }
}
