package dev.kolektiv.kalendee.web

import dev.kolektiv.keel.KeelEvent
import dev.kolektiv.keel.event.EventDirection
import dev.kolektiv.keel.ktor.realtime.KeelRealtime
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.kolektiv.kalendee.web.Realtime")

/**
 * Channel scheme (host-owned strings).
 * - `user:<userId>` — per-user (notifications, reminders).
 * - `page:kalendee.home` — the calendar home page, auto-subscribed from the
 *   seed's page id.
 * - `calendar:<calendarId>` — a specific calendar (shared/org viewers).
 */
const val HOME_CHANNEL: String = "page:kalendee.home"

fun userChannel(userId: String): String = "user:$userId"

fun calendarChannel(calendarId: String): String = "calendar:$calendarId"

@Serializable
@KeelEvent("kalendee.notification", EventDirection.OUT)
data class NotificationEvent(val unreadCount: Int)

@Serializable
@KeelEvent("kalendee.eventChanged", EventDirection.OUT)
data class EventChangedEvent(val calendarId: String?)

@Serializable
@KeelEvent("kalendee.remindersChanged", EventDirection.OUT)
class RemindersChangedEvent

/**
 * A process-wide handle to the configured [KeelRealtime] bus. Populated in
 * [dev.kolektiv.kalendee.plugins.configureKeel] after `keel { }` installs the
 * realtime endpoints, so services (e.g. [dev.kolektiv.kalendee.notifications.NotificationService])
 * that are constructed before the Ktor plugins run can publish lazily.
 */
object RealtimeBus {
    @Volatile
    var realtime: KeelRealtime? = null

    /**
     * Best-effort publish that never throws. A missing or not-configured
     * realtime bus must not fail the request, so every failure is logged and
     * swallowed.
     */
    fun publish(channel: String, event: String, data: Any? = null) {
        val bus = realtime ?: return
        try {
            bus.publish(channel, event, data)
        } catch (failure: Throwable) {
            log.warn("realtime publish failed for '$event' on '$channel'", failure)
        }
    }
}

fun publishEventChanged(calendarId: String?) {
    val event = EventChangedEvent(calendarId)
    RealtimeBus.publish(HOME_CHANNEL, "kalendee.eventChanged", event)
    if (calendarId != null) {
        RealtimeBus.publish(calendarChannel(calendarId), "kalendee.eventChanged", event)
    }
}

fun publishNotificationCount(userId: String, unreadCount: Int) {
    RealtimeBus.publish(userChannel(userId), "kalendee.notification", NotificationEvent(unreadCount))
}

fun publishRemindersChanged(userId: String) {
    RealtimeBus.publish(userChannel(userId), "kalendee.remindersChanged", RemindersChangedEvent())
}
