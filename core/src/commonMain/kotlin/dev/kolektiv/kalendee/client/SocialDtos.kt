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
