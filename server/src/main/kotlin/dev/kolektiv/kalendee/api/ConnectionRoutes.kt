package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.oauth.CalendarConnectionSummary
import dev.kolektiv.kalendee.oauth.ConnectionService
import dev.kolektiv.kalendee.oauth.OAuthReauthRequiredException
import dev.kolektiv.kalendee.oauth.google.GoogleSyncException
import dev.kolektiv.kalendee.oauth.google.GoogleSyncService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.connectionRoutes(
    connections: ConnectionService,
    googleSync: GoogleSyncService,
) {
    get("/connections") {
        val user = call.user()
        call.respond(ConnectionsResponse(connections.connections(user.id).map { it.toOut() }))
    }
    get("/connections/providers") {
        call.respond(OAuthProvidersResponse(providerOuts(connections)))
    }
    delete("/connections/{id}") {
        val user = call.user()
        if (!connections.disconnect(user.id, call.connectionId())) {
            throw CalendarException.NotFound("connection not found")
        }
        call.respond(HttpStatusCode.NoContent)
    }
    post("/connections/{id}/sync") {
        val user = call.user()
        val id = call.connectionId()
        val summary = connections.connections(user.id).firstOrNull { it.id == id }
            ?: throw CalendarException.NotFound("connection not found")
        try {
            when (summary.provider) {
                "google" -> googleSync.syncNow(user.id, id)
                "discord" -> {
                    call.respondUnprocessable("use Discord server sync for this connection")
                    return@post
                }
                else -> {
                    call.respondUnprocessable("sync is not supported for this provider")
                    return@post
                }
            }
        } catch (cause: OAuthReauthRequiredException) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorBody(error = "conflict", message = cause.message ?: "reconnect this account"),
            )
            return@post
        } catch (cause: GoogleSyncException) {
            call.respond(
                HttpStatusCode.BadGateway,
                ErrorBody(error = "bad_gateway", message = cause.message ?: "google sync failed"),
            )
            return@post
        }
        val refreshed = connections.connections(user.id).firstOrNull { it.id == id } ?: summary
        call.respond(
            SyncConnectionOut(
                ok = true,
                lastSyncAt = refreshed.lastSyncAt?.toString(),
                lastError = refreshed.lastError,
            ),
        )
    }
}

internal suspend fun providerOuts(connections: ConnectionService): List<OAuthProviderOut> =
    connections.providers().map { provider ->
        OAuthProviderOut(
            id = provider.id,
            displayName = provider.displayName,
            enabled = provider.enabled,
            connectUrl = "/api/v1/oauth/${provider.id}/start",
            registerUrl = if (provider.enabled) registerUrlOrNull(connections, provider.id) else null,
        )
    }

private suspend fun registerUrlOrNull(connections: ConnectionService, providerId: String): String? = try {
    connections.registerUrl(providerId, null)
} catch (_: CalendarException) {
    null
} catch (_: IllegalStateException) {
    null
}

private fun ApplicationCall.connectionId(): String =
    parameters["id"]?.takeIf { it.isNotBlank() }
        ?: throw CalendarException.Invalid("missing connection id")

private suspend fun ApplicationCall.respondUnprocessable(message: String) {
    respond(HttpStatusCode.UnprocessableEntity, ErrorBody(error = "invalid", message = message))
}

internal fun CalendarConnectionSummary.toOut(): ConnectionOut = ConnectionOut(
    id = id,
    provider = provider,
    providerName = providerName,
    accountEmail = accountEmail,
    displayName = displayName,
    status = status,
    lastSyncAt = lastSyncAt?.toString(),
    lastError = lastError,
)
