package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.friends.FriendRequestSummary as DomainFriendRequest
import dev.kolektiv.kalendee.friends.FriendSummary as DomainFriend
import dev.kolektiv.kalendee.friends.FriendshipAccepted
import dev.kolektiv.kalendee.friends.FriendshipService
import dev.kolektiv.kalendee.friends.UserSearchResult as DomainUserSearchResult
import dev.kolektiv.kalendee.mail.MailService
import dev.kolektiv.kalendee.notifications.NotificationService
import dev.kolektiv.keel.KeelAction

class FriendshipActions(
    private val friendships: FriendshipService,
    private val auth: AuthService,
    private val settings: AuthSettings,
    private val notifications: NotificationService,
    private val mail: MailService,
) {
    @KeelAction("kalendee.friends")
    suspend fun friends(input: FriendsIn): FriendsOut {
        val user = requireSessionUser(auth, settings)
        return friendsOut(user.id)
    }

    @KeelAction("kalendee.sendFriendRequest")
    suspend fun sendFriendRequest(input: SendFriendRequestIn): FriendRequestOut =
        mapDomainErrors("username") {
            val user = requireSessionUser(auth, settings)
            val result = friendships.sendRequest(user.id, input.username)
            if (result.status == FriendshipAccepted) {
                notifyAccepted(user, result.friend)
            } else {
                notifyRequested(user, result.friend)
            }
            FriendRequestOut(status = result.status, friend = result.friend.toFriendSummary())
        }

    @KeelAction("kalendee.acceptFriendRequest")
    suspend fun acceptFriendRequest(input: RespondFriendRequestIn): FriendsOut =
        mapDomainErrors("id") {
            val user = requireSessionUser(auth, settings)
            val request = friendships.accept(user.id, input.id)
                ?: throw CalendarException.NotFound("request not found")
            notifyAccepted(user, request.user)
            friendsOut(user.id)
        }

    @KeelAction("kalendee.declineFriendRequest")
    suspend fun declineFriendRequest(input: RespondFriendRequestIn): FriendsOut =
        mapDomainErrors("id") {
            val user = requireSessionUser(auth, settings)
            friendships.decline(user.id, input.id)
            friendsOut(user.id)
        }

    @KeelAction("kalendee.removeFriend")
    suspend fun removeFriend(input: RemoveFriendIn): FriendsOut = mapDomainErrors("userId") {
        val user = requireSessionUser(auth, settings)
        friendships.remove(user.id, UserId.parse(input.userId))
        friendsOut(user.id)
    }

    @KeelAction("kalendee.searchUsers")
    suspend fun searchUsers(input: SearchUsersIn): UserSearchOut = mapDomainErrors("query") {
        val user = requireSessionUser(auth, settings)
        UserSearchOut(
            results = friendships.searchUsers(user.id, input.query).map { it.toUserSearchResult() },
        )
    }

    private suspend fun friendsOut(userId: UserId): FriendsOut = FriendsOut(
        friends = friendships.friends(userId).map { it.toFriendSummary() },
        incoming = friendships.incomingRequests(userId).map { it.toFriendRequestSummary() },
    )

    private suspend fun notifyRequested(sender: User, target: DomainFriend) {
        val targetId = UserId.parse(target.userId)
        notifications.create(
            userId = targetId,
            kind = "friend.request",
            title = "${sender.displayName} wants to be friends",
            href = "/",
        )
        auth.userById(targetId)?.email?.let { email ->
            mail.sendFriendRequest(to = email, requesterName = sender.displayName)
        }
    }

    private suspend fun notifyAccepted(accepter: User, requester: DomainFriend) {
        val requesterId = UserId.parse(requester.userId)
        notifications.create(
            userId = requesterId,
            kind = "friend.accepted",
            title = "${accepter.displayName} accepted your friend request",
            href = "/",
        )
        auth.userById(requesterId)?.email?.let { email ->
            mail.sendFriendAccepted(to = email, friendName = accepter.displayName)
        }
    }
}

internal fun DomainFriend.toFriendSummary(): FriendSummary = FriendSummary(
    userId = userId,
    username = username,
    displayName = displayName,
    avatarUrl = avatarVersion?.let { avatarUrl(UserId.parse(userId), it) },
)

internal fun DomainFriendRequest.toFriendRequestSummary(): FriendRequestSummary = FriendRequestSummary(
    id = id,
    user = user.toFriendSummary(),
    createdAt = createdAt.toString(),
)

internal fun DomainUserSearchResult.toUserSearchResult(): UserSearchResult = UserSearchResult(
    userId = userId,
    username = username,
    displayName = displayName,
    avatarUrl = avatarVersion?.let { avatarUrl(UserId.parse(userId), it) },
    relationship = relationship,
)
