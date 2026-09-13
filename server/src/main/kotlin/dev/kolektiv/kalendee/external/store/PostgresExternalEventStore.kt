package dev.kolektiv.kalendee.external.store

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.db.CalendarConnectionsTable
import dev.kolektiv.kalendee.db.CalendarsTable
import dev.kolektiv.kalendee.db.EventsTable
import dev.kolektiv.kalendee.db.ExternalCalendarsTable
import dev.kolektiv.kalendee.oauth.discord.ImportedCalendarEvent
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

class PostgresExternalEventStore(
    private val database: Database,
    private val clock: Clock = Clock.System,
) : ExternalEventStore {
    override suspend fun upsert(
        externalCalendarId: Uuid,
        calendarId: CalendarId,
        event: ImportedCalendarEvent,
    ) {
        dbQuery {
            val timeZone = CalendarsTable.selectAll()
                .where { CalendarsTable.id eq calendarId.toUuid() }
                .singleOrNull()
                ?.get(CalendarsTable.timeZone)
                ?: throw CalendarException.NotFound("calendar not found")
            val now = clock.now()
            val existing = EventsTable.selectAll()
                .where {
                    (EventsTable.externalCalendarId eq externalCalendarId) and
                        (EventsTable.externalUid eq event.uid)
                }
                .singleOrNull()
            if (existing == null) {
                EventsTable.insert {
                    it[EventsTable.id] = Uuid.random()
                    it[EventsTable.calendarId] = calendarId.toUuid()
                    it[title] = event.title
                    it[description] = event.description
                    it[location] = event.location
                    it[url] = null
                    it[startAt] = event.start
                    it[endAt] = event.end
                    it[allDay] = event.allDay
                    it[EventsTable.timeZone] = timeZone
                    it[status] = event.status.name
                    it[recurrenceFrequency] = null
                    it[recurrenceInterval] = null
                    it[recurrenceUntil] = null
                    it[recurrenceCount] = null
                    it[etag] = newEtag()
                    it[EventsTable.externalCalendarId] = externalCalendarId
                    it[externalUid] = event.uid
                    it[externalUpdatedAt] = now
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                return@dbQuery
            }
            val eventId = existing[EventsTable.id]
            val moved = existing[EventsTable.calendarId] != calendarId.toUuid()
            val changed = moved ||
                existing[EventsTable.title] != event.title ||
                existing[EventsTable.description] != event.description ||
                existing[EventsTable.location] != event.location ||
                existing[EventsTable.startAt] != event.start ||
                existing[EventsTable.endAt] != event.end ||
                existing[EventsTable.allDay] != event.allDay ||
                existing[EventsTable.status] != event.status.name
            EventsTable.update({ EventsTable.id eq eventId }) {
                if (changed) {
                    it[EventsTable.calendarId] = calendarId.toUuid()
                    it[title] = event.title
                    it[description] = event.description
                    it[location] = event.location
                    it[startAt] = event.start
                    it[endAt] = event.end
                    it[allDay] = event.allDay
                    it[EventsTable.timeZone] = timeZone
                    it[status] = event.status.name
                    it[etag] = newEtag()
                    it[updatedAt] = now
                }
                it[externalUpdatedAt] = now
            }
        }
    }

    override suspend fun deleteByUids(externalCalendarId: Uuid, uids: Collection<String>) {
        if (uids.isEmpty()) return
        dbQuery {
            EventsTable.deleteWhere {
                (EventsTable.externalCalendarId eq externalCalendarId) and
                    (EventsTable.externalUid inList uids.toList())
            }
        }
    }

    override suspend fun listBySource(externalCalendarId: Uuid): List<StoredExternalEvent> = dbQuery {
        val statuses = EventStatus.entries.associateBy { it.name }
        EventsTable.selectAll()
            .where { EventsTable.externalCalendarId eq externalCalendarId }
            .map { row -> row.toStoredExternalEvent(statuses) }
    }

    override suspend fun markCancelled(externalCalendarId: Uuid, uid: String) {
        dbQuery {
            val row = EventsTable.selectAll()
                .where {
                    (EventsTable.externalCalendarId eq externalCalendarId) and
                        (EventsTable.externalUid eq uid)
                }
                .singleOrNull()
                ?: return@dbQuery
            if (row[EventsTable.status] == EventStatus.CANCELLED.name) return@dbQuery
            val now = clock.now()
            EventsTable.update({ EventsTable.id eq row[EventsTable.id] }) {
                it[status] = EventStatus.CANCELLED.name
                it[etag] = newEtag()
                it[externalUpdatedAt] = now
                it[updatedAt] = now
            }
        }
    }

    override suspend fun findSource(eventId: EventId): ExternalEventSource? = dbQuery {
        (EventsTable innerJoin ExternalCalendarsTable innerJoin CalendarConnectionsTable)
            .selectAll()
            .where { EventsTable.id eq eventId.toUuid() }
            .singleOrNull()
            ?.let { row ->
                val externalCalendarId = row[EventsTable.externalCalendarId] ?: return@let null
                val uid = row[EventsTable.externalUid] ?: return@let null
                ExternalEventSource(
                    externalCalendarId = externalCalendarId,
                    calendarId = CalendarId(row[EventsTable.calendarId].toString()),
                    uid = uid,
                    externalId = row[ExternalCalendarsTable.externalId],
                    provider = row[CalendarConnectionsTable.provider],
                    ownerId = UserId(row[CalendarConnectionsTable.userId].toString()),
                    syncDirection = row[ExternalCalendarsTable.syncDirection],
                    enabled = row[ExternalCalendarsTable.enabled],
                    start = row[EventsTable.startAt],
                    end = row[EventsTable.endAt],
                )
            }
    }

    override suspend fun reschedule(externalCalendarId: Uuid, uid: String, start: Instant, end: Instant) {
        dbQuery {
            val row = EventsTable.selectAll()
                .where {
                    (EventsTable.externalCalendarId eq externalCalendarId) and
                        (EventsTable.externalUid eq uid)
                }
                .singleOrNull()
                ?: throw CalendarException.NotFound("external event not found")
            val now = clock.now()
            EventsTable.update({ EventsTable.id eq row[EventsTable.id] }) {
                it[startAt] = start
                it[endAt] = end
                it[etag] = newEtag()
                it[externalUpdatedAt] = now
                it[updatedAt] = now
            }
        }
    }

    private fun ResultRow.toStoredExternalEvent(statuses: Map<String, EventStatus>): StoredExternalEvent =
        StoredExternalEvent(
            uid = this[EventsTable.externalUid] ?: "",
            calendarId = CalendarId(this[EventsTable.calendarId].toString()),
            start = this[EventsTable.startAt],
            end = this[EventsTable.endAt],
            status = statuses[this[EventsTable.status]] ?: EventStatus.CONFIRMED,
        )

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }

    private fun newEtag(): String = Uuid.random().toString()
}

private fun CalendarId.toUuid(): Uuid = Uuid.parse(value)

private fun EventId.toUuid(): Uuid = Uuid.parse(value)
