package dev.kolektiv.kalendee.notify

/** Desktop has no OS notification integration yet; scheduling is a no-op. */
private object NoOpNotificationScheduler : LocalNotificationScheduler {
    override suspend fun requestPermission(): Boolean = false

    override suspend fun reschedule(reminders: List<PendingReminder>) = Unit

    override suspend fun cancelAll() = Unit
}

actual fun platformNotificationScheduler(): LocalNotificationScheduler = NoOpNotificationScheduler
