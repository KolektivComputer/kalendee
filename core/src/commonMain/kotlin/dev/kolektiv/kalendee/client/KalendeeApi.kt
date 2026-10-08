package dev.kolektiv.kalendee.client

import dev.kolektiv.kalendee.auth.LoginUser
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.UpdateUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.UpdateCalendar
import dev.kolektiv.kalendee.calendar.UpdateEvent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Instant
import kotlinx.serialization.json.Json

data class TaggedEvent(val event: Event, val etag: String?)

data class AuthenticatedSession(
    val user: User?,
    val token: String?,
    val verificationRequired: Boolean,
)

class KalendeeApi(
    baseUrl: String,
    tokenProvider: () -> String? = { null },
    engine: HttpClientEngine? = null,
) {
    private val baseUrl: String = baseUrl.trimEnd('/')
    private val tokenProvider: () -> String? = tokenProvider
    private var managedToken: String? = null

    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    private val client: HttpClient = HttpClient(engine ?: platformHttpEngine()) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(this@KalendeeApi.json)
        }
    }

    suspend fun discovery(): DiscoveryResponse =
        client.get("$baseUrl/api/v1").decode()

    suspend fun health(): HealthResponse =
        client.get("$baseUrl/api/v1/health").decode()

    suspend fun login(username: String, password: String): AuthenticatedSession {
        val response = client.post("$baseUrl/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginUser(username = username, password = password))
        }
        val result: AuthResult = response.decode()
        val token = parseSessionToken(response)
        if (token != null) managedToken = token
        return AuthenticatedSession(
            user = result.user,
            token = token,
            verificationRequired = result.verificationRequired,
        )
    }

    suspend fun register(username: String, password: String, email: String? = null): AuthResult {
        val response = client.post("$baseUrl/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = username, password = password, email = email))
        }
        val result: AuthResult = response.decode()
        val token = parseSessionToken(response)
        if (token != null) managedToken = token
        return result
    }

    suspend fun me(): User =
        client.get("$baseUrl/api/v1/auth/me") {
            authorize()
        }.decode()

    suspend fun logout() {
        client.post("$baseUrl/api/v1/auth/logout") {
            authorize()
        }.ensureSuccess()
        managedToken = null
    }

    suspend fun updateMe(update: UpdateUser): User =
        client.patch("$baseUrl/api/v1/auth/me") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(update)
        }.decode()

    suspend fun calendars(): List<Calendar> =
        client.get("$baseUrl/api/v1/calendars") {
            authorize()
        }.decode()

    suspend fun createCalendar(command: CreateCalendar): Calendar =
        client.post("$baseUrl/api/v1/calendars") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(command)
        }.decode()

    suspend fun updateCalendar(id: String, update: UpdateCalendar): Calendar =
        client.patch("$baseUrl/api/v1/calendars/$id") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(update)
        }.decode()

    suspend fun setCalendarHidden(id: String, hidden: Boolean): Calendar =
        client.put("$baseUrl/api/v1/calendars/$id/hidden") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(HiddenBody(hidden = hidden))
        }.decode()

    suspend fun deleteCalendar(id: String) {
        client.delete("$baseUrl/api/v1/calendars/$id") {
            authorize()
        }.ensureSuccess()
    }

    suspend fun events(from: Instant, to: Instant): List<Event> =
        client.get("$baseUrl/api/v1/events") {
            authorize()
            parameter("from", from.toString())
            parameter("to", to.toString())
        }.decode()

    suspend fun calendarEvents(calendarId: String, from: Instant? = null, to: Instant? = null): List<Event> =
        client.get("$baseUrl/api/v1/calendars/$calendarId/events") {
            authorize()
            if (from != null) parameter("from", from.toString())
            if (to != null) parameter("to", to.toString())
        }.decode()

    suspend fun event(id: String): TaggedEvent {
        val response = client.get("$baseUrl/api/v1/events/$id") {
            authorize()
        }
        val event: Event = response.decode()
        return TaggedEvent(event, parseEtag(response.headers[HttpHeaders.ETag]) ?: event.etag)
    }

    suspend fun createEvent(calendarId: String, command: CreateEvent): TaggedEvent {
        val response = client.post("$baseUrl/api/v1/calendars/$calendarId/events") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(command)
        }
        val event: Event = response.decode()
        return TaggedEvent(event, parseEtag(response.headers[HttpHeaders.ETag]) ?: event.etag)
    }

    suspend fun updateEvent(id: String, update: UpdateEvent, etag: String?): TaggedEvent {
        val response = client.patch("$baseUrl/api/v1/events/$id") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(update)
            ifMatch(etag)
        }
        val event: Event = response.decode()
        return TaggedEvent(event, parseEtag(response.headers[HttpHeaders.ETag]) ?: event.etag)
    }

    suspend fun deleteEvent(id: String, etag: String?) {
        client.delete("$baseUrl/api/v1/events/$id") {
            authorize()
            ifMatch(etag)
        }.ensureSuccess()
    }

    suspend fun upcomingReminders(hours: Int = 168): List<ReminderInstanceResponse> =
        client.get("$baseUrl/api/v1/reminders/upcoming") {
            authorize()
            parameter("hours", hours.toString())
        }.decode()

    suspend fun reminderSettings(): ReminderSettingsResponse =
        client.get("$baseUrl/api/v1/reminders/settings") {
            authorize()
        }.decode()

    suspend fun notifications(): List<NotificationOut> =
        client.get("$baseUrl/api/v1/notifications") {
            authorize()
        }.decode()

    suspend fun markNotificationRead(id: String): NotificationStateResponse =
        client.post("$baseUrl/api/v1/notifications/$id/read") {
            authorize()
        }.decode()

    suspend fun markAllNotificationsRead(): NotificationStateResponse =
        client.post("$baseUrl/api/v1/notifications/read-all") {
            authorize()
        }.decode()

    suspend fun organizations(): List<OrganizationSummaryOut> =
        client.get("$baseUrl/api/v1/organizations") {
            authorize()
        }.decode<OrganizationsResponse>().organizations

    suspend fun friends(): FriendsResponse =
        client.get("$baseUrl/api/v1/friends") {
            authorize()
        }.decode()

    suspend fun sendFriendRequest(usernameOrEmail: String): FriendRequestOut =
        client.post("$baseUrl/api/v1/friends/requests") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(SendFriendRequestBody(username = usernameOrEmail))
        }.decode()

    suspend fun acceptFriendRequest(requestId: String): FriendsResponse =
        client.post("$baseUrl/api/v1/friends/requests/$requestId/accept") {
            authorize()
        }.decode()

    suspend fun declineFriendRequest(requestId: String): FriendsResponse =
        client.post("$baseUrl/api/v1/friends/requests/$requestId/decline") {
            authorize()
        }.decode()

    suspend fun removeFriend(userId: String): FriendsResponse =
        client.delete("$baseUrl/api/v1/friends/$userId") {
            authorize()
        }.decode()

    suspend fun searchUsers(query: String, limit: Int = 10): List<UserSearchResultOut> =
        client.get("$baseUrl/api/v1/users/search") {
            authorize()
            parameter("q", query)
            parameter("limit", limit.toString())
        }.decode<UserSearchResponse>().results

    suspend fun createOrganization(command: CreateOrganizationBody): OrganizationSummaryOut =
        client.post("$baseUrl/api/v1/organizations") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(command)
        }.decode()

    suspend fun updateOrganization(id: String, command: UpdateOrganizationBody): OrganizationSummaryOut =
        client.patch("$baseUrl/api/v1/organizations/$id") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(command)
        }.decode()

    suspend fun deleteOrganization(id: String) {
        client.delete("$baseUrl/api/v1/organizations/$id") {
            authorize()
        }.ensureSuccess()
    }

    suspend fun organizationMembers(id: String): OrganizationMembersResponse =
        client.get("$baseUrl/api/v1/organizations/$id/members") {
            authorize()
        }.decode()

    suspend fun organizationInvitations(id: String): OrganizationInvitationsResponse =
        client.get("$baseUrl/api/v1/organizations/$id/invitations") {
            authorize()
        }.decode()

    suspend fun inviteToOrganization(id: String, identifier: String, role: String = "member"): OrganizationInvitationOut =
        client.post("$baseUrl/api/v1/organizations/$id/invitations") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(InviteToOrganizationBody(identifier = identifier, role = role))
        }.decode()

    suspend fun revokeOrganizationInvitation(id: String, invitationId: String): OrganizationInvitationOut =
        client.delete("$baseUrl/api/v1/organizations/$id/invitations/$invitationId") {
            authorize()
        }.decode()

    suspend fun setOrganizationMemberRole(id: String, userId: String, role: String): OrganizationMemberOut =
        client.patch("$baseUrl/api/v1/organizations/$id/members/$userId") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(TeamRoleBody(role = role))
        }.decode()

    suspend fun removeOrganizationMember(id: String, userId: String) {
        client.delete("$baseUrl/api/v1/organizations/$id/members/$userId") {
            authorize()
        }.ensureSuccess()
    }

    suspend fun acceptOrganizationInvitation(invitationId: String? = null, token: String? = null): InvitationAcceptedResponse =
        client.post("$baseUrl/api/v1/organizations/invitations/accept") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(AcceptInvitationBody(invitationId = invitationId, token = token))
        }.decode()

    suspend fun declineOrganizationInvitation(invitationId: String) {
        client.post("$baseUrl/api/v1/organizations/invitations/decline") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(DeclineInvitationBody(invitationId = invitationId))
        }.ensureSuccess()
    }

    suspend fun organizationTeams(id: String): OrganizationTeamsResponse =
        client.get("$baseUrl/api/v1/organizations/$id/teams") {
            authorize()
        }.decode()

    suspend fun createOrganizationTeam(id: String, command: CreateOrganizationTeamBody): OrganizationTeamOut =
        client.post("$baseUrl/api/v1/organizations/$id/teams") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(command)
        }.decode()

    suspend fun updateOrganizationTeam(teamId: String, command: UpdateOrganizationTeamBody): OrganizationTeamOut =
        client.patch("$baseUrl/api/v1/teams/$teamId") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(command)
        }.decode()

    suspend fun deleteOrganizationTeam(teamId: String) {
        client.delete("$baseUrl/api/v1/teams/$teamId") {
            authorize()
        }.ensureSuccess()
    }

    suspend fun addOrganizationTeamMember(teamId: String, userId: String): OrganizationTeamMemberOut =
        client.post("$baseUrl/api/v1/teams/$teamId/members") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(TeamMemberBody(userId = userId))
        }.decode()

    suspend fun removeOrganizationTeamMember(teamId: String, userId: String) {
        client.delete("$baseUrl/api/v1/teams/$teamId/members/$userId") {
            authorize()
        }.ensureSuccess()
    }

    suspend fun setOrganizationTeamMemberRole(teamId: String, userId: String, role: String): OrganizationTeamMemberOut =
        client.patch("$baseUrl/api/v1/teams/$teamId/members/$userId") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(TeamRoleBody(role = role))
        }.decode()

    suspend fun grantCalendarToTeam(teamId: String, calendarId: String, permission: String): OrganizationTeamGrantOut =
        client.put("$baseUrl/api/v1/teams/$teamId/calendars/$calendarId") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(TeamGrantBody(permission = permission))
        }.decode()

    suspend fun revokeCalendarFromTeam(teamId: String, calendarId: String) {
        client.delete("$baseUrl/api/v1/teams/$teamId/calendars/$calendarId") {
            authorize()
        }.ensureSuccess()
    }

    suspend fun transferCalendar(calendarId: String, organizationId: String?, teamId: String?): Calendar =
        client.put("$baseUrl/api/v1/calendars/$calendarId/transfer") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(TransferCalendarBody(organizationId = organizationId, teamId = teamId))
        }.decode()

    suspend fun moveEvent(
        eventId: String,
        calendarId: String,
        from: String? = null,
        scope: String = "following",
        etag: String? = null,
    ): List<Event> =
        client.post("$baseUrl/api/v1/events/$eventId/move") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(MoveEventBody(calendarId = calendarId, scope = scope, from = from, etag = etag))
        }.decode<MoveEventResponse>().events

    suspend fun setMyPublicAccess(mode: String): User =
        client.put("$baseUrl/api/v1/auth/me/public-access") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(PublicAccessBody(mode = mode))
        }.decode()

    suspend fun oauthProviders(): List<OAuthProviderOut> =
        client.get("$baseUrl/api/v1/oauth/providers")
            .decode<OAuthProvidersResponse>().providers

    suspend fun connectionProviders(): List<OAuthProviderOut> =
        client.get("$baseUrl/api/v1/connections/providers") {
            authorize()
        }.decode<OAuthProvidersResponse>().providers

    suspend fun connections(): List<ConnectionOut> =
        client.get("$baseUrl/api/v1/connections") {
            authorize()
        }.decode<ConnectionsResponse>().connections

    suspend fun disconnectConnection(id: String) {
        client.delete("$baseUrl/api/v1/connections/$id") {
            authorize()
        }.ensureSuccess()
    }

    suspend fun syncConnection(id: String): SyncConnectionOut =
        client.post("$baseUrl/api/v1/connections/$id/sync") {
            authorize()
        }.decode()

    suspend fun adminSettings(): AdminSettingsOut =
        client.get("$baseUrl/api/v1/admin/settings") {
            authorize()
        }.decode()

    suspend fun updateAdminSettings(body: AdminSettingsBody): AdminSettingsOut =
        client.patch("$baseUrl/api/v1/admin/settings") {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(body)
        }.decode()

    private fun HttpRequestBuilder.authorize() {
        authToken()?.let { header(HttpHeaders.Cookie, it) }
    }

    private fun HttpRequestBuilder.ifMatch(etag: String?) {
        if (etag == null) return
        val value = if (etag == "*") "*" else "\"$etag\""
        header(HttpHeaders.IfMatch, value)
    }

    private fun authToken(): String? = managedToken ?: tokenProvider()

    private fun parseSessionToken(response: HttpResponse): String? {
        val raw = response.headers.getAll(HttpHeaders.SetCookie)?.firstOrNull() ?: return null
        val pair = raw.substringBefore(';').trim()
        if (pair.isEmpty() || !pair.contains('=')) return null
        return pair
    }

    private fun parseEtag(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value == "*") return "*"
        return value.removePrefix("W/").trim().removeSurrounding("\"")
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T {
        if (status.isSuccess()) return body()
        throw apiException()
    }

    private suspend fun HttpResponse.ensureSuccess() {
        if (!status.isSuccess()) throw apiException()
    }

    private suspend fun HttpResponse.apiException(): KalendeeApiException {
        val text = runCatching { bodyAsText() }.getOrNull().orEmpty()
        val parsed = runCatching { json.decodeFromString<ErrorBody>(text) }.getOrNull()
        val fallback = text.ifBlank { status.description.ifBlank { "HTTP ${status.value}" } }
        return KalendeeApiException(
            status = status.value,
            code = parsed?.error,
            message = parsed?.message ?: fallback,
        )
    }
}
