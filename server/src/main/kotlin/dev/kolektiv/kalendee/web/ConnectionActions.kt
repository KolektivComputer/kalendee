package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.oauth.ConnectionService
import dev.kolektiv.kalendee.oauth.OAuthReauthRequiredException
import dev.kolektiv.kalendee.oauth.google.GoogleSyncException
import dev.kolektiv.kalendee.oauth.google.GoogleSyncService
import dev.kolektiv.keel.KeelAction
import dev.kolektiv.keel.ktor.PageValidationException

class ConnectionActions(
    private val connections: ConnectionService,
    private val googleSync: GoogleSyncService,
    private val auth: AuthService,
    private val settings: AuthSettings,
) {
    @KeelAction("kalendee.connectProvider")
    suspend fun connectProvider(input: ConnectProviderIn): ConnectProviderOut = mapDomainErrors("providerId") {
        val user = requireSessionUser(auth, settings)
        ConnectProviderOut(
            url = connections.connectUrl(user.id, input.providerId, input.returnTo),
        )
    }

    @KeelAction("kalendee.disconnectAccount")
    suspend fun disconnectAccount(input: DisconnectAccountIn): DeletedOut = mapDomainErrors("connectionId") {
        val user = requireSessionUser(auth, settings)
        if (!connections.disconnect(user.id, input.connectionId)) {
            throw CalendarException.NotFound("connection not found")
        }
        DeletedOut()
    }

    @KeelAction("kalendee.syncConnection")
    suspend fun syncConnection(input: SyncConnectionIn): SyncConnectionOut = mapSyncErrors("connectionId") {
        val user = requireSessionUser(auth, settings)
        val summary = connections.connections(user.id).firstOrNull { it.id == input.connectionId }
            ?: throw CalendarException.NotFound("connection not found")
        when (summary.provider) {
            "google" -> googleSync.syncNow(user.id, input.connectionId)
            "discord" -> throw CalendarException.Invalid("use Discord server sync for this connection")
            else -> throw CalendarException.Invalid("sync is not supported for this provider")
        }
        val refreshed = connections.connections(user.id).firstOrNull { it.id == input.connectionId } ?: summary
        SyncConnectionOut(
            ok = true,
            lastSyncAt = refreshed.lastSyncAt?.toString(),
            lastError = refreshed.lastError,
        )
    }

    fun providerSummaries(): List<ProviderSummary> = connections.providers().map { provider ->
        ProviderSummary(
            id = provider.id,
            displayName = provider.displayName,
            enabled = provider.enabled,
            connectUrl = "/api/v1/oauth/${provider.id}/start",
        )
    }

    suspend fun connectionSummaries(userId: UserId): List<ConnectionSummary> =
        connections.connections(userId).map { connection ->
            ConnectionSummary(
                id = connection.id,
                provider = connection.provider,
                providerName = connection.providerName,
                accountEmail = connection.accountEmail,
                displayName = connection.displayName,
                status = connection.status,
                lastSyncAt = connection.lastSyncAt?.toString(),
                lastError = connection.lastError,
            )
        }
}

private suspend fun <T> mapSyncErrors(field: String, block: suspend () -> T): T = try {
    mapDomainErrors(field, block)
} catch (ex: OAuthReauthRequiredException) {
    throw PageValidationException(field, "reconnect this Google account")
} catch (ex: GoogleSyncException) {
    throw PageValidationException(field, ex.message ?: "google sync failed")
}
