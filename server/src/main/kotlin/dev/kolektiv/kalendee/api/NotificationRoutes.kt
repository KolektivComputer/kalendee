package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.notifications.Notification
import dev.kolektiv.kalendee.notifications.NotificationService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.notificationRoutes(notifications: NotificationService) {
    get("/notifications") {
        call.respond(notifications.list(call.user().id).map { it.toOut() })
    }
    post("/notifications/{id}/read") {
        val user = call.user()
        notifications.markRead(user.id, call.parameters["id"].orEmpty())
        call.respond(NotificationStateResponse(unreadCount = notifications.unreadCount(user.id)))
    }
    post("/notifications/read-all") {
        val user = call.user()
        notifications.markAllRead(user.id)
        call.respond(NotificationStateResponse(unreadCount = notifications.unreadCount(user.id)))
    }
}

private fun Notification.toOut(): NotificationOut = NotificationOut(
    id = id.toString(),
    kind = kind,
    title = title,
    body = body,
    href = href,
    read = readAt != null,
    createdAt = createdAt.toString(),
)
