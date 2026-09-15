package dev.kolektiv.kalendee.events

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.OptionalField
import dev.kolektiv.kalendee.calendar.UpdateEvent
import dev.kolektiv.kalendee.calendar.requireEndAfterStart
import dev.kolektiv.kalendee.oauth.discord.DiscordPushService

/**
 * Single write path for event updates. Local events keep the regular store
 * flow; imported events accept reschedules only and are pushed to their
 * provider first, so the Keel actions and the JSON API cannot drift.
 */
class EventUpdateService(
    private val store: CalendarStore,
    private val discordPush: DiscordPushService,
) {
    suspend fun update(
        id: EventId,
        actorId: UserId,
        command: UpdateEvent,
        expectedEtag: String? = null,
    ): Event {
        val existing = store.getEvent(id, actorId)
            ?: throw CalendarException.NotFound("event not found")
        if (existing.externalCalendarId == null) {
            return store.updateEvent(id, actorId, command, expectedEtag)
                ?: throw CalendarException.NotFound("event not found")
        }
        requireEtag(existing, expectedEtag)
        if (command.changesBeyondReschedule(existing)) {
            throw CalendarException.Forbidden(ImportedRescheduleOnlyMessage)
        }
        if (command.start == null && command.end == null) return existing
        requireEndAfterStart(command.start ?: existing.start, command.end ?: existing.end)
        return discordPush.reschedule(actorId, id, command.start, command.end)
    }

    private fun UpdateEvent.changesBeyondReschedule(existing: Event): Boolean =
        (title != null && title != existing.title) ||
            description.changedFrom(existing.description) ||
            location.changedFrom(existing.location) ||
            url.changedFrom(existing.url) ||
            (allDay != null && allDay != existing.allDay) ||
            timeZone.changedFrom(existing.timeZone) ||
            (status != null && status != existing.status) ||
            recurrence.changedFrom(existing.recurrence)

    private fun <T> OptionalField<T>.changedFrom(existing: T): Boolean =
        this is OptionalField.Present && value != existing

    private fun requireEtag(existing: Event, expectedEtag: String?) {
        if (expectedEtag == null || expectedEtag == "*") return
        if (existing.etag != expectedEtag) {
            throw CalendarException.PreconditionFailed("etag mismatch")
        }
    }

    private companion object {
        const val ImportedRescheduleOnlyMessage = "imported events can only be rescheduled"
    }
}
