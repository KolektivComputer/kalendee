package dev.kolektiv.kalendee.notify

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

actual class ReminderScheduler actual constructor() {
    actual fun schedule(reminder: Reminder) {
        AndroidReminders.schedule(reminder)
    }

    actual fun cancel(eventId: String) {
        AndroidReminders.cancel(eventId)
    }
}

object AndroidReminders {
    const val ChannelId = "kalendee.events"
    const val ChannelName = "Event reminders"

    @Volatile
    var appContext: Context? = null

    private val handler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, Runnable>()

    fun schedule(reminder: Reminder) {
        cancel(reminder.eventId)
        val context = appContext ?: return
        val delay = reminder.fireAtEpochMs - System.currentTimeMillis()
        if (delay <= 2_000L) {
            notifyNow(context, reminder)
            return
        }
        if (!scheduleAlarm(context, reminder)) {
            val runnable = Runnable { notifyNow(context, reminder) }
            pending[reminder.eventId] = runnable
            handler.postDelayed(runnable, delay)
        }
    }

    fun cancel(eventId: String) {
        pending.remove(eventId)?.let { handler.removeCallbacks(it) }
        val context = appContext ?: return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.cancel(pendingIntent(context, eventId, ""))
    }

    fun notifyNow(context: Context, reminder: Reminder) {
        pending.remove(reminder.eventId)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(reminder.title)
            .setContentText("Starting soon")
            .setAutoCancel(true)
            .build()
        manager.notify(reminder.eventId.hashCode(), notification)
    }

    private fun scheduleAlarm(context: Context, reminder: Reminder): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val pending = pendingIntent(context, reminder.eventId, reminder.title)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.fireAtEpochMs, pending)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.fireAtEpochMs, pending)
            } else {
                @Suppress("DEPRECATION")
                manager.setExact(AlarmManager.RTC_WAKEUP, reminder.fireAtEpochMs, pending)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun pendingIntent(context: Context, eventId: String, title: String): PendingIntent {
        val intent = Intent(REMIND_ACTION).apply {
            setClassName(context.packageName, "dev.kolektiv.kalendee.ReminderReceiver")
            putExtra("eventId", eventId)
            putExtra("title", title)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, eventId.hashCode(), intent, flags)
    }

    private const val REMIND_ACTION = "computer.kolektiv.kalendee.REMIND"
}
