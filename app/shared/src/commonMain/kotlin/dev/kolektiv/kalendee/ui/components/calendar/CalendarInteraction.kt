package dev.kolektiv.kalendee.ui.components.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Foundation-only tap target: like the design system's own components, this opts out of
 * the platform ripple so the calendar stays ripple-free on every target.
 */
@Composable
internal fun Modifier.calendarClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}
