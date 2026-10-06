package dev.kolektiv.kalendee.notify

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume
import kotlin.time.Clock

private const val MAX_PENDING_NOTIFICATIONS = 60

private class IosLocalNotificationScheduler : LocalNotificationScheduler {

    private val center = UNUserNotificationCenter.currentNotificationCenter()

    override suspend fun requestPermission(): Boolean {
        val status = suspendCancellableCoroutine<Long> { continuation ->
            center.getNotificationSettingsWithCompletionHandler { settings ->
                continuation.resume(settings?.authorizationStatus ?: UNAuthorizationStatusNotDetermined)
            }
        }
        if (status == UNAuthorizationStatusAuthorized ||
            status == UNAuthorizationStatusProvisional ||
            status == UNAuthorizationStatusEphemeral
        ) {
            return true
        }
        return suspendCancellableCoroutine { continuation ->
            center.requestAuthorizationWithOptions(
                options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
            ) { granted, _ ->
                continuation.resume(granted)
            }
        }
    }

    override suspend fun reschedule(reminders: List<PendingReminder>) {
        center.removeAllPendingNotificationRequests()
        val now = Clock.System.now()
        reminders
            .filter { it.at > now }
            .sortedBy { it.at }
            .take(MAX_PENDING_NOTIFICATIONS)
            .forEach { reminder ->
                val content = UNMutableNotificationContent().apply {
                    setTitle(reminder.title)
                    setBody(reminder.body)
                    setSound(UNNotificationSound.defaultSound)
                    val userInfo = mutableMapOf<Any?, Any>("id" to reminder.id)
                    reminder.eventId?.let { userInfo["eventId"] = it }
                    setUserInfo(userInfo)
                }
                val delaySeconds = (reminder.at - now).inWholeSeconds.coerceAtLeast(1L)
                val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
                    timeInterval = delaySeconds.toDouble(),
                    repeats = false,
                )
                val request = UNNotificationRequest.requestWithIdentifier(
                    identifier = reminder.id,
                    content = content,
                    trigger = trigger,
                )
                center.addNotificationRequest(request) { }
            }
    }

    override suspend fun cancelAll() {
        center.removeAllPendingNotificationRequests()
    }
}

actual fun platformNotificationScheduler(): LocalNotificationScheduler = IosLocalNotificationScheduler()
