package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.notifications.NotificationService
import dev.kolektiv.keel.KeelAction

class NotificationActions(
    private val notifications: NotificationService,
    private val auth: AuthService,
    private val settings: AuthSettings,
) {
    @KeelAction("kalendee.markNotificationRead")
    suspend fun markNotificationRead(input: MarkNotificationReadIn): NotificationStateOut =
        mapDomainErrors("id") {
            val user = requireSessionUser(auth, settings)
            notifications.markRead(user.id, input.id)
            NotificationStateOut(unreadCount = notifications.unreadCount(user.id))
        }

    @KeelAction("kalendee.markAllNotificationsRead")
    suspend fun markAllNotificationsRead(input: MarkAllNotificationsReadIn): NotificationStateOut =
        mapDomainErrors("id") {
            val user = requireSessionUser(auth, settings)
            notifications.markAllRead(user.id)
            NotificationStateOut(unreadCount = notifications.unreadCount(user.id))
        }
}
