package dev.kolektiv.kalendee

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kolektiv.kalendee.notify.AndroidReminders
import dev.kolektiv.kalendee.notify.Reminder

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        AndroidReminders.notifyNow(context, Reminder(eventId = eventId, title = title, fireAtEpochMs = 0L))
    }

    companion object {
        const val ACTION = "computer.kolektiv.kalendee.REMIND"
        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_TITLE = "title"
    }
}
