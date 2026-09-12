package dev.kolektiv.kalendee.plugins

import dev.kolektiv.kalendee.api.isFaviconPath
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.sessionToken
import dev.kolektiv.kalendee.calendar.CalendarException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
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
        } else if (requiresAuth(path)) {
            throw CalendarException.Unauthorized("unauthorized")
        }
    }
}

private fun requiresAuth(path: String): Boolean {
    if (path != "/api/v1" && !path.startsWith("/api/v1/")) return false
    if (path.startsWith("/api/v1/users/") && path.endsWith("/avatar")) return false
    if (path.startsWith("/api/v1/public/")) return false
    return when (path) {
        "/api/v1", "/api/v1/", "/api/v1/health",
        "/api/v1/auth/register", "/api/v1/auth/login",
        "/api/v1/auth/verify-email", "/api/v1/auth/resend-verification",
        -> false
        else -> true
    }
}
