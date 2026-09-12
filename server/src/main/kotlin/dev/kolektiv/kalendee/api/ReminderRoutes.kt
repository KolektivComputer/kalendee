package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.reminders.EventReminders
import dev.kolektiv.kalendee.reminders.ReminderInstance
import dev.kolektiv.kalendee.reminders.ReminderService
import dev.kolektiv.kalendee.reminders.ReminderSettings
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

fun Route.reminderRoutes(reminders: ReminderService) {
    get("/reminders/settings") {
        call.respond(reminders.settings(call.user().id).toOut())
    }
    put("/reminders/settings") {
        val body = call.receive<ReminderSettingsBody>()
        call.respond(
            reminders.updateSettings(call.user().id, body.defaultOffsetsSeconds, body.notifyAtStart).toOut(),
        )
    }
    get("/events/{id}/reminders") {
        call.respond(reminders.eventReminders(call.user().id, call.eventId()).toOut())
    }
    put("/events/{id}/reminders") {
        val body = call.receive<SetEventRemindersBody>()
        call.respond(
            reminders.setEventReminders(
                call.user().id,
                call.eventId(),
                body.offsetsSeconds,
                body.useDefaults,
            ).toOut(),
        )
    }
    get("/reminders/upcoming") {
        val raw = call.request.queryParameters["hours"]
        val hours = when (raw) {
            null -> DefaultUpcomingHours
            else -> raw.toIntOrNull() ?: throw CalendarException.Invalid("hours must be a number")
        }
        call.respond(reminders.upcoming(call.user().id, hours).map { it.toOut() })
    }
}

private const val DefaultUpcomingHours = 48

private fun ReminderSettings.toOut(): ReminderSettingsResponse = ReminderSettingsResponse(
    defaultOffsetsSeconds = defaultOffsetsSeconds,
    notifyAtStart = notifyAtStart,
)

private fun EventReminders.toOut(): EventRemindersResponse = EventRemindersResponse(
    eventId = eventId,
    offsetsSeconds = offsetsSeconds,
    useDefaults = useDefaults,
    defaultOffsetsSeconds = defaultOffsetsSeconds,
    notifyAtStart = notifyAtStart,
)

private fun ReminderInstance.toOut(): ReminderInstanceResponse = ReminderInstanceResponse(
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
