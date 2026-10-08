package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.Event
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class DiscoveryResponse(
    val service: String,
    val api: String,
)

@Serializable
data class HealthResponse(
    val status: String,
)

@Serializable
data class ErrorBody(
    val error: String,
    val message: String,
)

@Serializable
data class AuthResult(
    val user: User? = null,
    val verificationRequired: Boolean = false,
    val email: String? = null,
)

@Serializable
data class VerifyEmailBody(
    val token: String,
)

@Serializable
data class VerifyEmailResponse(
    val ok: Boolean,
    val email: String? = null,
)

@Serializable
data class ResendVerificationBody(
    val username: String,
)

@Serializable
data class OkResponse(
    val ok: Boolean = true,
)

@Serializable
data class HiddenBody(
    val hidden: Boolean,
)

@Serializable
data class AvatarOut(
    val avatarUrl: String,
    val version: Long,
)

@Serializable
data class NotificationOut(
    val id: String,
    val kind: String,
    val title: String,
    val body: String? = null,
    val href: String? = null,
    val read: Boolean,
    val createdAt: String,
)

@Serializable
data class NotificationStateResponse(
    val unreadCount: Int,
)

@Serializable
data class ShareCalendarBody(
    val username: String,
    val permission: String = "read",
)

@Serializable
data class UpdateShareBody(
    val permission: String,
)

@Serializable
data class PublicLinkBody(
    val enabled: Boolean,
)

@Serializable
data class PublicCalendarOut(
    val calendar: Calendar,
    val following: Boolean,
)

@Serializable
data class ReminderSettingsBody(
    val defaultOffsetsSeconds: List<Int>,
    val notifyAtStart: Boolean,
)

@Serializable
data class ReminderSettingsResponse(
    val defaultOffsetsSeconds: List<Int>,
    val notifyAtStart: Boolean,
)

@Serializable
data class EventRemindersResponse(
    val eventId: String,
    val offsetsSeconds: List<Int>,
    val useDefaults: Boolean,
    val defaultOffsetsSeconds: List<Int>,
    val notifyAtStart: Boolean,
)

@Serializable
data class SetEventRemindersBody(
    val offsetsSeconds: List<Int>,
    val useDefaults: Boolean,
)

@Serializable
data class ReminderInstanceResponse(
    val eventId: String,
    val calendarId: String,
    val calendarName: String,
    val calendarColor: String,
    val title: String,
    val start: String,
    val allDay: Boolean,
    val offsetSeconds: Int,
    val remindAt: String,
)

@Serializable
data class AvailabilityWindowBody(
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
)

@Serializable
data class UpdateAvailabilityBody(
    val requestsEnabled: Boolean,
    val slotMinutes: Int = 60,
    val accessMode: String = "inherit",
    val windows: List<AvailabilityWindowBody> = emptyList(),
)

@Serializable
data class RequestTimeSlotBody(
    val start: Instant,
    val end: Instant,
    val message: String? = null,
)

@Serializable
data class PublicRequestTimeSlotBody(
    val name: String,
    val email: String? = null,
    val start: Instant,
    val end: Instant,
    val message: String? = null,
)

@Serializable
data class RespondTimeSlotBody(
    val accept: Boolean,
    val message: String? = null,
)

@Serializable
data class InviteEventBody(
    val username: String,
    val name: String? = null,
)

@Serializable
data class RespondEventBody(
    val status: String,
)

@Serializable
data class SetOpenRsvpBody(
    val enabled: Boolean,
)

@Serializable
data class PublicRsvpBody(
    val name: String,
    val email: String? = null,
    val status: String,
)

@Serializable
data class RsvpByTokenBody(
    val token: String,
    val status: String,
)

@Serializable
data class AdminUpdateUserBody(
    val displayName: String? = null,
    val email: String? = null,
    val password: String? = null,
    val admin: Boolean? = null,
)

@Serializable
data class AdminUserOut(
    val id: String,
    val username: String,
    val displayName: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val admin: Boolean,
    val superadmin: Boolean = false,
    val timeZone: String,
    val storageBytes: Long = 0,
    val quotaBytes: Long? = null,
    val groups: List<String> = emptyList(),
    val createdAt: String,
)

@Serializable
data class AdminCalendarOut(
    val id: String,
    val displayName: String,
    val ownerUsername: String,
    val eventCount: Long,
    val publicLinkEnabled: Boolean,
)

@Serializable
data class AdminSetCalendarPublicBody(
    val enabled: Boolean,
)

@Serializable
data class AdminCreateGroupBody(
    val name: String,
    val storageQuotaBytes: Long? = null,
)

@Serializable
data class AdminUpdateGroupBody(
    val name: String? = null,
    val storageQuotaBytes: Long? = null,
    val clearQuota: Boolean = false,
)

@Serializable
data class AdminGroupOut(
    val id: String,
    val name: String,
    val isSystem: Boolean,
    val storageQuotaBytes: Long? = null,
    val memberCount: Long,
)

@Serializable
data class AdminGroupMemberOut(
    val userId: String,
    val username: String,
    val displayName: String,
    val admin: Boolean,
)

@Serializable
data class AdminGroupMembersResponse(
    val groupId: String,
    val members: List<AdminGroupMemberOut>,
)

@Serializable
data class AdminSetGroupMembersBody(
    val userIds: List<String>,
)

@Serializable
data class OrganizationsResponse(
    val organizations: List<OrganizationSummaryOut>,
)

@Serializable
data class OrganizationSummaryOut(
    val id: String,
    val slug: String,
    val displayName: String,
    val description: String? = null,
    val visibility: String = "private",
    val avatarUrl: String? = null,
    val role: String? = null,
    val memberCount: Int = 0,
)

@Serializable
data class FriendsResponse(
    val friends: List<FriendSummaryOut>,
    val incoming: List<FriendRequestSummaryOut>,
)

@Serializable
data class FriendSummaryOut(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

@Serializable
data class FriendRequestSummaryOut(
    val id: String,
    val user: FriendSummaryOut,
    val createdAt: String,
)

@Serializable
data class SendFriendRequestBody(
    val username: String,
)

@Serializable
data class FriendRequestResultOut(
    val status: String,
    val friend: FriendSummaryOut? = null,
)

@Serializable
data class UserSearchResponse(
    val results: List<UserSearchResultOut>,
)

@Serializable
data class UserSearchResultOut(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val relationship: String,
)

@Serializable
data class CreateOrganizationBody(
    val slug: String,
    val displayName: String,
    val description: String? = null,
)

@Serializable
data class UpdateOrganizationBody(
    val displayName: String? = null,
    val description: String? = null,
    val visibility: String? = null,
)

@Serializable
data class OrganizationMemberOut(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val role: String,
    val isSelf: Boolean = false,
)

@Serializable
data class OrganizationMembersResponse(
    val organizationId: String,
    val viewerRole: String? = null,
    val canManageMembers: Boolean = false,
    val canManageOwners: Boolean = false,
    val members: List<OrganizationMemberOut> = emptyList(),
)

@Serializable
data class OrganizationInvitationOut(
    val id: String,
    val email: String? = null,
    val userId: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val role: String,
    val status: String,
    val createdAt: String,
    val expiresAt: String,
)

@Serializable
data class OrganizationInvitationsResponse(
    val organizationId: String,
    val invitations: List<OrganizationInvitationOut> = emptyList(),
)

@Serializable
data class CreateOrganizationInvitationBody(
    val identifier: String,
    val role: String = "member",
)

@Serializable
data class OrganizationInvitationResultOut(
    val id: String,
    val status: String,
)

@Serializable
data class SetOrganizationMemberRoleBody(
    val role: String,
)

@Serializable
data class AcceptOrganizationInvitationBody(
    val invitationId: String? = null,
    val token: String? = null,
)

@Serializable
data class DeclineOrganizationInvitationBody(
    val invitationId: String,
)

@Serializable
data class OrganizationMembershipResponse(
    val organization: OrganizationSummaryOut,
    val role: String,
)

@Serializable
data class OrganizationTeamsResponse(
    val organizationId: String,
    val viewerRole: String? = null,
    val canManageTeams: Boolean = false,
    val teams: List<TeamOut> = emptyList(),
    val manageableCalendars: List<CalendarOptionOut> = emptyList(),
)

@Serializable
data class TeamOut(
    val id: String,
    val organizationId: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    val isDefault: Boolean = false,
    val memberCount: Int = 0,
    val viewerRole: String? = null,
    val canManageMembers: Boolean = false,
    val canManageGrants: Boolean = false,
    val canDelete: Boolean = false,
    val members: List<TeamMemberOut> = emptyList(),
    val grants: List<TeamCalendarOut> = emptyList(),
)

@Serializable
data class TeamMemberOut(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val role: String,
    val isSelf: Boolean = false,
)

@Serializable
data class TeamCalendarOut(
    val calendarId: String,
    val displayName: String,
    val color: String,
    val permission: String,
)

@Serializable
data class CalendarOptionOut(
    val id: String,
    val displayName: String,
    val color: String,
)

@Serializable
data class CreateTeamBody(
    val slug: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class UpdateTeamBody(
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class AddTeamMemberBody(
    val userId: String,
)

@Serializable
data class SetTeamMemberRoleBody(
    val role: String,
)

@Serializable
data class GrantTeamCalendarBody(
    val permission: String,
)

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
    val events: List<Event>,
)

@Serializable
data class PublicAccessBody(
    val mode: String,
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
data class OAuthProvidersResponse(
    val providers: List<OAuthProviderOut>,
)

@Serializable
data class ConnectionOut(
    val id: String,
    val provider: String,
    val providerName: String,
    val accountEmail: String? = null,
    val displayName: String? = null,
    val status: String,
    val lastSyncAt: String? = null,
    val lastError: String? = null,
)

@Serializable
data class ConnectionsResponse(
    val connections: List<ConnectionOut>,
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
data class UpdateAdminSettingsBody(
    val registrationOpen: Boolean? = null,
    val oauthRegistrationOpen: Boolean? = null,
    val emailVerification: String? = null,
    val publicAccess: String? = null,
)
