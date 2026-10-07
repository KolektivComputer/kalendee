package dev.kolektiv.kalendee.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.kolektiv.kalendee.calendar.colorFor

/** Active design tokens. Defaults to the brand dark theme outside the theme. */
val LocalKalendeeColors = staticCompositionLocalOf { kalendeeDarkColors }

/**
 * Content color scoped by container components ([DButton] variants, [DAlert],
 * list items). [Color.Unspecified] means "fall back to `base-content`".
 */
val LocalDContentColor = staticCompositionLocalOf { Color.Unspecified }

/**
 * Kalendee design theme: daisyUI 5 "kalendee" tokens, no Material.
 *
 * [accent] is the viewer's accent preference (`primary`, `secondary`, `accent`,
 * `info`, `success` or `error`); it remaps `primary`/`primaryContent`, exactly
 * like the web UI. `#rrggbb` and `oklch(...)` are accepted as well. Dark is the
 * only brand-accurate theme; [dark] provides the derived light variant.
 */
@Composable
fun KalendeeTheme(
    accent: String = "primary",
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = remember(accent, dark) { kalendeeColorsFor(accent, dark) }
    CompositionLocalProvider(
        LocalKalendeeColors provides colors,
        LocalKalendeeDimens provides KalendeeDimens(),
        content = content,
    )
}

/** Container/content color pair for a calendar or event color. */
data class CalendarColorSpec(
    val fill: Color,
    val content: Color,
)

/**
 * Maps a calendar `color` string to a concrete pair:
 *
 * - palette names (`primary|secondary|accent|neutral|info|success|warning|error`)
 *   resolve to theme tokens;
 * - `#rrggbb` and `oklch(...)` parse and derive a readable content color;
 * - blank falls back to [colorFor] when a [calendarId] is available, otherwise
 *   (and for unknown values) to `info`.
 */
fun calendarColorSpec(
    color: String,
    colors: KalendeeColors,
    calendarId: String? = null,
): CalendarColorSpec {
    val raw = color.trim().lowercase()
    val resolved = if (raw.isBlank() && calendarId != null) colorFor(calendarId) else raw
    colors.colorPair(resolved)?.let { (fill, content) ->
        return CalendarColorSpec(fill, content)
    }
    parseColor(resolved)?.let { fill ->
        return CalendarColorSpec(fill, colors.contentFor(fill))
    }
    return CalendarColorSpec(colors.info, colors.infoContent)
}

/** [calendarColorSpec] against the active theme tokens. */
@Composable
fun rememberCalendarColorSpec(color: String, calendarId: String? = null): CalendarColorSpec {
    val colors = LocalKalendeeColors.current
    return remember(color, calendarId, colors) { calendarColorSpec(color, colors, calendarId) }
}
