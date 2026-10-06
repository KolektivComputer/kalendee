package dev.kolektiv.kalendee.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Accent-derived colors used to build the Material3 light/dark schemes.
 *
 * Accent ids mirror [dev.kolektiv.kalendee.auth.Accent.ids] plus `warning`, and any
 * `#rrggbb` value is accepted for hex accents.
 */
data class AccentSpec(
    val light: Color,
    val onLight: Color,
    val lightContainer: Color,
    val onLightContainer: Color,
    val dark: Color,
    val onDark: Color,
    val darkContainer: Color,
    val onDarkContainer: Color,
)

private val NamedAccents: Map<String, AccentSpec> = mapOf(
    "primary" to AccentSpec(
        light = Color(0xFF570DF8),
        onLight = Color.White,
        lightContainer = Color(0xFFE9E3FF),
        onLightContainer = Color(0xFF1F0A57),
        dark = Color(0xFFB69CFF),
        onDark = Color(0xFF22005D),
        darkContainer = Color(0xFF3C2A80),
        onDarkContainer = Color(0xFFE9DDFF),
    ),
    "secondary" to AccentSpec(
        light = Color(0xFFDB2777),
        onLight = Color.White,
        lightContainer = Color(0xFFFCE7F3),
        onLightContainer = Color(0xFF831843),
        dark = Color(0xFFF9A8D4),
        onDark = Color(0xFF500724),
        darkContainer = Color(0xFF831843),
        onDarkContainer = Color(0xFFFCE7F3),
    ),
    "accent" to AccentSpec(
        light = Color(0xFF0D9488),
        onLight = Color.White,
        lightContainer = Color(0xFFCCFBF1),
        onLightContainer = Color(0xFF115E59),
        dark = Color(0xFF5EEAD4),
        onDark = Color(0xFF042F2E),
        darkContainer = Color(0xFF115E59),
        onDarkContainer = Color(0xFFCCFBF1),
    ),
    "info" to AccentSpec(
        light = Color(0xFF0284C7),
        onLight = Color.White,
        lightContainer = Color(0xFFE0F2FE),
        onLightContainer = Color(0xFF075985),
        dark = Color(0xFF7DD3FC),
        onDark = Color(0xFF082F49),
        darkContainer = Color(0xFF075985),
        onDarkContainer = Color(0xFFE0F2FE),
    ),
    "success" to AccentSpec(
        light = Color(0xFF16A34A),
        onLight = Color.White,
        lightContainer = Color(0xFFDCFCE7),
        onLightContainer = Color(0xFF166534),
        dark = Color(0xFF86EFAC),
        onDark = Color(0xFF052E16),
        darkContainer = Color(0xFF166534),
        onDarkContainer = Color(0xFFDCFCE7),
    ),
    "warning" to AccentSpec(
        light = Color(0xFFD97706),
        onLight = Color.White,
        lightContainer = Color(0xFFFEF3C7),
        onLightContainer = Color(0xFF92400E),
        dark = Color(0xFFFCD34D),
        onDark = Color(0xFF451A03),
        darkContainer = Color(0xFF78350F),
        onDarkContainer = Color(0xFFFEF3C7),
    ),
    "error" to AccentSpec(
        light = Color(0xFFDC2626),
        onLight = Color.White,
        lightContainer = Color(0xFFFEE2E2),
        onLightContainer = Color(0xFF7F1D1D),
        dark = Color(0xFFF87171),
        onDark = Color(0xFF450A0A),
        darkContainer = Color(0xFF7F1D1D),
        onDarkContainer = Color(0xFFFEE2E2),
    ),
)

/** Resolves an accent id or hex color to a full [AccentSpec], falling back to `primary`. */
fun accentSpec(accent: String): AccentSpec {
    val key = accent.trim().lowercase()
    NamedAccents[key]?.let { return it }
    parseHexColor(key)?.let { return hexAccent(it) }
    return NamedAccents.getValue("primary")
}

internal fun parseHexColor(raw: String): Color? {
    val value = raw.trim()
    if (!value.startsWith("#") || value.length != 7) return null
    val parsed = value.drop(1).toLongOrNull(radix = 16) ?: return null
    return Color(0xFF000000L or parsed)
}

private fun hexAccent(base: Color): AccentSpec {
    val lightContainer = lerp(Color.White, base, 0.18f)
    val darkContainer = lerp(Color.Black, base, 0.40f)
    return AccentSpec(
        light = base,
        onLight = if (base.luminance() > 0.55f) Color(0xFF111827) else Color.White,
        lightContainer = lightContainer,
        onLightContainer = lerp(Color.Black, base, 0.72f),
        dark = lerp(Color.White, base, 0.38f),
        onDark = Color(0xFF111827),
        darkContainer = darkContainer,
        onDarkContainer = lerp(Color.White, base, 0.88f),
    )
}

private val Ink = Color(0xFF0F172A)
private val InkMuted = Color(0xFF475569)

/** Hand-built light scheme: neutral surfaces, accent-driven primary/tertiary roles. */
fun kalendeeLightScheme(accent: String): ColorScheme {
    val a = accentSpec(accent)
    return lightColorScheme(
        primary = a.light,
        onPrimary = a.onLight,
        primaryContainer = a.lightContainer,
        onPrimaryContainer = a.onLightContainer,
        inversePrimary = a.dark,
        secondary = Color(0xFF475569),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE2E8F0),
        onSecondaryContainer = Ink,
        tertiary = a.light,
        onTertiary = a.onLight,
        tertiaryContainer = a.lightContainer,
        onTertiaryContainer = a.onLightContainer,
        background = Color(0xFFF8FAFC),
        onBackground = Ink,
        surface = Color(0xFFFFFFFF),
        onSurface = Ink,
        surfaceVariant = Color(0xFFE9EEF5),
        onSurfaceVariant = InkMuted,
        inverseSurface = Color(0xFF1E293B),
        inverseOnSurface = Color(0xFFF8FAFC),
        outline = Color(0xFF94A3B8),
        outlineVariant = Color(0xFFCBD5E1),
        error = Color(0xFFDC2626),
        onError = Color.White,
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = Color(0xFF7F1D1D),
        scrim = Color.Black,
        surfaceTint = a.light,
    )
}

/** Hand-built dark scheme: deep neutral surfaces, pastel accent roles. */
fun kalendeeDarkScheme(accent: String): ColorScheme {
    val a = accentSpec(accent)
    return darkColorScheme(
        primary = a.dark,
        onPrimary = a.onDark,
        primaryContainer = a.darkContainer,
        onPrimaryContainer = a.onDarkContainer,
        inversePrimary = a.light,
        secondary = Color(0xFF94A3B8),
        onSecondary = Color(0xFF0F172A),
        secondaryContainer = Color(0xFF334155),
        onSecondaryContainer = Color(0xFFE2E8F0),
        tertiary = a.dark,
        onTertiary = a.onDark,
        tertiaryContainer = a.darkContainer,
        onTertiaryContainer = a.onDarkContainer,
        background = Color(0xFF0B1120),
        onBackground = Color(0xFFE2E8F0),
        surface = Color(0xFF111827),
        onSurface = Color(0xFFE2E8F0),
        surfaceVariant = Color(0xFF1F2937),
        onSurfaceVariant = Color(0xFF94A3B8),
        inverseSurface = Color(0xFFE2E8F0),
        inverseOnSurface = Color(0xFF0F172A),
        outline = Color(0xFF475569),
        outlineVariant = Color(0xFF334155),
        error = Color(0xFFF87171),
        onError = Color(0xFF450A0A),
        errorContainer = Color(0xFF7F1D1D),
        onErrorContainer = Color(0xFFFEE2E2),
        scrim = Color.Black,
        surfaceTint = a.dark,
    )
}

private val KalendeeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * App theme. [accent] is a Kalendee accent id (`primary`, `secondary`, `accent`,
 * `info`, `success`, `error`, `warning`) or a `#rrggbb` hex value.
 */
@Composable
fun KalendeeTheme(
    accent: String = "primary",
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) kalendeeDarkScheme(accent) else kalendeeLightScheme(accent),
        typography = Typography(),
        shapes = KalendeeShapes,
        content = content,
    )
}
