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
import dev.kolektiv.kalendee.oauth.parseJsonObject
import dev.kolektiv.kalendee.oauth.requireSuccess
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GoogleProvider(
    private val settings: ProviderOAuthSettings,
    private val http: HttpClient,
) : CalendarProvider {
    override val id: String = "google"
    override val displayName: String = "Google"
    override val enabled: Boolean = settings.enabled
    override val scopes: List<String> = Scopes

    override fun oauthClient(): OAuthClient = GoogleOAuthClient(settings = settings, http = http)

    fun calendarApi(): GoogleCalendarApi = GoogleCalendarApi(http)

    companion object {
        val Scopes: List<String> = listOf(
            "openid",
            "email",
            "profile",
            "https://www.googleapis.com/auth/calendar.readonly",
        )
    }
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
            parameters.append("scope", GoogleProvider.Scopes.joinToString(" "))
            parameters.append("state", state)
            parameters.append("code_challenge", pkce.challenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("access_type", "offline")
            parameters.append("prompt", "consent")
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
        val body = response.bodyAsText()
        if (response.status == HttpStatusCode.BadRequest && oauthError(body) == "invalid_grant") {
            throw OAuthReauthRequiredException("google refresh token was rejected")
        }
        requireSuccess(response, "google token refresh")
        return parseOAuthTokens(body)
    }

    override suspend fun revoke(tokens: OAuthTokens) {
        runCatching {
            http.submitForm(
                url = RevokeEndpoint,
                formParameters = Parameters.build {
                    append("token", tokens.refreshToken ?: tokens.accessToken)
                },
            )
        }
    }

    override suspend fun accountIdentity(tokens: OAuthTokens): ProviderAccount {
        val response = http.get(UserInfoEndpoint) {
            header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
        }
        requireSuccess(response, "google userinfo")
        val obj = parseJsonObject(response.bodyAsText(), "google userinfo")
        val sub = obj["sub"]?.jsonPrimitive?.contentOrNull
            ?: throw CalendarException.Unauthorized("google userinfo is missing sub")
        return ProviderAccount(
            externalId = sub,
            email = obj["email"]?.jsonPrimitive?.contentOrNull,
            displayName = obj["name"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun oauthError(body: String): String? = runCatching {
        OAuthJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    companion object {
        private const val AuthorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TokenEndpoint = "https://oauth2.googleapis.com/token"
        private const val RevokeEndpoint = "https://oauth2.googleapis.com/revoke"
        private const val UserInfoEndpoint = "https://openidconnect.googleapis.com/v1/userinfo"
    }
}

data class GoogleRemoteCalendar(
    val id: String,
    val summary: String,
    val timeZone: String?,
    val primary: Boolean,
)

data class GoogleRemoteEvent(
    val id: String,
    val calendarId: String,
    val summary: String,
    val description: String?,
    val location: String?,
    val start: String,
    val end: String,
    val allDay: Boolean,
    val etag: String?,
    val status: String?,
    val updated: String?,
)

data class GoogleEventPage(
    val events: List<GoogleRemoteEvent>,
    val nextSyncToken: String?,
    val nextPageToken: String?,
    val expiredSyncToken: Boolean = false,
)

class GoogleCalendarApi(private val http: HttpClient) {
    suspend fun listCalendars(accessToken: String): List<GoogleRemoteCalendar> {
        val response = http.get("https://www.googleapis.com/calendar/v3/users/me/calendarList") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        requireSuccess(response, "google calendar list")
        val items = parseJsonObject(response.bodyAsText(), "google calendar list")["items"]?.jsonArray.orEmpty()
        return items.map { element ->
            val obj = element.jsonObject
            GoogleRemoteCalendar(
                id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                summary = obj["summary"]?.jsonPrimitive?.contentOrNull ?: "Google",
                timeZone = obj["timeZone"]?.jsonPrimitive?.contentOrNull,
                primary = obj["primary"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }.filter { it.id.isNotBlank() }
    }

    suspend fun listEvents(
        accessToken: String,
        calendarId: String,
        syncToken: String? = null,
        pageToken: String? = null,
    ): GoogleEventPage {
        val encoded = java.net.URLEncoder.encode(calendarId, Charsets.UTF_8)
        val response = http.get("https://www.googleapis.com/calendar/v3/calendars/$encoded/events") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            if (syncToken != null) parameter("syncToken", syncToken)
            if (pageToken != null) parameter("pageToken", pageToken)
            parameter("singleEvents", "true")
            parameter("maxResults", "250")
        }
        if (response.status == HttpStatusCode.Gone) {
            return GoogleEventPage(events = emptyList(), nextSyncToken = null, nextPageToken = null, expiredSyncToken = true)
        }
        requireSuccess(response, "google events")
        val obj = parseJsonObject(response.bodyAsText(), "google events")
        val events = obj["items"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val startObj = item["start"]?.jsonObject
            val endObj = item["end"]?.jsonObject
            val allDay = startObj?.get("date") != null
            val start = startObj?.get("dateTime")?.jsonPrimitive?.contentOrNull
                ?: startObj?.get("date")?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val end = endObj?.get("dateTime")?.jsonPrimitive?.contentOrNull
                ?: endObj?.get("date")?.jsonPrimitive?.contentOrNull
                ?: start
            GoogleRemoteEvent(
                id = id,
                calendarId = calendarId,
                summary = item["summary"]?.jsonPrimitive?.contentOrNull ?: "(No title)",
                description = item["description"]?.jsonPrimitive?.contentOrNull,
                location = item["location"]?.jsonPrimitive?.contentOrNull,
                start = start,
                end = end,
                allDay = allDay,
                etag = item["etag"]?.jsonPrimitive?.contentOrNull,
                status = item["status"]?.jsonPrimitive?.contentOrNull,
                updated = item["updated"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return GoogleEventPage(
            events = events,
            nextSyncToken = obj["nextSyncToken"]?.jsonPrimitive?.contentOrNull,
            nextPageToken = obj["nextPageToken"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
