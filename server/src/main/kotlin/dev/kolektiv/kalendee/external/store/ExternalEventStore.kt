package dev.kolektiv.kalendee.external.store

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.oauth.discord.ImportedCalendarEvent
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class StoredExternalEvent(
    val uid: String,
    val calendarId: CalendarId,
    val start: Instant,
    val end: Instant,
    val status: EventStatus,
    /** The provider-side exception that overrides this occurrence, if any. */
    val exceptionId: String? = null,
)

/**
 * Everything a push back to the external provider needs to know about one
 * mirrored event: where it lives, which source owns it, and whether that source
 * is allowed to write through.
 */
data class ExternalEventSource(
    val externalCalendarId: Uuid,
    val calendarId: CalendarId,
    val uid: String,
    val externalId: String,
    val provider: String,
    val ownerId: UserId,
    val syncDirection: String,
    val enabled: Boolean,
    val start: Instant,
    val end: Instant,
    val externalExceptionId: String? = null,
)

/**
 * Persistence for events mirrored from an external provider into a local
 * calendar. Implementations are expected to key rows on
 * `(external_calendar_id, external_uid)` and to bypass the local read-only
 * guard because they are the sync engine's own write path. Rows are addressed
 * by source instead of calendar because per-event routing can place one source
 * in several calendars.
 */
interface ExternalEventStore {
    suspend fun upsert(externalCalendarId: Uuid, calendarId: CalendarId, event: ImportedCalendarEvent)

    suspend fun deleteByUids(externalCalendarId: Uuid, uids: Collection<String>)

    suspend fun listBySource(externalCalendarId: Uuid): List<StoredExternalEvent>

    suspend fun markCancelled(externalCalendarId: Uuid, uid: String)

    /** The mirrored event and its source, or null when the event is not imported. */
    suspend fun findSource(eventId: EventId): ExternalEventSource?

    /**
     * Rewrites the times of one mirrored row after the provider confirmed the
     * change. Bumps the local etag so clients refetch.
     */
    suspend fun reschedule(externalCalendarId: Uuid, uid: String, start: Instant, end: Instant)

    /**
     * Remembers the provider's scheduled-event exception id on one mirrored
     * occurrence, so later edits can modify the same exception instead of
     * creating a duplicate.
     */
    suspend fun setExceptionId(eventId: EventId, exceptionId: String)

    /**
     * Re-applies a provider-side exception to the mirrored occurrence that was
     * created for it. Matches on `(external_calendar_id, external_exception_id)`
     * and may update zero rows: exceptions created directly in the provider's
     * client cannot be correlated to an occurrence. Non-null times overwrite
     * the row's times, [cancelled] sets the status, and the etag is bumped.
     */
    suspend fun applyException(
        externalCalendarId: Uuid,
        exceptionId: String,
        start: Instant?,
        end: Instant?,
        cancelled: Boolean,
    )
}
