package dev.kolektiv.kalendee.auth

import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.requireTimeZone

private val UsernamePattern = Regex("^[a-z0-9][a-z0-9._-]{2,31}$")
private val EmailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

fun RegisterUser.validated(): RegisterUser {
    val name = username.trim().lowercase()
    if (!UsernamePattern.matches(name)) {
        throw CalendarException.Invalid(
            "username must be 3-32 characters, start with a letter or digit, and contain only letters, digits, dots, underscores, or hyphens",
        )
    }
    requirePassword(password)
    return copy(username = name, email = email?.let(::requireEmail))
}

fun LoginUser.normalized(): LoginUser = copy(username = username.trim().lowercase())

fun UpdateUser.validated(): UpdateUser = copy(
    displayName = displayName?.let(::requireDisplayName),
    timeZone = timeZone?.let(::requireTimeZone),
    accent = accent?.let(Accent::require),
    email = email?.let(::requireEmail),
)

fun requireEmail(value: String): String {
    val trimmed = value.trim().lowercase()
    if (trimmed.isEmpty()) {
        throw CalendarException.Invalid("email must not be blank")
    }
    if (trimmed.length > 254) {
        throw CalendarException.Invalid("email must be at most 254 characters")
    }
    if (!EmailPattern.matches(trimmed)) {
        throw CalendarException.Invalid("email is not valid")
    }
    return trimmed
}

fun requireDisplayName(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        throw CalendarException.Invalid("displayName must not be blank")
    }
    if (trimmed.length > 80) {
        throw CalendarException.Invalid("displayName must be at most 80 characters")
    }
    return trimmed
}

fun requirePassword(password: String) {
    if (password.length < 8) {
        throw CalendarException.Invalid("password must be at least 8 characters")
    }
    if (password.length > 128) {
        throw CalendarException.Invalid("password must be at most 128 characters")
    }
}
