package dev.kolektiv.kalendee.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Converts an oklch color to sRGB.
 *
 * Pipeline: oklch -> oklab -> linear sRGB (D65) -> sRGB, per the CSS Color 4
 * definitions. [l] is perceptual lightness in `0.0..1.0` (so `oklch(66% ...)`
 * is `oklch(0.66, ...)`), [c] is chroma and [h] is hue in degrees.
 *
 * Out-of-sRGB-gamut results are clipped per channel. Browsers gamut-map such
 * values (chroma reduction); several Kalendee accents are slightly outside
 * sRGB, so clipped results differ marginally from the web (e.g. `primary`
 * renders as `#DF6F00` here versus `#D77500` gamut-mapped).
 */
fun oklch(l: Double, c: Double, h: Double): Color {
    val hRadians = h * PI / 180.0
    val a = c * cos(hRadians)
    val b = c * sin(hRadians)

    val lCubeRoot = l + 0.3963377774 * a + 0.2158037573 * b
    val mCubeRoot = l - 0.1055613458 * a - 0.0638541728 * b
    val sCubeRoot = l - 0.0894841775 * a - 1.2914855480 * b

    val lmsL = lCubeRoot * lCubeRoot * lCubeRoot
    val lmsM = mCubeRoot * mCubeRoot * mCubeRoot
    val lmsS = sCubeRoot * sCubeRoot * sCubeRoot

    val linearRed = 4.0767416621 * lmsL - 3.3077115913 * lmsM + 0.2309699292 * lmsS
    val linearGreen = -1.2684380046 * lmsL + 2.6097574011 * lmsM - 0.3413193965 * lmsS
    val linearBlue = -0.0041960863 * lmsL - 0.7034186147 * lmsM + 1.7076147010 * lmsS

    return Color(
        red = linearToSrgb(linearRed),
        green = linearToSrgb(linearGreen),
        blue = linearToSrgb(linearBlue),
    )
}

private fun linearToSrgb(component: Double): Float {
    val encoded = if (component <= 0.0031308) {
        12.92 * component
    } else {
        1.055 * component.pow(1.0 / 2.4) - 0.055
    }
    return encoded.coerceIn(0.0, 1.0).toFloat()
}

private val HexColor = Regex("^#([0-9a-fA-F]{6})$")

private val OklchColor = Regex(
    "^oklch\\(\\s*([0-9]*\\.?[0-9]+)(%?)\\s+([0-9]*\\.?[0-9]+)\\s+([0-9]*\\.?[0-9]+)(?:deg)?\\s*\\)$",
    RegexOption.IGNORE_CASE,
)

/** Parses `#rrggbb`, returning null for anything else. */
fun parseHexColor(value: String): Color? {
    val match = HexColor.matchEntire(value.trim()) ?: return null
    val rgb = match.groupValues[1].toLongOrNull(radix = 16) ?: return null
    return Color(0xFF000000L or rgb)
}

/** Parses `oklch(l c h)`, with optional `%` on lightness and optional `deg` on hue. */
fun parseOklchColor(value: String): Color? {
    val match = OklchColor.matchEntire(value.trim()) ?: return null
    val rawLightness = match.groupValues[1].toDoubleOrNull() ?: return null
    val lightness = if (match.groupValues[2] == "%") rawLightness / 100.0 else rawLightness
    val chroma = match.groupValues[3].toDoubleOrNull() ?: return null
    val hue = match.groupValues[4].toDoubleOrNull() ?: return null
    return oklch(lightness, chroma, hue)
}

/** Parses a `#rrggbb` or `oklch(...)` color string, returning null when unsupported. */
fun parseColor(value: String): Color? = parseHexColor(value) ?: parseOklchColor(value)

/**
 * Every daisyUI "kalendee" theme token, as sRGB [Color]s.
 *
 * Naming follows the CSS custom properties: `base-100` becomes [base100],
 * `primary-content` becomes [primaryContent], and so on.
 */
@Immutable
data class KalendeeColors(
    val base100: Color,
    val base200: Color,
    val base300: Color,
    val baseContent: Color,
    val primary: Color,
    val primaryContent: Color,
    val secondary: Color,
    val secondaryContent: Color,
    val accent: Color,
    val accentContent: Color,
    val neutral: Color,
    val neutralContent: Color,
    val info: Color,
    val infoContent: Color,
    val success: Color,
    val successContent: Color,
    val warning: Color,
    val warningContent: Color,
    val error: Color,
    val errorContent: Color,
) {
    /** 1px surface border color. */
    val border: Color get() = base300

    /** Secondary text: 50% of [baseContent]. */
    val mutedContent: Color get() = baseContent.copy(alpha = 0.5f)

    /** Slightly de-emphasized text: 70% of [baseContent]. */
    val subtleContent: Color get() = baseContent.copy(alpha = 0.7f)

    /** Placeholder text: 40% of [baseContent]. */
    val placeholder: Color get() = baseContent.copy(alpha = 0.4f)

    /** Hover overlay: ~8% of [baseContent]. */
    val hoverOverlay: Color get() = baseContent.copy(alpha = 0.08f)

    /** Pressed overlay: ~12% of [baseContent]. */
    val pressedOverlay: Color get() = baseContent.copy(alpha = 0.12f)

    /** Modal scrim: black at 60%. */
    val scrim: Color get() = Color.Black.copy(alpha = 0.6f)

    /**
     * The `(fill, content)` pair for a named daisyUI color, or null when the
     * name is not a theme color. Accepts `-content`-less names only.
     */
    fun colorPair(name: String): Pair<Color, Color>? = when (name.trim().lowercase()) {
        "primary" -> primary to primaryContent
        "secondary" -> secondary to secondaryContent
        "accent" -> accent to accentContent
        "neutral" -> neutral to neutralContent
        "info" -> info to infoContent
        "success" -> success to successContent
        "warning" -> warning to warningContent
        "error" -> error to errorContent
        else -> null
    }

    /**
     * Picks a readable content color for an arbitrary [fill]: whichever of
     * [base100]/[baseContent] is darker for light fills, lighter for dark fills.
     */
    fun contentFor(fill: Color): Color {
        val base100IsDark = base100.luminance() <= baseContent.luminance()
        val darkInk = if (base100IsDark) base100 else baseContent
        val lightInk = if (base100IsDark) baseContent else base100
        return if (fill.luminance() > 0.5f) darkInk else lightInk
    }

    /**
     * Remaps [primary]/[primaryContent] to the viewer's accent preference.
     *
     * Named accents are the six web accent ids; `#rrggbb` and `oklch(...)` are
     * also accepted for compatibility. Unknown values keep the brand primary.
     */
    fun withAccent(accent: String): KalendeeColors {
        val key = accent.trim().lowercase()
        val pair: Pair<Color, Color> = when (key) {
            "primary" -> primary to primaryContent
            "secondary" -> secondary to secondaryContent
            "accent" -> this.accent to accentContent
            "info" -> info to infoContent
            "success" -> success to successContent
            "error" -> error to errorContent
            else -> {
                val custom = parseColor(key) ?: return this
                custom to contentFor(custom)
            }
        }
        return copy(primary = pair.first, primaryContent = pair.second)
    }
}

/** The brand theme, matching `server/pack/src/styles.css` exactly. */
val kalendeeDarkColors: KalendeeColors = KalendeeColors(
    base100 = oklch(0.14, 0.0, 0.0),
    base200 = oklch(0.20, 0.0, 0.0),
    base300 = oklch(0.26, 0.0, 0.0),
    baseContent = oklch(0.97, 0.0, 0.0),
    primary = oklch(0.66, 0.179, 58.318),
    primaryContent = oklch(0.98, 0.016, 73.684),
    secondary = oklch(0.60, 0.126, 221.723),
    secondaryContent = oklch(0.98, 0.019, 200.873),
    accent = oklch(0.59, 0.293, 322.896),
    accentContent = oklch(0.97, 0.021, 166.113),
    neutral = oklch(0.43, 0.0, 0.0),
    neutralContent = oklch(0.98, 0.0, 0.0),
    info = oklch(0.68, 0.169, 237.323),
    infoContent = oklch(0.97, 0.013, 236.62),
    success = oklch(0.62, 0.194, 149.214),
    successContent = oklch(0.98, 0.014, 180.72),
    warning = oklch(0.79, 0.184, 86.047),
    warningContent = oklch(0.98, 0.026, 102.212),
    error = oklch(0.63, 0.237, 25.331),
    errorContent = oklch(0.97, 0.013, 17.38),
)

/**
 * Derived light variant for the existing appearance setting. The web has no
 * light theme; surfaces are inverted while the accent pairs stay untouched.
 */
val kalendeeLightColors: KalendeeColors = kalendeeDarkColors.copy(
    base100 = oklch(0.98, 0.0, 0.0),
    base200 = oklch(0.955, 0.0, 0.0),
    base300 = oklch(0.89, 0.0, 0.0),
    baseContent = oklch(0.16, 0.0, 0.0),
)

/** [kalendeeDarkColors]/[kalendeeLightColors] selected by [dark]. */
fun kalendeeColors(dark: Boolean): KalendeeColors =
    if (dark) kalendeeDarkColors else kalendeeLightColors

/** Theme tokens for [dark] with [accent] remapping `primary`/`primaryContent`. */
fun kalendeeColorsFor(accent: String, dark: Boolean): KalendeeColors =
    kalendeeColors(dark).withAccent(accent)
