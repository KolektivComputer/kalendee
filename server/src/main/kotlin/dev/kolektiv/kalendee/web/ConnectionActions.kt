package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.oauth.ConnectionService
import dev.kolektiv.keel.KeelAction

class ConnectionActions(
    private val connections: ConnectionService,
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
