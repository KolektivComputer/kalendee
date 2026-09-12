package dev.kolektiv.kalendee.oauth.providers

import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.oauth.CalendarProvider
import dev.kolektiv.kalendee.oauth.CalendarSyncAdapter
import dev.kolektiv.kalendee.oauth.OAuthClient
import dev.kolektiv.kalendee.oauth.OAuthTokens
import dev.kolektiv.kalendee.oauth.PkceChallenge
import dev.kolektiv.kalendee.oauth.ProviderAccount
import dev.kolektiv.kalendee.oauth.ProviderOAuthSettings
import dev.kolektiv.kalendee.oauth.parseJsonObject
import dev.kolektiv.kalendee.oauth.parseOAuthTokens
import dev.kolektiv.kalendee.oauth.requireSuccess
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class GoogleProvider(
    private val settings: ProviderOAuthSettings,
    private val http: HttpClient,
) : CalendarProvider {
    override val id: String = "google"
    override val displayName: String = "Google Calendar"
    override val enabled: Boolean = settings.enabled
    override val scopes: List<String> = GoogleOAuthClient.Scopes

    override fun oauthClient(): OAuthClient = GoogleOAuthClient(settings = settings, http = http)

    override fun syncAdapter(): CalendarSyncAdapter? = null
}

class GoogleOAuthClient(
    private val settings: ProviderOAuthSettings,
    private val http: HttpClient,
) : OAuthClient {
    override fun authorizationUrl(state: String, pkce: PkceChallenge, redirectUri: String): String =
        URLBuilder(AuthorizationEndpoint).apply {
            parameters.append("client_id", settings.clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_type", "code")
            parameters.append("scope", Scopes.joinToString(" "))
            parameters.append("state", state)
            parameters.append("code_challenge", pkce.challenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("access_type", "offline")
            parameters.append("prompt", "consent")
            parameters.append("include_granted_scopes", "true")
        }.buildString()

    override suspend fun exchange(code: String, pkce: PkceChallenge, redirectUri: String): OAuthTokens {
        val response = http.submitForm(
            url = TokenEndpoint,
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("client_id", settings.clientId)
                append("client_secret", settings.clientSecret)
                append("redirect_uri", redirectUri)
                append("code_verifier", pkce.verifier)
            },
        )
        requireSuccess(response, "google token exchange")
        return parseOAuthTokens(response.bodyAsText())
    }

    override suspend fun refresh(refreshToken: String): OAuthTokens {
        val response = http.submitForm(
            url = TokenEndpoint,
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", settings.clientId)
                append("client_secret", settings.clientSecret)
            },
        )
        requireSuccess(response, "google token refresh")
        return parseOAuthTokens(response.bodyAsText())
    }

    override suspend fun revoke(tokens: OAuthTokens) {
        try {
            http.submitForm(
                url = RevokeEndpoint,
                formParameters = Parameters.build {
                    append("token", tokens.refreshToken ?: tokens.accessToken)
                },
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            // Revocation is best effort; the local connection is still removed.
        }
    }

    override suspend fun accountIdentity(tokens: OAuthTokens): ProviderAccount {
        val response = http.get(UserInfoEndpoint) {
            header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
        }
        requireSuccess(response, "google userinfo request")
        val obj = parseJsonObject(response.bodyAsText(), "google userinfo response")
        val sub = obj["sub"]?.jsonPrimitive?.contentOrNull
            ?: throw CalendarException.Unauthorized("google userinfo response is missing sub")
        return ProviderAccount(
            externalId = sub,
            email = obj["email"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            displayName = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        // Full calendar scope for future two-way sync; switch to calendar.readonly
        // if the product decides connections stay pull-only.
        val Scopes: List<String> = listOf(
            "openid",
            "email",
            "profile",
            "https://www.googleapis.com/auth/calendar",
        )

        private const val AuthorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TokenEndpoint = "https://oauth2.googleapis.com/token"
        private const val RevokeEndpoint = "https://oauth2.googleapis.com/revoke"
        private const val UserInfoEndpoint = "https://openidconnect.googleapis.com/v1/userinfo"
    }
}
