package dev.kolektiv.kalendee.notify

data class Reminder(
    val eventId: String,
    val title: String,
    val fireAtEpochMs: Long,
)

expect class ReminderScheduler() {
    fun schedule(reminder: Reminder)
    fun cancel(eventId: String)
}
