package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.friends.FriendRequestSummary as DomainFriendRequest
import dev.kolektiv.kalendee.friends.FriendSummary as DomainFriend
import dev.kolektiv.kalendee.friends.FriendshipAccepted
import dev.kolektiv.kalendee.friends.FriendshipNotifier
import dev.kolektiv.kalendee.friends.FriendshipService
import dev.kolektiv.kalendee.friends.UserSearchResult as DomainUserSearchResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.friendRoutes(
    friendships: FriendshipService,
    notifier: FriendshipNotifier,
) {
    get("/friends") {
        val user = call.user()
        call.respond(friendsResponse(friendships, user.id))
    }
    post("/friends/requests") {
        val user = call.user()
        val body = call.receive<SendFriendRequestBody>()
        val result = call.respondFriendErrors {
            friendships.sendRequest(user.id, body.username)
        } ?: return@post
        if (result.status == FriendshipAccepted) {
            notifier.notifyAccepted(user, result.friend)
        } else {
            notifier.notifyRequested(user, result.friend)
        }
        call.respond(
            HttpStatusCode.Created,
            FriendRequestResultOut(status = result.status, friend = result.friend.toOut()),
        )
    }
    post("/friends/requests/{id}/accept") {
        val user = call.user()
        val id = call.friendRequestId()
        val request = call.respondFriendErrors {
            friendships.accept(user.id, id) ?: throw CalendarException.NotFound("request not found")
        } ?: return@post
        notifier.notifyAccepted(user, request.user)
        call.respond(friendsResponse(friendships, user.id))
    }
    post("/friends/requests/{id}/decline") {
        val user = call.user()
        val id = call.friendRequestId()
        call.respondFriendErrors { friendships.decline(user.id, id) } ?: return@post
        call.respond(friendsResponse(friendships, user.id))
    }
    delete("/friends/{userId}") {
        val user = call.user()
        val otherId = call.respondFriendErrors { call.userId() } ?: return@delete
        call.respondFriendErrors { friendships.remove(user.id, otherId) } ?: return@delete
        call.respond(friendsResponse(friendships, user.id))
    }
    get("/users/search") {
        val user = call.user()
        val query = call.request.queryParameters["q"].orEmpty()
        val limit = call.request.queryParameters["limit"]
            ?.toIntOrNull()
            ?.coerceIn(1, 50)
            ?: 10
        call.respond(
            UserSearchResponse(
                results = friendships.searchUsers(user.id, query, limit).map { it.toOut() },
            ),
        )
    }
}

private suspend fun friendsResponse(friendships: FriendshipService, userId: UserId): FriendsResponse =
    FriendsResponse(
        friends = friendships.friends(userId).map { it.toOut() },
        incoming = friendships.incomingRequests(userId).map { it.toOut() },
    )

private fun ApplicationCall.friendRequestId(): String =
    parameters["id"] ?: throw CalendarException.Invalid("missing request id")

private suspend fun <T> ApplicationCall.respondFriendErrors(block: suspend () -> T): T? = try {
    block()
} catch (cause: CalendarException.Invalid) {
    friendError("invalid", cause)
} catch (cause: CalendarException.NotFound) {
    friendError("not_found", cause)
} catch (cause: CalendarException.Conflict) {
    friendError("conflict", cause)
} catch (cause: CalendarException.Forbidden) {
    friendError("forbidden", cause)
}

private suspend fun ApplicationCall.friendError(error: String, cause: CalendarException): Nothing? {
    respond(
        HttpStatusCode.UnprocessableEntity,
        ErrorBody(error = error, message = cause.message ?: error),
    )
    return null
}

private fun DomainFriend.toOut(): FriendSummaryOut = FriendSummaryOut(
    userId = userId,
    username = username,
    displayName = displayName,
    avatarUrl = avatarVersion?.let { "/api/v1/users/$userId/avatar?v=$it" },
)

private fun DomainFriendRequest.toOut(): FriendRequestSummaryOut = FriendRequestSummaryOut(
    id = id,
    user = user.toOut(),
    createdAt = createdAt.toString(),
)

private fun DomainUserSearchResult.toOut(): UserSearchResultOut = UserSearchResultOut(
    userId = userId,
    username = username,
    displayName = displayName,
    avatarUrl = avatarVersion?.let { "/api/v1/users/$userId/avatar?v=$it" },
    relationship = relationship,
)
