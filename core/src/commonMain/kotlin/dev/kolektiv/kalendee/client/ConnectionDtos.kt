package dev.kolektiv.kalendee.client

import dev.kolektiv.kalendee.calendar.Event
import kotlinx.serialization.Serializable

@Serializable
data class TransferCalendarBody(
    val organizationId: String? = null,
    val teamId: String? = null,
)

@Serializable
data class MoveEventBody(
    val calendarId: String,
    val scope: String = "following",
    val from: String? = null,
    val etag: String? = null,
)

@Serializable
data class MoveEventResponse(
    val events: List<Event> = emptyList(),
)

@Serializable
data class PublicAccessBody(
    val mode: String,
)

@Serializable
data class OAuthProvidersResponse(
    val providers: List<OAuthProviderOut> = emptyList(),
)

@Serializable
data class OAuthProviderOut(
    val id: String,
    val displayName: String,
    val enabled: Boolean,
    val connectUrl: String,
    val registerUrl: String? = null,
)

@Serializable
data class ConnectionsResponse(
    val connections: List<ConnectionOut> = emptyList(),
)

@Serializable
data class ConnectionOut(
    val id: String,
    val provider: String,
    val providerName: String,
    val accountEmail: String? = null,
    val displayName: String? = null,
    val status: String = "",
    val lastSyncAt: String? = null,
    val lastError: String? = null,
)

@Serializable
data class SyncConnectionOut(
    val ok: Boolean,
    val lastSyncAt: String? = null,
    val lastError: String? = null,
)

@Serializable
data class AdminSettingsOut(
    val registrationOpen: Boolean,
    val oauthRegistrationOpen: Boolean,
    val emailVerification: String,
    val publicAccess: String,
)

@Serializable
data class AdminSettingsBody(
    val registrationOpen: Boolean? = null,
    val oauthRegistrationOpen: Boolean? = null,
    val emailVerification: String? = null,
    val publicAccess: String? = null,
)
