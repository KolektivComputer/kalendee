package dev.kolektiv.kalendee.auth

import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall
import kotlin.math.min

internal fun ApplicationCall.setSessionCookie(token: String, settings: AuthSettings) {
    response.cookies.append(sessionCookie(token, settings, maxAgeSeconds = settings.cookieMaxAgeSeconds()))
}

internal fun ApplicationCall.clearSessionCookie(settings: AuthSettings) {
    response.cookies.append(sessionCookie("", settings, maxAgeSeconds = 0))
}

internal fun ApplicationCall.sessionToken(settings: AuthSettings): String? =
    request.cookies[settings.cookieName]

private fun sessionCookie(value: String, settings: AuthSettings, maxAgeSeconds: Int): Cookie = Cookie(
    name = settings.cookieName,
    value = value,
    maxAge = maxAgeSeconds,
    path = "/",
    secure = settings.cookieSecure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Lax"),
)

private fun AuthSettings.cookieMaxAgeSeconds(): Int =
    min(sessionTtl.inWholeSeconds, Int.MAX_VALUE.toLong()).toInt()
