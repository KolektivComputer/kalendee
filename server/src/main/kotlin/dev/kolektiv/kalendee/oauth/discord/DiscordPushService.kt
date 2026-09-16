package dev.kolektiv.kalendee.oauth.discord

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.external.store.ExternalEventSource
import dev.kolektiv.kalendee.external.store.ExternalEventStore
import dev.kolektiv.kalendee.oauth.providers.DiscordApi
import dev.kolektiv.kalendee.oauth.providers.DiscordApiHttpException
import kotlin.time.Instant

/**
 * Pushes local reschedules of imported Discord events back to Discord. Anyone
 * with write permission on the mirrored calendar may push and the import must
 * be two-way enabled. Base event edits patch the scheduled event itself; edits
 * of a materialized occurrence create or modify a Discord scheduled-event
 * exception, tracked by the exception id stored on the mirrored row.
 */
class DiscordPushService(
    private val store: CalendarStore,
    private val externalEvents: ExternalEventStore,
    private val api: DiscordApi,
) {
    suspend fun reschedule(
        actorId: UserId,
        eventId: EventId,
        start: Instant?,
        end: Instant?,
    ): Event {
        val event = store.getEvent(eventId, actorId)
            ?: throw CalendarException.NotFound("event not found")
        if (event.externalCalendarId == null) throw CalendarException.Forbidden(ExternalReadOnlyMessage)
        val source = externalEvents.findSource(eventId)
            ?: throw CalendarException.Forbidden(ExternalReadOnlyMessage)
        if (source.provider != DiscordProviderId) throw CalendarException.Forbidden(ExternalReadOnlyMessage)
        val permission = store.getCalendar(source.calendarId, actorId)?.permission
        if (permission?.canWrite != true) {
            throw CalendarException.Forbidden("you do not have permission to reschedule this event")
        }
        if (source.syncDirection != SyncDirectionBoth) {
            throw CalendarException.Forbidden(
                "this Discord server only syncs into Kalendee; enable two-way sync before rescheduling",
            )
        }
        if (!source.enabled) {
            throw CalendarException.Forbidden("this Discord import is disabled")
        }
        val baseEventId = discordBaseEventId(source.externalId, source.uid)
            ?: throw CalendarException.Forbidden(NotImportedMessage)
        val baseUid = discordEventUid(source.externalId, baseEventId)
        if (source.uid.startsWith("$baseUid:")) {
            val originalStart = discordOccurrenceStart(source.externalId, baseEventId, source.uid)
                ?: throw CalendarException.Forbidden(NotImportedMessage)
            return rescheduleOccurrence(actorId, eventId, source, baseEventId, originalStart, start, end)
        }

        val confirmed = try {
            api.modifyScheduledEvent(
                guildId = source.externalId,
                eventId = baseEventId,
                start = start,
                end = end,
            )
        } catch (cause: DiscordApiHttpException) {
            throwMapped(cause)
        }

        val newStart = parseInstant(confirmed.scheduledStartTime) ?: start ?: source.start
        val newEnd = parseInstant(confirmed.scheduledEndTime) ?: end ?: source.end
        externalEvents.reschedule(source.externalCalendarId, source.uid, newStart, newEnd)
        return store.getEvent(eventId, actorId)
            ?: throw CalendarException.NotFound("event not found")
    }

    /**
     * Creates an exception for an occurrence Kalendee has not overridden yet,
     * or patches the exception it created earlier. Discord requires the
     * original occurrence start on create and returns it nowhere, so the
     * occurrence start is parsed from the local uid and the returned exception
     * id is stored for later edits.
     */
    private suspend fun rescheduleOccurrence(
        actorId: UserId,
        eventId: EventId,
        source: ExternalEventSource,
        baseEventId: String,
        originalStart: Instant,
        start: Instant?,
        end: Instant?,
    ): Event {
        val confirmed = try {
            val existingExceptionId = source.externalExceptionId
            if (existingExceptionId == null) {
                val created = api.createScheduledEventException(
                    guildId = source.externalId,
                    eventId = baseEventId,
                    originalStart = originalStart,
                    start = start,
                    end = end,
                )
                externalEvents.setExceptionId(eventId, created.exceptionId)
                created
            } else {
                api.modifyScheduledEventException(
                    guildId = source.externalId,
                    eventId = baseEventId,
                    exceptionId = existingExceptionId,
                    start = start,
                    end = end,
                )
            }
        } catch (cause: DiscordApiHttpException) {
            throwMapped(cause)
        }

        val newStart = parseInstant(confirmed.scheduledStartTime) ?: start ?: source.start
        val newEnd = parseInstant(confirmed.scheduledEndTime) ?: end ?: source.end
        externalEvents.reschedule(source.externalCalendarId, source.uid, newStart, newEnd)
        return store.getEvent(eventId, actorId)
            ?: throw CalendarException.NotFound("event not found")
    }

    private fun throwMapped(cause: DiscordApiHttpException): Nothing = when (cause.statusCode) {
        HttpStatusCodeForbidden -> throw CalendarException.Forbidden(ManageEventsMessage)
        HttpStatusCodeNotFound -> throw CalendarException.NotFound(EventGoneMessage)
        else -> throw cause
    }

    private fun parseInstant(raw: String?): Instant? =
        raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private companion object {
        const val DiscordProviderId = "discord"
        const val NotImportedMessage = "this event is not imported from Discord"
        const val ExternalReadOnlyMessage =
            "this external calendar is read-only; reschedules are only supported for two-way Discord imports"
        const val ManageEventsMessage = "the Kalendee bot needs MANAGE_EVENTS in this Discord server"
        const val EventGoneMessage = "the Discord scheduled event no longer exists"
        const val HttpStatusCodeForbidden = 403
        const val HttpStatusCodeNotFound = 404
    }
}
