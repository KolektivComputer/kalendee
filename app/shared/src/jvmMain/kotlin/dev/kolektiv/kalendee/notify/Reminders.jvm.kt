package dev.kolektiv.kalendee.notify

import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max

actual class ReminderScheduler actual constructor() {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "kalendee-reminders").apply { isDaemon = true }
    }
    private val jobs = ConcurrentHashMap<String, ScheduledFuture<*>>()

    actual fun schedule(reminder: Reminder) {
        cancel(reminder.eventId)
        val delay = max(0L, reminder.fireAtEpochMs - System.currentTimeMillis())
        jobs[reminder.eventId] = executor.schedule({ notify(reminder.title) }, delay, TimeUnit.MILLISECONDS)
    }

    actual fun cancel(eventId: String) {
        jobs.remove(eventId)?.cancel(false)
    }

    private fun notify(title: String) {
        if (!SystemTray.isSupported()) return
        val tray = SystemTray.getSystemTray()
        val icon = TrayIcon(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "Kalendee")
        icon.isImageAutoSize = true
        runCatching { tray.add(icon) }
        icon.displayMessage("Kalendee", title, TrayIcon.MessageType.INFO)
        tray.remove(icon)
    }
}
