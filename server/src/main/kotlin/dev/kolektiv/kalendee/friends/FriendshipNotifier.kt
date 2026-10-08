package dev.kolektiv.kalendee.friends

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.mail.MailService
import dev.kolektiv.kalendee.notifications.NotificationService

/**
 * Side effects for friendship changes, shared by the Keel actions and the JSON
 * API routes. Callers are responsible for the friendship state change itself.
 */
class FriendshipNotifier(
    private val auth: AuthService,
    private val notifications: NotificationService,
    private val mail: MailService,
) {
    suspend fun notifyRequested(sender: User, target: FriendSummary) {
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

    suspend fun notifyAccepted(accepter: User, requester: FriendSummary) {
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
