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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class MicrosoftProvider(
    private val settings: ProviderOAuthSettings,
    private val http: HttpClient,
) : CalendarProvider {
    override val id: String = "microsoft"
    override val displayName: String = "Microsoft Outlook"
    override val enabled: Boolean = settings.enabled
    override val scopes: List<String> = MicrosoftOAuthClient.Scopes

    override fun oauthClient(): OAuthClient = MicrosoftOAuthClient(settings = settings, http = http)

    override fun syncAdapter(): CalendarSyncAdapter? = null
}

class MicrosoftOAuthClient(
    private val settings: ProviderOAuthSettings,
    private val http: HttpClient,
) : OAuthClient {
    override fun authorizationUrl(state: String, pkce: PkceChallenge, redirectUri: String): String =
        URLBuilder(AuthorizationEndpoint).apply {
            parameters.append("client_id", settings.clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_type", "code")
            parameters.append("response_mode", "query")
            parameters.append("scope", Scopes.joinToString(" "))
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
        )
        requireSuccess(response, "microsoft token exchange")
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
        requireSuccess(response, "microsoft token refresh")
        return parseOAuthTokens(response.bodyAsText())
    }

    override suspend fun revoke(tokens: OAuthTokens) {
        // Microsoft has no per-token revocation endpoint; disconnect just drops the row.
    }

    override suspend fun accountIdentity(tokens: OAuthTokens): ProviderAccount {
        val response = http.get(MeEndpoint) {
            header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
        }
        requireSuccess(response, "microsoft account request")
        val obj = parseJsonObject(response.bodyAsText(), "microsoft account response")
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
            ?: throw CalendarException.Unauthorized("microsoft account response is missing id")
        val email = obj["mail"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: obj["userPrincipalName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return ProviderAccount(
            externalId = id,
            email = email,
            displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        val Scopes: List<String> = listOf(
            "offline_access",
            "openid",
            "email",
            "profile",
            "User.Read",
            "Calendars.ReadWrite",
        )

        private const val Tenant = "common"
        private const val AuthorizationEndpoint = "https://login.microsoftonline.com/$Tenant/oauth2/v2.0/authorize"
        private const val TokenEndpoint = "https://login.microsoftonline.com/$Tenant/oauth2/v2.0/token"
        private const val MeEndpoint = "https://graph.microsoft.com/v1.0/me"
    }
}
