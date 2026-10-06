package dev.kolektiv.kalendee.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms reminder alarms after a reboot or app update; the OS drops all
 * [android.app.AlarmManager] alarms in both cases.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }

        val stored = readStoredReminders(context)
        if (stored.isEmpty()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                AndroidLocalNotificationScheduler(context.applicationContext).reschedule(stored)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
