package dev.kolektiv.kalendee.calendar

val CalendarPalette = listOf(
    "primary",
    "secondary",
    "accent",
    "info",
    "success",
    "warning",
    "error",
)

private val HexColor = Regex("^#[0-9a-fA-F]{6}$")

fun colorFor(id: String): String =
    CalendarPalette[id.hashCode().mod(CalendarPalette.size)]

fun requireColor(value: String): String {
    val trimmed = value.trim().lowercase()
    if (trimmed in CalendarPalette) return trimmed
    if (HexColor.matches(trimmed)) return trimmed
    throw CalendarException.Invalid("color must be a daisyUI color name or #rrggbb hex value")
}
