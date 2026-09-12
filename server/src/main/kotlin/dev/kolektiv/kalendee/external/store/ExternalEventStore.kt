package dev.kolektiv.kalendee.external.store

import dev.kolektiv.kalendee.calendar.CalendarId
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
}
