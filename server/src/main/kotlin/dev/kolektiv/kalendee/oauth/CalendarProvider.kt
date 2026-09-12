package dev.kolektiv.kalendee.oauth

import dev.kolektiv.kalendee.auth.SessionTokens
import dev.kolektiv.kalendee.calendar.CalendarException
import io.ktor.client.statement.HttpResponse
import java.security.MessageDigest
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class PkceChallenge(
    val verifier: String,
    val challenge: String,
)

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Instant? = null,
    val scopes: List<String> = emptyList(),
    val tokenType: String = "Bearer",
)

data class ProviderAccount(
    val externalId: String,
    val email: String? = null,
    val displayName: String? = null,
)

interface OAuthClient {
    fun authorizationUrl(state: String, pkce: PkceChallenge, redirectUri: String): String

    suspend fun exchange(code: String, pkce: PkceChallenge, redirectUri: String): OAuthTokens

    suspend fun refresh(refreshToken: String): OAuthTokens

    suspend fun revoke(tokens: OAuthTokens)

    suspend fun accountIdentity(tokens: OAuthTokens): ProviderAccount
}

/**
 * Placeholder for the external-calendar sync engine. Providers return null until a
 * later milestone implements calendar discovery, pull, and push.
 */
interface CalendarSyncAdapter {
    val providerId: String
}

interface CalendarProvider {
    val id: String
    val displayName: String
    val enabled: Boolean
    val scopes: List<String>

    fun oauthClient(): OAuthClient

    fun syncAdapter(): CalendarSyncAdapter? = null
}

object Pkce {
    fun generate(): PkceChallenge {
        val verifier = SessionTokens.generate()
        return PkceChallenge(verifier = verifier, challenge = challenge(verifier))
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}

internal val OAuthJson = Json { ignoreUnknownKeys = true }

internal fun requireSuccess(response: HttpResponse, label: String) {
    if (response.status.value !in 200..299) {
        throw CalendarException.Unauthorized("$label failed (HTTP ${response.status.value})")
    }
}

internal fun parseOAuthTokens(body: String): OAuthTokens {
    val obj = parseJsonObject(body, "oauth token response")
    val accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
        ?: throw CalendarException.Unauthorized("oauth token response is missing access_token")
    val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull
    return OAuthTokens(
        accessToken = accessToken,
        refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull,
        expiresAt = expiresIn?.let { Clock.System.now() + it.seconds },
        scopes = obj["scope"]?.jsonPrimitive?.contentOrNull
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            .orEmpty(),
    )
}

internal fun parseJsonObject(body: String, label: String): JsonObject = try {
    OAuthJson.parseToJsonElement(body).jsonObject
} catch (cause: SerializationException) {
    throw CalendarException.Unauthorized("invalid $label")
} catch (cause: IllegalArgumentException) {
    throw CalendarException.Unauthorized("invalid $label")
}
