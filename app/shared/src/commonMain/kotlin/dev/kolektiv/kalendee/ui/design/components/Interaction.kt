package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors

/**
 * daisyUI-style hover/pressed overlay (`base-content` at 8/12%) on a
 * [shape]-clipped background. Pair with `clickable(interactionSource,
 * indication = null)` using the same [interactionSource].
 */
@Composable
internal fun Modifier.hoverSurface(
    interactionSource: MutableInteractionSource,
    shape: Shape = RectangleShape,
): Modifier {
    val colors = LocalKalendeeColors.current
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()
    val overlay = when {
        pressed -> colors.pressedOverlay
        hovered -> colors.hoverOverlay
        else -> Color.Transparent
    }
    return this.background(overlay, shape).hoverable(interactionSource)
}
