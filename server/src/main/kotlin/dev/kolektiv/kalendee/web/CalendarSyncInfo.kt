package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.db.CalendarConnectionsTable
import dev.kolektiv.kalendee.db.ExternalCalendarsTable
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Copies external-sync metadata onto calendar summaries so clients can tell
 * which calendars mirror a provider and whether the mirror accepts pushes.
 */
class CalendarSyncInfoEnricher(private val database: Database) {
    suspend fun attachSyncInfo(calendars: List<CalendarSummary>): List<CalendarSummary> {
        if (calendars.isEmpty()) return calendars
        val ids = calendars.mapNotNull { Uuid.parseOrNull(it.id) }
        if (ids.isEmpty()) return calendars
        val syncInfo = dbQuery(ids)
        if (syncInfo.isEmpty()) return calendars
        return calendars.map { calendar ->
            val id = Uuid.parseOrNull(calendar.id) ?: return@map calendar
            val info = syncInfo[id] ?: return@map calendar
            calendar.copy(
                connectionId = info.connectionId.toString(),
                syncDirection = info.syncDirection,
                syncBlockedReason = info.syncBlockedReason,
                syncStatus = if (info.lastError != null) "error" else "ok",
                syncError = info.lastError,
            )
        }
    }

    private suspend fun dbQuery(calendarIds: List<Uuid>): Map<Uuid, SyncInfo> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            (ExternalCalendarsTable innerJoin CalendarConnectionsTable)
                .selectAll()
                .where { ExternalCalendarsTable.calendarId inList calendarIds }
                .associate { row ->
                    row[ExternalCalendarsTable.calendarId] to SyncInfo(
                        connectionId = row[CalendarConnectionsTable.id],
                        syncDirection = row[ExternalCalendarsTable.syncDirection],
                        syncBlockedReason = row[ExternalCalendarsTable.syncBlockedReason],
                        lastError = row[ExternalCalendarsTable.lastError],
                    )
                }
        }
    }

    private data class SyncInfo(
        val connectionId: Uuid,
        val syncDirection: String,
        val syncBlockedReason: String?,
        val lastError: String?,
    )
}
