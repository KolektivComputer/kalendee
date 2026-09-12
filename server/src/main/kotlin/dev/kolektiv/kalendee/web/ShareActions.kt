package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.friends.FriendshipService
import dev.kolektiv.kalendee.mail.MailService
import dev.kolektiv.kalendee.notifications.NotificationService
import dev.kolektiv.keel.KeelAction

class ShareActions(
    private val store: CalendarStore,
    private val auth: AuthService,
    private val settings: AuthSettings,
    private val notifications: NotificationService,
    private val mail: MailService,
    private val friendships: FriendshipService,
) {
    @KeelAction("kalendee.getCalendarSharing")
    suspend fun getCalendarSharing(input: GetCalendarSharingIn): CalendarSharingOut =
        mapDomainErrors("calendarId") {
            sharing(requireSessionUser(auth, settings), CalendarId.parse(input.calendarId))
        }

    @KeelAction("kalendee.shareCalendar")
    suspend fun shareCalendar(input: ShareCalendarIn): CalendarSharingOut =
        mapDomainErrors(inviteeField(input.username)) {
            share(
                requireSessionUser(auth, settings),
                CalendarId.parse(input.calendarId),
                input.username,
                input.permission,
            )
        }

    @KeelAction("kalendee.updateShare")
    suspend fun updateShare(input: UpdateShareIn): CalendarSharingOut = mapDomainErrors("permission") {
        setSharePermission(
            requireSessionUser(auth, settings),
            CalendarId.parse(input.calendarId),
            UserId.parse(input.userId),
            input.permission,
        )
    }

    @KeelAction("kalendee.removeShare")
    suspend fun removeShare(input: RemoveShareIn): CalendarSharingOut = mapDomainErrors("userId") {
        removeShare(
            requireSessionUser(auth, settings),
            CalendarId.parse(input.calendarId),
            UserId.parse(input.userId),
        )
    }

    @KeelAction("kalendee.setCalendarPublic")
    suspend fun setCalendarPublic(input: SetCalendarPublicIn): CalendarSharingOut =
        mapDomainErrors("calendarId") {
            setPublic(
                requireSessionUser(auth, settings),
                CalendarId.parse(input.calendarId),
                input.enabled,
            )
        }

    @KeelAction("kalendee.rotatePublicLink")
    suspend fun rotatePublicLink(input: RotatePublicLinkIn): CalendarSharingOut =
        mapDomainErrors("calendarId") {
            rotatePublic(requireSessionUser(auth, settings), CalendarId.parse(input.calendarId))
        }

    @KeelAction("kalendee.followCalendar")
    suspend fun followCalendar(input: FollowCalendarIn): FollowOut = mapDomainErrors("token") {
        follow(requireSessionUser(auth, settings), input.token)
    }

    @KeelAction("kalendee.unfollowCalendar")
    suspend fun unfollowCalendar(input: UnfollowCalendarIn): FollowOut = mapDomainErrors("calendarId") {
        unfollow(requireSessionUser(auth, settings), CalendarId.parse(input.calendarId))
    }

    suspend fun sharing(user: User, calendarId: CalendarId): CalendarSharingOut {
        val calendar = requireOwned(calendarId, user.id)
        return sharingOut(calendar, user.id)
    }

    suspend fun share(
        user: User,
        calendarId: CalendarId,
        inviteeIdentifier: String,
        permission: String,
    ): CalendarSharingOut {
        val calendar = requireOwned(calendarId, user.id)
        val parsed = CalendarPermission.parseShare(permission)
        val invitee = auth.userByIdentifier(inviteeIdentifier)
            ?: throw CalendarException.NotFound("no user matches that username or email")
        if (invitee.id == user.id) {
            throw CalendarException.Invalid("cannot share a calendar with its owner")
        }
        val alreadyShared = store.listShares(calendar.id, user.id).any { it.userId == invitee.id }
        if (alreadyShared) {
            throw CalendarException.Conflict("calendar is already shared with that user")
        }
        store.addShare(calendar.id, user.id, invitee.id, parsed)
        notifications.create(
            userId = invitee.id,
            kind = "calendar_shared",
            title = "${user.displayName} shared \"${calendar.displayName}\" with you",
            body = "You have ${parsed.wire} access.",
            href = "/?calendar=${calendar.id.value}",
        )
        invitee.email?.let { email ->
            mail.sendCalendarShared(
                to = email,
                inviterName = user.displayName,
                calendarName = calendar.displayName,
                link = "/?calendar=${calendar.id.value}",
            )
        }
        return sharingOut(calendar, user.id)
    }

    suspend fun setSharePermission(
        user: User,
        calendarId: CalendarId,
        inviteeId: UserId,
        permission: String,
    ): CalendarSharingOut {
        val calendar = requireOwned(calendarId, user.id)
        val parsed = CalendarPermission.parseShare(permission)
        store.updateShare(calendar.id, user.id, inviteeId, parsed)
            ?: throw CalendarException.NotFound("share not found")
        return sharingOut(calendar, user.id)
    }

    suspend fun removeShare(user: User, calendarId: CalendarId, inviteeId: UserId): CalendarSharingOut {
        val calendar = requireOwned(calendarId, user.id)
        if (!store.removeShare(calendar.id, user.id, inviteeId)) {
            throw CalendarException.NotFound("share not found")
        }
        return sharingOut(calendar, user.id)
    }

    suspend fun setPublic(user: User, calendarId: CalendarId, enabled: Boolean): CalendarSharingOut {
        val calendar = requireOwned(calendarId, user.id)
        val updated = store.setPublicLink(calendar.id, user.id, enabled)
            ?: throw CalendarException.NotFound("calendar not found")
        return sharingOut(updated, user.id)
    }

    suspend fun rotatePublic(user: User, calendarId: CalendarId): CalendarSharingOut {
        val calendar = requireOwned(calendarId, user.id)
        val updated = store.rotatePublicLink(calendar.id, user.id)
            ?: throw CalendarException.NotFound("calendar not found")
        return sharingOut(updated, user.id)
    }

    suspend fun follow(user: User, token: String): FollowOut {
        val trimmed = token.trim()
        val existing = store.publicCalendar(trimmed)
            ?: throw CalendarException.NotFound("calendar not found")
        val alreadyFollowing = store.getCalendar(existing.id, user.id)?.permission == CalendarPermission.FOLLOW
        val calendar = store.follow(trimmed, user.id)
            ?: throw CalendarException.NotFound("calendar not found")
        if (!alreadyFollowing) {
            val owner = auth.userById(calendar.ownerId)
            notifications.create(
                userId = calendar.ownerId,
                kind = "new_follower",
                title = "${user.displayName} followed \"${calendar.displayName}\"",
                body = "Someone is following your public calendar.",
                href = "/c/$trimmed",
            )
            owner?.email?.let { email ->
                mail.sendNewFollower(
                    to = email,
                    followerName = user.displayName,
                    calendarName = calendar.displayName,
                    link = "/c/$trimmed",
                )
            }
            notifications.create(
                userId = user.id,
                kind = "following",
                title = "You are following \"${calendar.displayName}\"",
                body = "Followed from a public link.",
                href = "/c/$trimmed",
            )
            user.email?.let { email ->
                mail.sendFollowing(
                    to = email,
                    calendarName = calendar.displayName,
                    ownerName = owner?.displayName ?: "its owner",
                    link = "/c/$trimmed",
                )
            }
        }
        return FollowOut(calendarId = calendar.id.value, following = true)
    }

    suspend fun unfollow(user: User, calendarId: CalendarId): FollowOut {
        store.unfollow(calendarId, user.id)
        return FollowOut(calendarId = calendarId.value, following = false)
    }

    private suspend fun requireOwned(calendarId: CalendarId, userId: UserId): Calendar {
        val calendar = store.getCalendar(calendarId, userId)
            ?: throw CalendarException.NotFound("calendar not found")
        if (calendar.permission != CalendarPermission.OWNER) {
            throw CalendarException.Forbidden("owner only")
        }
        return calendar
    }

    private suspend fun sharingOut(calendar: Calendar, userId: UserId): CalendarSharingOut = CalendarSharingOut(
        shares = store.listShares(calendar.id, userId).map { it.toSummary() },
        publicLinkEnabled = calendar.publicLinkEnabled,
        publicLinkToken = calendar.publicLinkToken,
        followerCount = store.countFollowers(calendar.id),
        friends = friendships.friends(userId).map { it.toFriendSummary() },
    )
}

private fun inviteeField(identifier: String): String =
    if (identifier.contains('@')) "email" else "username"
