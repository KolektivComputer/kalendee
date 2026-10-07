package dev.kolektiv.kalendee.ui.design.components

import androidx.compose.ui.graphics.Color
import dev.kolektiv.kalendee.ui.design.KalendeeColors

/** Shared daisyUI color roles used by buttons, badges and alerts. */
internal enum class DColorRole { Primary, Secondary, Accent, Neutral, Info, Success, Warning, Error }

internal fun DColorRole.fill(colors: KalendeeColors): Color =
    colors.colorPair(name.lowercase())?.first ?: colors.primary

internal fun DColorRole.content(colors: KalendeeColors): Color =
    colors.colorPair(name.lowercase())?.second ?: colors.primaryContent
