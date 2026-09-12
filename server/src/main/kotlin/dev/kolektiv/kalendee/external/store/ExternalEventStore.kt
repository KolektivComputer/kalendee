package dev.kolektiv.kalendee.external.store

import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.oauth.discord.ImportedCalendarEvent
import kotlin.time.Instant

data class StoredExternalEvent(
    val uid: String,
    val start: Instant,
    val end: Instant,
    val status: EventStatus,
)

/**
 * Persistence for events mirrored from an external provider into a local
 * calendar. Implementations are expected to key rows on
 * `(external_calendar_id, external_uid)` and to bypass the local read-only
 * guard because they are the sync engine's own write path.
 */
interface ExternalEventStore {
    suspend fun upsert(calendarId: CalendarId, event: ImportedCalendarEvent)

    suspend fun deleteByUids(calendarId: CalendarId, uids: Collection<String>)

    suspend fun listExternal(calendarId: CalendarId): List<StoredExternalEvent>

    suspend fun markCancelled(calendarId: CalendarId, uid: String)
}
