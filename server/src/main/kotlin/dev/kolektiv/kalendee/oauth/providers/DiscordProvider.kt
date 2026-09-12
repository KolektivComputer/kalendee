package dev.kolektiv.kalendee.oauth.providers

import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.oauth.CalendarProvider
import dev.kolektiv.kalendee.oauth.OAuthClient
import dev.kolektiv.kalendee.oauth.OAuthJson
import dev.kolektiv.kalendee.oauth.OAuthReauthRequiredException
import dev.kolektiv.kalendee.oauth.OAuthTokens
import dev.kolektiv.kalendee.oauth.PkceChallenge
import dev.kolektiv.kalendee.oauth.ProviderAccount
import dev.kolektiv.kalendee.oauth.ProviderOAuthSettings
import dev.kolektiv.kalendee.oauth.parseOAuthTokens
import dev.kolektiv.kalendee.oauth.requireSuccess
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DiscordProvider(
    private val settings: ProviderOAuthSettings,
    private val http: HttpClient,
    private val api: DiscordApi = DiscordApi(http = http, botToken = settings.botToken),
) : CalendarProvider {
    override val id: String = "discord"
    override val displayName: String = "Discord"
    override val enabled: Boolean = settings.enabled
    override val scopes: List<String> = Scopes

    override fun oauthClient(): OAuthClient = DiscordOAuthClient(settings = settings, http = http, api = api)

    companion object {
        val Scopes: List<String> = listOf("identify", "guilds")
    }
}

class DiscordOAuthClient(
    private val settings: ProviderOAuthSettings,
    private val http: HttpClient,
    private val api: DiscordApi,
) : OAuthClient {
    override fun authorizationUrl(state: String, pkce: PkceChallenge, redirectUri: String): String =
        URLBuilder(AuthorizationEndpoint).apply {
            parameters.append("client_id", settings.clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_type", "code")
            parameters.append("scope", DiscordProvider.Scopes.joinToString(" "))
            parameters.append("state", state)
            parameters.append("code_challenge", pkce.challenge)
            parameters.append("code_challenge_method", "S256")
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
        ) {
            header(HttpHeaders.UserAgent, DiscordApi.UserAgent)
        }
        requireSuccess(response, "discord token exchange")
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
        ) {
            header(HttpHeaders.UserAgent, DiscordApi.UserAgent)
        }
        val body = response.bodyAsText()
        if (response.status == HttpStatusCode.BadRequest && oauthError(body) == "invalid_grant") {
            throw OAuthReauthRequiredException("discord refresh token was rejected")
        }
        requireSuccess(response, "discord token refresh")
        return parseOAuthTokens(body)
    }

    override suspend fun revoke(tokens: OAuthTokens) {
        try {
            http.submitForm(
                url = RevokeEndpoint,
                formParameters = Parameters.build {
                    append("token", tokens.refreshToken ?: tokens.accessToken)
                },
            ) {
                header(HttpHeaders.UserAgent, DiscordApi.UserAgent)
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            // Revocation is best effort; the local connection is still removed.
        }
    }

    override suspend fun accountIdentity(tokens: OAuthTokens): ProviderAccount {
        val user = try {
            api.user(tokens.accessToken)
        } catch (cause: DiscordApiException) {
            throw CalendarException.Unauthorized("discord account request failed: ${cause.message}")
        }
        return ProviderAccount(
            externalId = user.id,
            email = null,
            displayName = user.globalName?.takeIf { it.isNotBlank() } ?: user.username,
        )
    }

    private fun oauthError(body: String): String? = runCatching {
        OAuthJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    companion object {
        private const val AuthorizationEndpoint = "https://discord.com/oauth2/authorize"
        private const val TokenEndpoint = "https://discord.com/api/v10/oauth2/token"
        private const val RevokeEndpoint = "https://discord.com/api/v10/oauth2/token/revoke"
    }
}
