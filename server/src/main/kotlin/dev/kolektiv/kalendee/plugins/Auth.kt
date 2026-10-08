package dev.kolektiv.kalendee.plugins

import dev.kolektiv.kalendee.api.isFaviconPath
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.sessionToken
import dev.kolektiv.kalendee.calendar.CalendarException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey

val CurrentUserKey = AttributeKey<User>("currentUser")

fun ApplicationCall.currentUser(): User? = attributes.getOrNull(CurrentUserKey)

internal fun ApplicationCall.clientIp(): String? = request.origin.remoteHost

internal fun ApplicationCall.clientUserAgent(): String? = request.headers[HttpHeaders.UserAgent]

fun Application.configureSessionAuth(authService: AuthService, settings: AuthSettings) {
    intercept(ApplicationCallPipeline.Call) {
        val path = call.request.path()
        if (path.startsWith("/__keel/pack/") || isFaviconPath(path)) return@intercept
        val token = call.sessionToken(settings)
        val user = token?.let { authService.userFor(it) }
        if (user != null) {
            call.attributes.put(CurrentUserKey, user)
        } else if (requiresAuth(call.request.httpMethod, path)) {
            throw CalendarException.Unauthorized("unauthorized")
        }
    }
}

private val CalendarSlotsPath = Regex("""^/api/v1/calendars/[^/]+/slots$""")

private fun requiresAuth(method: HttpMethod, path: String): Boolean {
    if (path != "/api/v1" && !path.startsWith("/api/v1/")) return false
    // Anonymous viewers may read availability slots on PUBLIC calendars; the
    // service still enforces public-link and access-mode checks. Every other
    // method or calendar route keeps the session requirement.
    if (method == HttpMethod.Get && CalendarSlotsPath.matches(path)) return false
    if (path.startsWith("/api/v1/users/") && path.endsWith("/avatar")) return false
    if (path.startsWith("/api/v1/public/")) return false
    if (path == "/api/v1/oauth/providers") return false
    if (path.startsWith("/api/v1/oauth/") && path.endsWith("/register")) return false
    if (path.startsWith("/api/v1/oauth/") && path.endsWith("/callback")) return false
    return when (path) {
        "/api/v1", "/api/v1/", "/api/v1/health",
        "/api/v1/auth/register", "/api/v1/auth/login",
        "/api/v1/auth/verify-email", "/api/v1/auth/resend-verification",
        -> false
        else -> true
    }
}
