package dev.kolektiv.kalendee.notify

actual class ReminderScheduler actual constructor() {
    actual fun schedule(reminder: Reminder) = Unit
    actual fun cancel(eventId: String) = Unit
}
