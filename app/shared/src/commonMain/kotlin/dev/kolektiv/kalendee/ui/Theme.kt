package dev.kolektiv.kalendee.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vendored Kalendee slice of Blawk until `dev.kolektiv.blawk:blawk-daisy`
 * is published. Keep colours in lockstep with `server/pack/src/styles.css`.
 */
@Immutable
data class KalendeeColors(
    val base100: Color = Color(0xFF090909),
    val base200: Color = Color(0xFF161616),
    val base300: Color = Color(0xFF242424),
    val baseContent: Color = Color(0xFFF5F5F5),
    val primary: Color = Color(0xFFDF6F00),
    val primaryContent: Color = Color(0xFFFFF7ED),
    val secondary: Color = Color(0xFF0090B5),
    val secondaryContent: Color = Color(0xFFEBFDFE),
    val accent: Color = Color(0xFFC700DE),
    val accentContent: Color = Color(0xFFE9FAF2),
    val info: Color = Color(0xFF00A4F2),
    val success: Color = Color(0xFF00A43B),
    val warning: Color = Color(0xFFEEAF00),
    val error: Color = Color(0xFFF82834),
    val errorContent: Color = Color(0xFFFEF2F2),
) {
    fun named(token: String): Color = when (token) {
        "secondary" -> secondary
        "accent" -> accent
        "info" -> info
        "success" -> success
        "warning" -> warning
        "error" -> error
        else -> primary
    }
}

@Immutable
data class KalendeeMetrics(
    val radiusField: Dp = 4.dp,
    val radiusBox: Dp = 8.dp,
    val hourHeight: Dp = 48.dp,
    val gutter: Dp = 56.dp,
    val sidebar: Dp = 256.dp,
)

val LocalKalendeeColors = staticCompositionLocalOf { KalendeeColors() }
val LocalKalendeeMetrics = staticCompositionLocalOf { KalendeeMetrics() }

object KalendeeTheme {
    val colors: KalendeeColors
        @Composable get() = LocalKalendeeColors.current
    val metrics: KalendeeMetrics
        @Composable get() = LocalKalendeeMetrics.current
}

@Composable
fun KalendeeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalKalendeeColors provides KalendeeColors(),
        LocalKalendeeMetrics provides KalendeeMetrics(),
        content = content,
    )
}
