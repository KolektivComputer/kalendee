package dev.kolektiv.kalendee.external.store

import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.db.DiscordEventRoutesTable
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

class PostgresExternalEventRouteStore(
    private val database: Database,
    private val clock: Clock = Clock.System,
) : ExternalEventRouteStore {
    override suspend fun routes(externalCalendarId: Uuid): Map<String, RouteTarget> = dbQuery {
        DiscordEventRoutesTable.selectAll()
            .where { DiscordEventRoutesTable.externalCalendarId eq externalCalendarId }
            .associate { row -> row.toRouteEntry() }
    }

    override suspend fun replaceRoutes(externalCalendarId: Uuid, routes: Map<String, RouteTarget>) {
        dbQuery {
            DiscordEventRoutesTable.deleteWhere {
                DiscordEventRoutesTable.externalCalendarId eq externalCalendarId
            }
            val now = clock.now()
            routes.forEach { (eventId, target) ->
                DiscordEventRoutesTable.insert {
                    it[id] = Uuid.random()
                    it[DiscordEventRoutesTable.externalCalendarId] = externalCalendarId
                    it[DiscordEventRoutesTable.eventId] = eventId
                    it[calendarId] = (target as? RouteTarget.Calendar)?.calendarId?.toUuid()
                    it[skipped] = target == RouteTarget.Skip
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
    }

    override suspend fun deleteRoutes(externalCalendarId: Uuid) {
        dbQuery {
            DiscordEventRoutesTable.deleteWhere {
                DiscordEventRoutesTable.externalCalendarId eq externalCalendarId
            }
        }
    }

    private fun ResultRow.toRouteEntry(): Pair<String, RouteTarget> {
        val eventId = this[DiscordEventRoutesTable.eventId]
        if (this[DiscordEventRoutesTable.skipped]) {
            return eventId to RouteTarget.Skip
        }
        val calendarId = checkNotNull(this[DiscordEventRoutesTable.calendarId]) {
            "discord event route $eventId is missing a calendar"
        }
        return eventId to RouteTarget.Calendar(CalendarId(calendarId.toString()))
    }

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }
}

private fun CalendarId.toUuid(): Uuid = Uuid.parse(value)
