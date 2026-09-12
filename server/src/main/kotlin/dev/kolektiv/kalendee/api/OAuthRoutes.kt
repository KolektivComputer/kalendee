package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.setSessionCookie
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.oauth.ConnectionService
import dev.kolektiv.kalendee.oauth.OAuthCallbackOutcome
import dev.kolektiv.kalendee.plugins.currentUser
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class OAuthStartResponse(
    val url: String,
)

fun Route.oauthRoutes(connections: ConnectionService, settings: AuthSettings) {
    get("/oauth/{provider}/start") {
        val user = call.currentUser() ?: throw CalendarException.Unauthorized("unauthorized")
        val providerId = call.parameters["provider"].orEmpty()
        val returnTo = call.request.queryParameters["return_to"]
        val url = connections.connectUrl(user.id, providerId, returnTo)
        if (call.request.queryParameters["format"] == "json") {
            call.respond(OAuthStartResponse(url))
        } else {
            call.respondRedirect(url)
        }
    }

    get("/oauth/{provider}/callback") {
        val currentUser = call.currentUser()
        val failureRedirect = if (currentUser != null) "/settings?oauth=error" else "/login?oauth=error"
        val providerId = call.parameters["provider"].orEmpty()
        val code = call.request.queryParameters["code"]
        val state = call.request.queryParameters["state"]
        if (code.isNullOrBlank() || state.isNullOrBlank()) {
            call.respondRedirect(failureRedirect)
            return@get
        }
        val outcome = try {
            connections.handleCallback(providerId, code, state, currentUser?.id)
        } catch (_: CalendarException) {
            call.respondRedirect(failureRedirect)
            return@get
        }
        when (outcome) {
            is OAuthCallbackOutcome.Connected -> {
                outcome.sessionToken?.let { call.setSessionCookie(it, settings) }
                call.respondRedirect(outcome.returnTo ?: "/settings?tab=connections&oauth=ok")
            }
            OAuthCallbackOutcome.RegistrationClosed ->
                call.respondRedirect("/login?oauth=registration_closed")
            OAuthCallbackOutcome.EmailTaken ->
                call.respondRedirect("/login?oauth=email_taken")
        }
    }
}
