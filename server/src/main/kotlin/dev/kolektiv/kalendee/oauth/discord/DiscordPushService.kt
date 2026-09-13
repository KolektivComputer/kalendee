package dev.kolektiv.kalendee.oauth.discord

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.external.store.ExternalEventStore
import dev.kolektiv.kalendee.oauth.providers.DiscordApi
import dev.kolektiv.kalendee.oauth.providers.DiscordApiHttpException
import kotlin.time.Instant

/**
 * Pushes local reschedules of imported Discord events back to Discord. Only
 * the connection owner may push, the import must be two-way enabled, and the
 * event must be the base event of its series: per-occurrence edits need the
 * scheduled-event exceptions API, which is not implemented yet.
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
        if (event.externalCalendarId == null) throw CalendarException.Forbidden(NotImportedMessage)
        val source = externalEvents.findSource(eventId)
            ?: throw CalendarException.Forbidden(NotImportedMessage)
        if (source.provider != DiscordProviderId) throw CalendarException.Forbidden(NotImportedMessage)
        if (source.ownerId != actorId) {
            throw CalendarException.Forbidden(
                "only the Discord account that imported this event can reschedule it",
            )
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
        if (source.uid != baseUid && source.uid.startsWith("$baseUid:")) {
            throw CalendarException.Forbidden(
                "this event repeats on Discord; rescheduling individual occurrences is not supported yet",
            )
        }

        val confirmed = try {
            api.modifyScheduledEvent(
                guildId = source.externalId,
                eventId = baseEventId,
                start = start,
                end = end,
            )
        } catch (cause: DiscordApiHttpException) {
            when (cause.statusCode) {
                HttpStatusCodeForbidden -> throw CalendarException.Forbidden(
                    "the Kalendee bot needs MANAGE_EVENTS in this Discord server",
                )
                HttpStatusCodeNotFound -> throw CalendarException.NotFound(
                    "the Discord scheduled event no longer exists",
                )
                else -> throw cause
            }
        }

        val newStart = parseInstant(confirmed.scheduledStartTime) ?: start ?: source.start
        val newEnd = parseInstant(confirmed.scheduledEndTime) ?: end ?: source.end
        externalEvents.reschedule(source.externalCalendarId, source.uid, newStart, newEnd)
        return store.getEvent(eventId, actorId)
            ?: throw CalendarException.NotFound("event not found")
    }

    private fun parseInstant(raw: String?): Instant? =
        raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private companion object {
        const val DiscordProviderId = "discord"
        const val NotImportedMessage = "this event is not imported from Discord"
        const val HttpStatusCodeForbidden = 403
        const val HttpStatusCodeNotFound = 404
    }
}
