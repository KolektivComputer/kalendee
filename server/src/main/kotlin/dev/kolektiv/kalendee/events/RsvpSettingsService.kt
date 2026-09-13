package dev.kolektiv.kalendee.events

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.db.CalendarsTable
import dev.kolektiv.kalendee.db.ExternalCalendarsTable
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

data class CalendarRsvpSettings(
    val calendarId: CalendarId,
    val rsvpEnabled: Boolean,
    val anonymousRsvpEnabled: Boolean,
)

class RsvpSettingsService(
    private val database: Database,
    private val store: CalendarStore,
    private val clock: Clock,
) {
    suspend fun settings(calendarId: CalendarId, userId: UserId): CalendarRsvpSettings =
        requireOwner(calendarId, userId).toSettings()

    suspend fun updateSettings(
        calendarId: CalendarId,
        userId: UserId,
        rsvpEnabled: Boolean,
        anonymousRsvpEnabled: Boolean,
    ): CalendarRsvpSettings {
        requireOwner(calendarId, userId)
        dbQuery {
            if (calendarIsMirrored(calendarId)) {
                throw CalendarException.Forbidden(
                    "this calendar syncs from an external provider and is read-only",
                )
            }
            CalendarsTable.update({ CalendarsTable.id eq calendarId.toUuid() }) {
                it[CalendarsTable.rsvpEnabled] = rsvpEnabled
                it[CalendarsTable.anonymousRsvpEnabled] = anonymousRsvpEnabled
                it[updatedAt] = clock.now()
            }
        }
        val updated = store.getCalendar(calendarId, userId)
            ?: throw CalendarException.NotFound("calendar not found")
        return updated.toSettings()
    }

    private suspend fun requireOwner(calendarId: CalendarId, userId: UserId): Calendar {
        val calendar = store.getCalendar(calendarId, userId)
            ?: throw CalendarException.NotFound("calendar not found")
        if (calendar.permission != CalendarPermission.OWNER) {
            throw CalendarException.Forbidden("owner only")
        }
        return calendar
    }

    private fun JdbcTransaction.calendarIsMirrored(calendarId: CalendarId): Boolean =
        ExternalCalendarsTable.selectAll()
            .where { ExternalCalendarsTable.calendarId eq calendarId.toUuid() }
            .count() > 0

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }
}

private fun Calendar.toSettings(): CalendarRsvpSettings = CalendarRsvpSettings(
    calendarId = id,
    rsvpEnabled = rsvpEnabled,
    anonymousRsvpEnabled = anonymousRsvpEnabled,
)

private fun CalendarId.toUuid(): Uuid = Uuid.parse(value)
