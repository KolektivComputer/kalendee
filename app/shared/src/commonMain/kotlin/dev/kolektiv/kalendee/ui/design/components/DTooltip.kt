package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import kotlinx.coroutines.delay

/**
 * Tooltip shown on hover (desktop) or long press (touch), auto-hiding after a
 * short delay. Content is not consumed, so the wrapped control stays usable.
 */
@Composable
fun DTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var longPressed by remember { mutableStateOf(false) }
    val visible = hovered || longPressed
    LaunchedEffect(longPressed) {
        if (longPressed) {
            delay(1600)
            longPressed = false
        }
    }
    Box(
        modifier = modifier
            .hoverable(interactionSource)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { longPressed = true })
            },
    ) {
        content()
        if (visible) {
            Popup(
                popupPositionProvider = TooltipPositionProvider,
                onDismissRequest = { longPressed = false },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                Box(
                    modifier = Modifier
                        .clip(dimens.fieldShape)
                        .background(colors.base200)
                        .border(dimens.border, colors.base300, dimens.fieldShape)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    DText(text = text, style = DType.xs, color = colors.baseContent)
                }
            }
        }
    }
}

private object TooltipPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top - popupContentSize.height - 8
        val y = if (above >= 0) above else anchorBounds.bottom + 8
        return IntOffset(x, y)
    }
}
