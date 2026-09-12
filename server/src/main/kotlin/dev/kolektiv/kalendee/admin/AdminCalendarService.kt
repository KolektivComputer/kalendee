package dev.kolektiv.kalendee.admin

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.db.CalendarsTable
import dev.kolektiv.kalendee.db.EventsTable
import dev.kolektiv.kalendee.db.UsersTable
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

data class AdminCalendar(
    val id: String,
    val displayName: String,
    val ownerUsername: String,
    val eventCount: Long,
    val publicLinkEnabled: Boolean,
)

class AdminCalendarService(
    private val database: Database,
    private val clock: Clock,
) {
    suspend fun listCalendars(): List<AdminCalendar> = dbQuery {
        val eventCounts = EventsTable.selectAll()
            .map { it[EventsTable.calendarId] }
            .groupingBy { it }
            .eachCount()
        (CalendarsTable innerJoin UsersTable)
            .selectAll()
            .orderBy(CalendarsTable.displayName to SortOrder.ASC, CalendarsTable.id to SortOrder.ASC)
            .map { row ->
                AdminCalendar(
                    id = row[CalendarsTable.id].toString(),
                    displayName = row[CalendarsTable.displayName],
                    ownerUsername = row[UsersTable.username],
                    eventCount = eventCounts[row[CalendarsTable.id]]?.toLong() ?: 0L,
                    publicLinkEnabled = row[CalendarsTable.publicLinkEnabled],
                )
            }
    }

    suspend fun adminDeleteCalendar(actorId: UserId, calendarId: CalendarId): Boolean = dbQuery {
        val actor = userRow(actorId) ?: throw CalendarException.Unauthorized("unauthorized")
        requireAdminActor(actor)
        CalendarsTable.deleteWhere { CalendarsTable.id eq calendarId.toUuid() } > 0
    }

    suspend fun adminSetCalendarPublicLink(
        actorId: UserId,
        calendarId: CalendarId,
        enabled: Boolean,
    ): AdminCalendar = dbQuery {
        val actor = userRow(actorId) ?: throw CalendarException.Unauthorized("unauthorized")
        requireAdminActor(actor)
        val row = (CalendarsTable innerJoin UsersTable)
            .selectAll()
            .where { CalendarsTable.id eq calendarId.toUuid() }
            .singleOrNull()
            ?: throw CalendarException.NotFound("calendar not found")
        CalendarsTable.update({ CalendarsTable.id eq calendarId.toUuid() }) {
            it[publicLinkEnabled] = enabled
            it[updatedAt] = clock.now()
        }
        AdminCalendar(
            id = row[CalendarsTable.id].toString(),
            displayName = row[CalendarsTable.displayName],
            ownerUsername = row[UsersTable.username],
            eventCount = EventsTable.selectAll()
                .where { EventsTable.calendarId eq calendarId.toUuid() }
                .count(),
            publicLinkEnabled = enabled,
        )
    }

    private fun requireAdminActor(actor: ResultRow) {
        if (!actor[UsersTable.isAdmin] && !actor[UsersTable.isSuperadmin]) {
            throw CalendarException.Forbidden("admin only")
        }
    }

    private fun JdbcTransaction.userRow(userId: UserId): ResultRow? = UsersTable.selectAll()
        .where { UsersTable.id eq userId.toUuid() }
        .singleOrNull()

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }
}

private fun CalendarId.toUuid() = kotlin.uuid.Uuid.parse(value)
