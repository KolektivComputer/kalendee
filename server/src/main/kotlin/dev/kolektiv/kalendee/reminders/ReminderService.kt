package dev.kolektiv.kalendee.reminders

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.InstantRange
import dev.kolektiv.kalendee.db.EventReminderSettingsTable
import dev.kolektiv.kalendee.db.EventRemindersTable
import dev.kolektiv.kalendee.db.UserReminderDefaultsTable
import dev.kolektiv.kalendee.db.UsersTable
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

@Serializable
data class ReminderSettings(
    val defaultOffsetsSeconds: List<Int>,
    val notifyAtStart: Boolean,
)

@Serializable
data class EventReminders(
    val eventId: String,
    val offsetsSeconds: List<Int>,
    val useDefaults: Boolean,
    val defaultOffsetsSeconds: List<Int>,
    val notifyAtStart: Boolean,
)

@Serializable
data class ReminderInstance(
    val eventId: String,
    val calendarId: String,
    val calendarName: String,
    val calendarColor: String,
    val title: String,
    val start: Instant,
    val allDay: Boolean,
    val offsetSeconds: Int,
    val remindAt: Instant,
)

class ReminderService(
    private val database: Database,
    private val store: CalendarStore,
    private val clock: Clock,
) {
    suspend fun settings(userId: UserId): ReminderSettings = dbQuery { loadSettings(userId) }

    suspend fun updateSettings(
        userId: UserId,
        offsets: List<Int>,
        notifyAtStart: Boolean,
    ): ReminderSettings {
        val normalized = normalizedOffsets(offsets)
        return dbQuery {
            UserReminderDefaultsTable.deleteWhere {
                UserReminderDefaultsTable.userId eq userId.toUuid()
            }
            normalized.forEach { offset ->
                UserReminderDefaultsTable.insert {
                    it[UserReminderDefaultsTable.userId] = userId.toUuid()
                    it[UserReminderDefaultsTable.offsetSeconds] = offset
                }
            }
            UsersTable.update({ UsersTable.id eq userId.toUuid() }) {
                it[UsersTable.notifyAtStart] = notifyAtStart
            }
            loadSettings(userId)
        }
    }

    suspend fun eventReminders(userId: UserId, eventId: EventId): EventReminders {
        val event = store.getEvent(eventId, userId)
            ?: throw CalendarException.NotFound("event not found")
        return dbQuery { loadEventReminders(userId, event.id.value) }
    }

    suspend fun setEventReminders(
        userId: UserId,
        eventId: EventId,
        offsets: List<Int>,
        useDefaults: Boolean,
    ): EventReminders {
        val normalized = normalizedOffsets(offsets)
        val event = store.getEvent(eventId, userId)
            ?: throw CalendarException.NotFound("event not found")
        return dbQuery {
            val eventUuid = event.id.toUuid()
            val existing = EventReminderSettingsTable.selectAll()
                .where {
                    (EventReminderSettingsTable.userId eq userId.toUuid()) and
                        (EventReminderSettingsTable.eventId eq eventUuid)
                }
                .count() > 0
            val now = clock.now()
            if (existing) {
                EventReminderSettingsTable.update({
                    (EventReminderSettingsTable.userId eq userId.toUuid()) and
                        (EventReminderSettingsTable.eventId eq eventUuid)
                }) {
                    it[EventReminderSettingsTable.useDefaults] = useDefaults
                    it[updatedAt] = now
                }
            } else {
                EventReminderSettingsTable.insert {
                    it[EventReminderSettingsTable.userId] = userId.toUuid()
                    it[EventReminderSettingsTable.eventId] = eventUuid
                    it[EventReminderSettingsTable.useDefaults] = useDefaults
                    it[updatedAt] = now
                }
            }
            EventRemindersTable.deleteWhere {
                (EventRemindersTable.userId eq userId.toUuid()) and
                    (EventRemindersTable.eventId eq eventUuid)
            }
            normalized.forEach { offset ->
                EventRemindersTable.insert {
                    it[EventRemindersTable.userId] = userId.toUuid()
                    it[EventRemindersTable.eventId] = eventUuid
                    it[EventRemindersTable.offsetSeconds] = offset
                    it[createdAt] = now
                }
            }
            loadEventReminders(userId, event.id.value)
        }
    }

    suspend fun upcoming(userId: UserId, hours: Int = DefaultUpcomingHours): List<ReminderInstance> {
        val windowHours = hours.coerceIn(MinUpcomingHours, MaxUpcomingHours)
        val now = clock.now()
        val windowEnd = now + windowHours.hours
        val state = dbQuery { loadState(userId) }
        val maxOffset = maxOf(
            state.settings.defaultOffsetsSeconds.maxOrNull() ?: 0,
            state.offsetsByEvent.values.flatten().maxOrNull() ?: 0,
        )
        val events = store.listEvents(userId, InstantRange(now, windowEnd + maxOffset.seconds))
        if (events.isEmpty()) return emptyList()
        val calendars = store.listCalendars(userId).associateBy { it.id.value }
        return events.flatMap { event ->
            val calendar = calendars[event.calendarId.value] ?: return@flatMap emptyList()
            val useDefaults = state.useDefaultsByEvent[event.id.value] ?: true
            val configured = if (useDefaults) {
                state.settings.defaultOffsetsSeconds
            } else {
                state.offsetsByEvent[event.id.value].orEmpty()
            }
            val effective = if (state.settings.notifyAtStart) configured + 0 else configured
            effective.distinct().mapNotNull { offset ->
                val remindAt = event.start - offset.seconds
                if (remindAt > windowEnd) {
                    null
                } else {
                    ReminderInstance(
                        eventId = event.id.value,
                        calendarId = event.calendarId.value,
                        calendarName = calendar.displayName,
                        calendarColor = calendar.color,
                        title = event.title,
                        start = event.start,
                        allDay = event.allDay,
                        offsetSeconds = offset,
                        remindAt = remindAt,
                    )
                }
            }
        }.sortedWith(compareBy({ it.remindAt }, { it.eventId }, { it.offsetSeconds }))
    }

    private fun JdbcTransaction.loadSettings(userId: UserId): ReminderSettings {
        val offsets = UserReminderDefaultsTable.selectAll()
            .where { UserReminderDefaultsTable.userId eq userId.toUuid() }
            .orderBy(UserReminderDefaultsTable.offsetSeconds to SortOrder.ASC)
            .map { it[UserReminderDefaultsTable.offsetSeconds] }
        val notifyAtStart = UsersTable.selectAll()
            .where { UsersTable.id eq userId.toUuid() }
            .single()[UsersTable.notifyAtStart]
        return ReminderSettings(defaultOffsetsSeconds = offsets, notifyAtStart = notifyAtStart)
    }

    private fun JdbcTransaction.loadEventReminders(userId: UserId, eventId: String): EventReminders {
        val settings = loadSettings(userId)
        val eventUuid = Uuid.parse(eventId)
        val row = EventReminderSettingsTable.selectAll()
            .where {
                (EventReminderSettingsTable.userId eq userId.toUuid()) and
                    (EventReminderSettingsTable.eventId eq eventUuid)
            }
            .singleOrNull()
        val offsets = EventRemindersTable.selectAll()
            .where {
                (EventRemindersTable.userId eq userId.toUuid()) and
                    (EventRemindersTable.eventId eq eventUuid)
            }
            .orderBy(EventRemindersTable.offsetSeconds to SortOrder.ASC)
            .map { it[EventRemindersTable.offsetSeconds] }
        return EventReminders(
            eventId = eventId,
            offsetsSeconds = offsets,
            useDefaults = row?.get(EventReminderSettingsTable.useDefaults) ?: true,
            defaultOffsetsSeconds = settings.defaultOffsetsSeconds,
            notifyAtStart = settings.notifyAtStart,
        )
    }

    private fun JdbcTransaction.loadState(userId: UserId): StoredReminders {
        val useDefaultsByEvent = EventReminderSettingsTable.selectAll()
            .where { EventReminderSettingsTable.userId eq userId.toUuid() }
            .associate {
                it[EventReminderSettingsTable.eventId].toString() to
                    it[EventReminderSettingsTable.useDefaults]
            }
        val offsetsByEvent = EventRemindersTable.selectAll()
            .where { EventRemindersTable.userId eq userId.toUuid() }
            .orderBy(EventRemindersTable.offsetSeconds to SortOrder.ASC)
            .groupBy(
                keySelector = { it[EventRemindersTable.eventId].toString() },
                valueTransform = { it[EventRemindersTable.offsetSeconds] },
            )
        return StoredReminders(
            settings = loadSettings(userId),
            useDefaultsByEvent = useDefaultsByEvent,
            offsetsByEvent = offsetsByEvent,
        )
    }

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }
}

private data class StoredReminders(
    val settings: ReminderSettings,
    val useDefaultsByEvent: Map<String, Boolean>,
    val offsetsByEvent: Map<String, List<Int>>,
)

private fun EventId.toUuid(): Uuid = Uuid.parse(value)

private const val MaxRemindersPerTarget = 10
private const val MaxOffsetSeconds = 31_536_000
private const val DefaultUpcomingHours = 48
private const val MinUpcomingHours = 1
private const val MaxUpcomingHours = 168

private fun normalizedOffsets(offsets: List<Int>): List<Int> {
    if (offsets.size > MaxRemindersPerTarget) {
        throw CalendarException.Invalid("reminder offsets must contain at most $MaxRemindersPerTarget values")
    }
    if (offsets.any { it < 0 || it > MaxOffsetSeconds }) {
        throw CalendarException.Invalid(
            "reminder offsets must be between 0 and $MaxOffsetSeconds seconds",
        )
    }
    return offsets.filter { it > 0 }.distinct().sorted()
}
