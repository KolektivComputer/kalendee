package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.reminders.EventReminders
import dev.kolektiv.kalendee.reminders.ReminderInstance
import dev.kolektiv.kalendee.reminders.ReminderService
import dev.kolektiv.kalendee.reminders.ReminderSettings
import dev.kolektiv.keel.KeelAction

class ReminderActions(
    private val reminders: ReminderService,
    private val auth: AuthService,
    private val settings: AuthSettings,
) {
    @KeelAction("kalendee.reminderSettings")
    suspend fun reminderSettings(input: ReminderSettingsIn): ReminderSettingsOut =
        mapDomainErrors("offsets") {
            val user = requireSessionUser(auth, settings)
            reminders.settings(user.id).toOut()
        }

    @KeelAction("kalendee.updateReminderSettings")
    suspend fun updateReminderSettings(input: UpdateReminderSettingsIn): ReminderSettingsOut =
        mapDomainErrors("offsets") {
            val user = requireSessionUser(auth, settings)
            reminders.updateSettings(user.id, input.defaultOffsetsSeconds, input.notifyAtStart).toOut()
        }

    @KeelAction("kalendee.eventReminders")
    suspend fun eventReminders(input: GetEventRemindersIn): EventRemindersOut =
        mapDomainErrors("offsets") {
            val user = requireSessionUser(auth, settings)
            reminders.eventReminders(user.id, EventId.parse(input.eventId)).toOut()
        }

    @KeelAction("kalendee.setEventReminders")
    suspend fun setEventReminders(input: SetEventRemindersIn): EventRemindersOut =
        mapDomainErrors("offsets") {
            val user = requireSessionUser(auth, settings)
            reminders.setEventReminders(
                user.id,
                EventId.parse(input.eventId),
                input.offsetsSeconds,
                input.useDefaults,
            ).toOut()
        }

    @KeelAction("kalendee.upcomingReminders")
    suspend fun upcomingReminders(input: UpcomingRemindersIn): List<ReminderInstanceOut> =
        mapDomainErrors("hours") {
            val user = requireSessionUser(auth, settings)
            reminders.upcoming(user.id, input.hours).map { it.toOut() }
        }
}

private fun ReminderSettings.toOut(): ReminderSettingsOut = ReminderSettingsOut(
    defaultOffsetsSeconds = defaultOffsetsSeconds,
    notifyAtStart = notifyAtStart,
)

private fun EventReminders.toOut(): EventRemindersOut = EventRemindersOut(
    eventId = eventId,
    offsetsSeconds = offsetsSeconds,
    useDefaults = useDefaults,
    defaultOffsetsSeconds = defaultOffsetsSeconds,
    notifyAtStart = notifyAtStart,
)

private fun ReminderInstance.toOut(): ReminderInstanceOut = ReminderInstanceOut(
    eventId = eventId,
    calendarId = calendarId,
    calendarName = calendarName,
    calendarColor = calendarColor,
    title = title,
    start = start.toString(),
    allDay = allDay,
    offsetSeconds = offsetSeconds,
    remindAt = remindAt.toString(),
)
