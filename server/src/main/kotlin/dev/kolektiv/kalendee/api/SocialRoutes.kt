package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.friends.FriendRequestSummary
import dev.kolektiv.kalendee.friends.FriendSummary
import dev.kolektiv.kalendee.friends.FriendshipService
import dev.kolektiv.kalendee.organizations.OrganizationService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.socialRoutes(
    organizations: OrganizationService,
    friendships: FriendshipService,
) {
    get("/organizations") {
        val user = call.user()
        val memberships = organizations.listFor(user.id)
        call.respond(
            OrganizationsResponse(
                organizations = memberships.map { membership ->
                    val organization = membership.organization
                    OrganizationSummaryOut(
                        id = organization.id.value,
                        slug = organization.slug,
                        displayName = organization.displayName,
                        description = organization.description,
                        visibility = organization.visibility.wire,
                        avatarUrl = null,
                        role = membership.role.wire,
                        memberCount = organizations.members(user.id, organization.id).size,
                    )
                },
            ),
        )
    }
    get("/friends") {
        val user = call.user()
        call.respond(
            FriendsResponse(
                friends = friendships.friends(user.id).map { it.toOut() },
                incoming = friendships.incomingRequests(user.id).map { it.toOut() },
            ),
        )
    }
}

private fun FriendSummary.toOut(): FriendSummaryOut = FriendSummaryOut(
    userId = userId,
    username = username,
    displayName = displayName,
    avatarUrl = avatarVersion?.let { "/api/v1/users/$userId/avatar?v=$it" },
)

private fun FriendRequestSummary.toOut(): FriendRequestSummaryOut = FriendRequestSummaryOut(
    id = id,
    user = user.toOut(),
    createdAt = createdAt.toString(),
)
