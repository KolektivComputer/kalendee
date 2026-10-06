package dev.kolektiv.kalendee.notify

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.kolektiv.kalendee.app.shared.R
import dev.kolektiv.kalendee.platform.KalendeeAndroid
import dev.kolektiv.kalendee.platform.SHARED_PREFS_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Alarm action for [ReminderAlarmReceiver]; only identity of the intent matters for cancelation. */
const val ACTION_SHOW_REMINDER = "dev.kolektiv.kalendee.notify.action.SHOW_REMINDER"

const val EXTRA_REMINDER_ID = "dev.kolektiv.kalendee.notify.extra.REMINDER_ID"
const val EXTRA_REMINDER_TITLE = "dev.kolektiv.kalendee.notify.extra.REMINDER_TITLE"
const val EXTRA_REMINDER_BODY = "dev.kolektiv.kalendee.notify.extra.REMINDER_BODY"
const val EXTRA_REMINDER_EVENT_ID = "dev.kolektiv.kalendee.notify.extra.REMINDER_EVENT_ID"

/** Notification channel used for all calendar reminders. */
const val NOTIFICATION_CHANNEL_REMINDERS = "reminders"

private const val KEY_SCHEDULED_REMINDERS = "notifications.scheduled_reminders"

actual fun platformNotificationScheduler(): LocalNotificationScheduler =
    AndroidLocalNotificationScheduler(KalendeeAndroid.appContext)

/**
 * [AlarmManager]-backed scheduler. All work is synchronous (SharedPreferences +
 * AlarmManager) and is executed on [Dispatchers.IO] so callers may invoke it
 * from any coroutine context.
 */
class AndroidLocalNotificationScheduler(private val context: Context) : LocalNotificationScheduler {

    override suspend fun requestPermission(): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    override suspend fun reschedule(reminders: List<PendingReminder>) {
        withContext(Dispatchers.IO) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            readStoredReminders(context).forEach { cancelAlarm(alarmManager, it) }

            val now = System.currentTimeMillis()
            val scheduled = reminders.filter { it.at.toEpochMilliseconds() > now }
            scheduled.forEach { scheduleAlarm(alarmManager, it) }
            storeReminders(context, scheduled)
        }
    }

    override suspend fun cancelAll() {
        withContext(Dispatchers.IO) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            readStoredReminders(context).forEach { cancelAlarm(alarmManager, it) }
            storeReminders(context, emptyList())
        }
    }

    private fun scheduleAlarm(alarmManager: AlarmManager, reminder: PendingReminder) {
        val pendingIntent = alarmPendingIntent(reminder, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val triggerAtMillis = reminder.at.toEpochMilliseconds()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun cancelAlarm(alarmManager: AlarmManager, reminder: PendingReminder) {
        val pendingIntent = alarmPendingIntent(reminder, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun alarmPendingIntent(reminder: PendingReminder, flags: Int): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ACTION_SHOW_REMINDER)
            .putExtra(EXTRA_REMINDER_ID, reminder.id)
            .putExtra(EXTRA_REMINDER_TITLE, reminder.title)
            .putExtra(EXTRA_REMINDER_BODY, reminder.body)
            .putExtra(EXTRA_REMINDER_EVENT_ID, reminder.eventId)
        return PendingIntent.getBroadcast(
            context,
            reminderIdHash(reminder.id),
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Stable request/notification id for a reminder. */
internal fun reminderIdHash(reminderId: String): Int = reminderId.hashCode()

/** Reads reminders persisted by [AndroidLocalNotificationScheduler]; used to re-arm after reboot. */
internal fun readStoredReminders(context: Context): List<PendingReminder> {
    val raw = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_SCHEDULED_REMINDERS, null)
        ?: return emptyList()
    return runCatching { decodeReminders(raw) }.getOrDefault(emptyList())
}

private fun storeReminders(context: Context, reminders: List<PendingReminder>) {
    context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_SCHEDULED_REMINDERS, encodeReminders(reminders))
        .commit()
}

private fun encodeReminders(reminders: List<PendingReminder>): String = buildJsonArray {
    reminders.forEach { reminder ->
        add(
            buildJsonObject {
                put("id", reminder.id)
                put("title", reminder.title)
                put("body", reminder.body)
                put("at", reminder.at.toEpochMilliseconds())
                put("eventId", reminder.eventId)
            }
        )
    }
}.toString()

private fun decodeReminders(raw: String): List<PendingReminder> =
    Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
        val json = element.jsonObject
        val id = json["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val atMillis = json["at"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        PendingReminder(
            id = id,
            title = json["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            body = json["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            at = Instant.fromEpochMilliseconds(atMillis),
            eventId = json["eventId"]?.jsonPrimitive?.contentOrNull,
        )
    }

/** Posts the reminder notification and creates the channel on first use. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOW_REMINDER) return
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val title = intent.getStringExtra(EXTRA_REMINDER_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_REMINDER_BODY).orEmpty()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        ensureRemindersChannel(context, notificationManager)
        notificationManager.notify(reminderIdHash(reminderId), buildReminderNotification(context, title, body))
    }
}

private fun ensureRemindersChannel(context: Context, notificationManager: NotificationManager) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_REMINDERS) != null) return
    val channel = NotificationChannel(
        NOTIFICATION_CHANNEL_REMINDERS,
        context.getString(R.string.notification_channel_reminders),
        NotificationManager.IMPORTANCE_HIGH,
    )
    notificationManager.createNotificationChannel(channel)
}

@Suppress("DEPRECATION")
private fun newReminderNotificationBuilder(context: Context): Notification.Builder =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, NOTIFICATION_CHANNEL_REMINDERS)
    } else {
        Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
    }

private fun buildReminderNotification(context: Context, title: String, body: String): Notification =
    newReminderNotificationBuilder(context)
        .setSmallIcon(R.drawable.ic_stat_reminder)
        .setContentTitle(title)
        .setContentText(body)
        .setAutoCancel(true)
        .setCategory(Notification.CATEGORY_REMINDER)
        .build()
