package dev.kolektiv.kalendee.notify

import kotlinx.datetime.Instant

/** A reminder to schedule as an OS-level local notification. */
data class PendingReminder(val id: String, val title: String, val body: String, val at: Instant, val eventId: String?)

interface LocalNotificationScheduler {
    suspend fun requestPermission(): Boolean

    suspend fun reschedule(reminders: List<PendingReminder>)

    suspend fun cancelAll()
}

expect fun platformNotificationScheduler(): LocalNotificationScheduler
