package dev.kolektiv.kalendee.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * daisyUI "kalendee" sizing tokens: `--radius-*`, the 4/8/12/16/24 spacing
 * scale and the control heights of daisyUI 5.
 */
@Immutable
data class KalendeeDimens(
    val radiusSelector: Dp = 32.dp,
    val radiusField: Dp = 4.dp,
    val radiusBox: Dp = 8.dp,
    val border: Dp = 1.dp,
    val space1: Dp = 4.dp,
    val space2: Dp = 8.dp,
    val space3: Dp = 12.dp,
    val space4: Dp = 16.dp,
    val space6: Dp = 24.dp,
    val btnXs: Dp = 24.dp,
    val btnSm: Dp = 32.dp,
    val btnMd: Dp = 40.dp,
    val input: Dp = 40.dp,
    val inputSm: Dp = 32.dp,
) {
    /** Pill radius for toggles/checkboxes/radios/switches. */
    val selectorShape: Shape get() = RoundedCornerShape(radiusSelector)

    /** Tight radius for inputs, buttons, chips and menus. */
    val fieldShape: Shape get() = RoundedCornerShape(radiusField)

    /** Soft radius for cards, modals and menus. */
    val boxShape: Shape get() = RoundedCornerShape(radiusBox)
}

val LocalKalendeeDimens = staticCompositionLocalOf { KalendeeDimens() }
