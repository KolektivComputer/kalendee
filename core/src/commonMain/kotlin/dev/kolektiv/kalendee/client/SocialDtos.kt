package dev.kolektiv.kalendee.client

import kotlinx.serialization.Serializable

@Serializable
data class OrganizationsResponse(
    val organizations: List<OrganizationSummaryOut> = emptyList(),
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
    val friends: List<FriendSummaryOut> = emptyList(),
    val incoming: List<FriendRequestSummaryOut> = emptyList(),
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
data class FriendRequestOut(
    val status: String,
    val friend: FriendSummaryOut? = null,
)

@Serializable
data class UserSearchResponse(
    val results: List<UserSearchResultOut> = emptyList(),
)

@Serializable
data class UserSearchResultOut(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val relationship: String = "none",
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
data class OrganizationMemberOut(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val role: String = "member",
    val isSelf: Boolean = false,
)

@Serializable
data class OrganizationInvitationsResponse(
    val organizationId: String,
    val invitations: List<OrganizationInvitationOut> = emptyList(),
)

@Serializable
data class OrganizationInvitationOut(
    val id: String,
    val email: String? = null,
    val userId: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val role: String = "member",
    val status: String = "pending",
    val createdAt: String = "",
    val expiresAt: String? = null,
)

@Serializable
data class InvitationAcceptedResponse(
    val organization: OrganizationSummaryOut,
    val role: String = "member",
)

@Serializable
data class OrganizationTeamsResponse(
    val organizationId: String,
    val viewerRole: String? = null,
    val canManageTeams: Boolean = false,
    val teams: List<OrganizationTeamOut> = emptyList(),
    val manageableCalendars: List<CalendarOptionOut> = emptyList(),
)

@Serializable
data class OrganizationTeamOut(
    val id: String,
    val organizationId: String = "",
    val slug: String = "",
    val name: String = "",
    val description: String? = null,
    val isDefault: Boolean = false,
    val memberCount: Int = 0,
    val viewerRole: String? = null,
    val canManageMembers: Boolean = false,
    val canManageGrants: Boolean = false,
    val canDelete: Boolean = false,
    val members: List<OrganizationTeamMemberOut> = emptyList(),
    val grants: List<OrganizationTeamGrantOut> = emptyList(),
)

@Serializable
data class OrganizationTeamMemberOut(
    val userId: String,
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val role: String = "member",
    val isSelf: Boolean = false,
)

@Serializable
data class OrganizationTeamGrantOut(
    val calendarId: String,
    val displayName: String = "",
    val color: String = "",
    val permission: String = "read",
)

@Serializable
data class CalendarOptionOut(
    val id: String,
    val displayName: String = "",
    val color: String = "",
)

@Serializable
data class SendFriendRequestBody(
    val username: String,
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
data class InviteToOrganizationBody(
    val identifier: String,
    val role: String = "member",
)

@Serializable
data class CreateOrganizationTeamBody(
    val slug: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class UpdateOrganizationTeamBody(
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class TeamMemberBody(
    val userId: String,
)

@Serializable
data class TeamRoleBody(
    val role: String,
)

@Serializable
data class TeamGrantBody(
    val permission: String,
)

@Serializable
data class AcceptInvitationBody(
    val invitationId: String? = null,
    val token: String? = null,
)

@Serializable
data class DeclineInvitationBody(
    val invitationId: String,
)
