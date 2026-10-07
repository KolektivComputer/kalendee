package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import dev.kolektiv.kalendee.ui.design.LocalDTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalDContentColor
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens

data class DDropdownItem(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val supportingText: String? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

/**
 * Anchored dropdown menu (daisyUI `menu`): base-100 surface, box radius,
 * 1px border, shadow, 176dp min width and `base-content/10` hover.
 *
 * Place next to the anchor; the popup opens at [alignment] of its parent.
 */
@Composable
fun DDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<DDropdownItem>,
    modifier: Modifier = Modifier,
    minWidth: Dp = 176.dp,
    alignment: Alignment = Alignment.BottomStart,
    offset: IntOffset = IntOffset(0, 0),
) {
    if (!expanded) return
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val shape = dimens.boxShape
    Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = modifier
                .defaultMinSize(minWidth = minWidth)
                .clip(shape)
                .background(colors.base100)
                .border(dimens.border, colors.base300, shape)
                .shadow(8.dp, shape)
                .padding(vertical = 4.dp),
        ) {
            items.forEach { item ->
                DDropdownItemRow(
                    item = item,
                    onDismiss = onDismissRequest,
                )
            }
        }
    }
}

@Composable
private fun DDropdownItemRow(
    item: DDropdownItem,
    onDismiss: () -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor = when {
        !item.enabled -> colors.mutedContent
        item.destructive -> colors.error
        else -> colors.baseContent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp)
            .hoverSurface(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = item.enabled,
                onClick = {
                    item.onClick()
                    onDismiss()
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalDContentColor provides contentColor,
            LocalDTextStyle provides DType.sm,
        ) {
            if (item.icon != null) {
                DIcon(
                    icon = item.icon,
                    tint = contentColor,
                    size = 16.dp,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            DText(
                text = item.label,
                style = DType.sm,
                color = contentColor,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.supportingText != null) {
                DText(
                    text = item.supportingText,
                    style = DType.xs,
                    color = colors.mutedContent,
                    modifier = Modifier.padding(start = 12.dp),
                    maxLines = 1,
                )
            }
        }
    }
}
