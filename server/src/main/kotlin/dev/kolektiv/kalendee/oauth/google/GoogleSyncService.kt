package dev.kolektiv.kalendee.oauth.google

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.db.CalendarConnectionsTable
import dev.kolektiv.kalendee.db.ExternalCalendarsTable
import dev.kolektiv.kalendee.external.store.ExternalEventStore
import dev.kolektiv.kalendee.oauth.ConnectionService
import dev.kolektiv.kalendee.oauth.OAuthReauthRequiredException
import dev.kolektiv.kalendee.oauth.ProviderRegistry
import dev.kolektiv.kalendee.oauth.discord.ImportedCalendarEvent
import dev.kolektiv.kalendee.oauth.discord.UntitledEventTitle
import dev.kolektiv.kalendee.oauth.providers.GoogleCalendarApi
import dev.kolektiv.kalendee.oauth.providers.GoogleProvider
import dev.kolektiv.kalendee.oauth.providers.GoogleRemoteCalendar
import dev.kolektiv.kalendee.oauth.providers.GoogleRemoteEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

class GoogleSyncException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

sealed interface GoogleImportedEvent {
    data class Upsert(val event: ImportedCalendarEvent) : GoogleImportedEvent
    data class Tombstone(val uid: String) : GoogleImportedEvent
}

fun googleEventUid(calendarId: String, eventId: String): String = "google:$calendarId:$eventId"

fun parseGoogleInstant(raw: String, allDay: Boolean = false): Instant? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (allDay || DateOnly.matches(trimmed)) {
        val date = runCatching { LocalDate.parse(trimmed.take(10)) }.getOrNull() ?: return null
        return date.atStartOfDayIn(TimeZone.UTC)
    }
    return runCatching { Instant.parse(trimmed) }.getOrNull()
        ?: runCatching { Instant.parse(trimmed.replace(' ', 'T')) }.getOrNull()
}

fun interpretGoogleEvent(event: GoogleRemoteEvent): GoogleImportedEvent? {
    val uid = googleEventUid(event.calendarId, event.id)
    if (event.status.equals("cancelled", ignoreCase = true)) {
        return GoogleImportedEvent.Tombstone(uid)
    }
    val start = parseGoogleInstant(event.start, event.allDay) ?: return null
    var end = parseGoogleInstant(event.end, event.allDay) ?: start
    if (end <= start) {
        end = start + if (event.allDay) 1.days else 1.hours
    }
    val status = when (event.status?.lowercase()) {
        "tentative" -> EventStatus.TENTATIVE
        else -> EventStatus.CONFIRMED
    }
    return GoogleImportedEvent.Upsert(
        ImportedCalendarEvent(
            uid = uid,
            title = event.summary.takeIf { it.isNotBlank() } ?: UntitledEventTitle,
            description = event.description,
            location = event.location,
            start = start,
            end = end,
            status = status,
            allDay = event.allDay,
        ),
    )
}

/**
 * Mirrors Google Calendar into local Kalendee calendars. One remote calendar
 * becomes one local calendar named `Google · {summary}`. Pulls happen on
 * demand through [discoverAndSync] / [syncNow]; nothing is pushed back.
 */
class GoogleSyncService(
    private val database: Database,
    private val connections: ConnectionService,
    private val store: CalendarStore,
    private val externalEvents: ExternalEventStore,
    private val registry: ProviderRegistry,
    private val clock: Clock = Clock.System,
) {
    private val log = LoggerFactory.getLogger(GoogleSyncService::class.java)
    private val syncLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Lists Google calendars for the connection, creates local mirrors for any
     * that are not mapped yet, then pulls events for every enabled mapping.
     */
    suspend fun discoverAndSync(userId: UserId, connectionId: String) {
        val lock = syncLocks.computeIfAbsent(connectionId) { Mutex() }
        lock.withLock {
            val connection = requireGoogleConnection(userId, connectionId)
            try {
                val token = connections.validAccessToken(userId, connectionId)
                val api = googleApi()
                val remotes = try {
                    api.listCalendars(token)
                } catch (cause: CalendarException.Unauthorized) {
                    throw reauthIfUnauthorized(userId, connectionId, cause)
                }
                ensureMappings(userId, connection, remotes)
                pullEnabled(userId, connection, token, api)
                dbQuery { markConnectionSuccess(connection, clock.now()) }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: OAuthReauthRequiredException) {
                dbQuery { markConnectionFailure(connection, cause.message ?: ReauthMessage, clock.now()) }
                throw cause
            } catch (cause: CalendarException.Unauthorized) {
                throw reauthIfUnauthorized(userId, connectionId, cause)
            } catch (cause: Exception) {
                val message = cause.message ?: "google sync failed"
                dbQuery { markConnectionFailure(connection, message, clock.now()) }
                throw GoogleSyncException("google sync failed: $message", cause)
            }
        }
    }

    /**
     * Re-pulls enabled mappings. Discovers any new Google calendars first so a
     * "Sync now" after a failed initial import still creates local mirrors.
     */
    suspend fun syncNow(userId: UserId, connectionId: String) {
        discoverAndSync(userId, connectionId)
    }

    private suspend fun ensureMappings(
        userId: UserId,
        connection: Uuid,
        remotes: List<GoogleRemoteCalendar>,
    ) {
        val existing = dbQuery { externalMappings(connection) }
        remotes.forEach { remote ->
            if (existing.containsKey(remote.id)) return@forEach
            val display = "Google · ${remote.summary.trim().ifBlank { "calendar" }}"
            val calendar = store.createCalendar(userId, CreateCalendar(displayName = display))
            val now = clock.now()
            val externalCalendarId = Uuid.random()
            dbQuery {
                ExternalCalendarsTable.insert {
                    it[id] = externalCalendarId
                    it[ExternalCalendarsTable.connectionId] = connection
                    it[externalId] = remote.id
                    it[calendarId] = calendar.id.toUuid()
                    it[externalName] = remote.summary
                    it[syncDirection] = SyncDirectionPull
                    it[enabled] = true
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
    }

    private suspend fun pullEnabled(
        userId: UserId,
        connection: Uuid,
        token: String,
        api: GoogleCalendarApi,
    ) {
        val mappings = dbQuery {
            ExternalCalendarsTable.selectAll()
                .where {
                    (ExternalCalendarsTable.connectionId eq connection) and
                        (ExternalCalendarsTable.enabled eq true)
                }
                .map { it.toMapping() }
        }
        mappings.forEach { mapping ->
            try {
                pullCalendar(mapping, token, api)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: OAuthReauthRequiredException) {
                throw cause
            } catch (cause: CalendarException.Unauthorized) {
                throw reauthIfUnauthorized(userId, connection.toString(), cause)
            } catch (cause: Exception) {
                log.warn("google calendar {} sync failed: {}", mapping.externalId, cause.message)
                dbQuery { markMappingFailure(mapping, cause.message ?: "google sync failed", clock.now()) }
            }
        }
    }

    private suspend fun pullCalendar(mapping: Mapping, token: String, api: GoogleCalendarApi) {
        var syncToken = mapping.syncToken
        var pageToken: String? = null
        var retriedExpired = false
        while (true) {
            val page = api.listEvents(
                accessToken = token,
                calendarId = mapping.externalId,
                syncToken = syncToken.takeIf { pageToken == null },
                pageToken = pageToken,
            )
            if (page.expiredSyncToken) {
                if (retriedExpired) {
                    throw GoogleSyncException("google sync token expired twice for ${mapping.externalId}")
                }
                retriedExpired = true
                syncToken = null
                pageToken = null
                dbQuery {
                    ExternalCalendarsTable.update({ ExternalCalendarsTable.id eq mapping.id }) {
                        it[ExternalCalendarsTable.syncToken] = null
                        it[updatedAt] = clock.now()
                    }
                }
                continue
            }
            applyEvents(mapping, page.events)
            val nextSync = page.nextSyncToken
            if (nextSync != null) {
                dbQuery {
                    ExternalCalendarsTable.update({ ExternalCalendarsTable.id eq mapping.id }) {
                        it[ExternalCalendarsTable.syncToken] = nextSync
                        it[updatedAt] = clock.now()
                    }
                }
            }
            pageToken = page.nextPageToken
            if (pageToken == null) break
            syncToken = null
        }
        dbQuery { markMappingSuccess(mapping, clock.now()) }
    }

    private suspend fun applyEvents(mapping: Mapping, events: List<GoogleRemoteEvent>) {
        events.forEach { remote ->
            when (val mapped = interpretGoogleEvent(remote)) {
                is GoogleImportedEvent.Upsert ->
                    externalEvents.upsert(mapping.id, mapping.calendarId, mapped.event)
                is GoogleImportedEvent.Tombstone ->
                    externalEvents.markCancelled(mapping.id, mapped.uid)
                null -> Unit
            }
        }
    }

    private suspend fun requireGoogleConnection(userId: UserId, connectionId: String): Uuid {
        val uuid = Uuid.parseOrNull(connectionId)
            ?: throw CalendarException.Invalid("invalid connection id")
        val provider = dbQuery {
            CalendarConnectionsTable.selectAll()
                .where {
                    (CalendarConnectionsTable.id eq uuid) and
                        (CalendarConnectionsTable.userId eq userId.toUuid())
                }
                .singleOrNull()
                ?.get(CalendarConnectionsTable.provider)
        } ?: throw CalendarException.NotFound("connection not found")
        if (provider != GoogleProviderId) {
            throw CalendarException.Invalid("connection is not a Google connection")
        }
        return uuid
    }

    private suspend fun reauthIfUnauthorized(
        userId: UserId,
        connectionId: String,
        cause: CalendarException.Unauthorized,
    ): Nothing {
        val message = cause.message ?: "google rejected the access token"
        if ("HTTP 401" in message) {
            connections.markNeedsReauth(userId, connectionId, message)
            throw OAuthReauthRequiredException(ReauthMessage)
        }
        throw cause
    }

    private fun googleApi(): GoogleCalendarApi {
        val provider = registry.byId(GoogleProviderId) as? GoogleProvider
            ?: throw CalendarException.Invalid("google provider is not registered")
        return provider.calendarApi()
    }

    private fun JdbcTransaction.externalMappings(connectionId: Uuid): Map<String, ResultRow> =
        ExternalCalendarsTable.selectAll()
            .where { ExternalCalendarsTable.connectionId eq connectionId }
            .associateBy { it[ExternalCalendarsTable.externalId] }

    private fun ResultRow.toMapping(): Mapping = Mapping(
        id = this[ExternalCalendarsTable.id],
        connectionId = this[ExternalCalendarsTable.connectionId],
        externalId = this[ExternalCalendarsTable.externalId],
        calendarId = CalendarId(this[ExternalCalendarsTable.calendarId].toString()),
        syncToken = this[ExternalCalendarsTable.syncToken],
    )

    private fun JdbcTransaction.markMappingSuccess(mapping: Mapping, now: Instant) {
        ExternalCalendarsTable.update({ ExternalCalendarsTable.id eq mapping.id }) {
            it[lastSyncAt] = now
            it[lastError] = null
            it[updatedAt] = now
        }
    }

    private fun JdbcTransaction.markMappingFailure(mapping: Mapping, message: String, now: Instant) {
        val truncated = message.take(MaxLastErrorLength)
        ExternalCalendarsTable.update({ ExternalCalendarsTable.id eq mapping.id }) {
            it[lastError] = truncated
            it[updatedAt] = now
        }
    }

    private fun JdbcTransaction.markConnectionSuccess(connectionId: Uuid, now: Instant) {
        CalendarConnectionsTable.update({ CalendarConnectionsTable.id eq connectionId }) {
            it[lastSyncAt] = now
            it[lastError] = null
            it[updatedAt] = now
        }
    }

    private fun JdbcTransaction.markConnectionFailure(connectionId: Uuid, message: String, now: Instant) {
        val truncated = message.take(MaxLastErrorLength)
        CalendarConnectionsTable.update({ CalendarConnectionsTable.id eq connectionId }) {
            it[lastError] = truncated
            it[updatedAt] = now
        }
    }

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }

    private data class Mapping(
        val id: Uuid,
        val connectionId: Uuid,
        val externalId: String,
        val calendarId: CalendarId,
        val syncToken: String?,
    )

    private companion object {
        const val GoogleProviderId = "google"
        const val SyncDirectionPull = "pull"
        const val ReauthMessage = "external connection must be reconnected"
        const val MaxLastErrorLength = 500
    }
}

private val DateOnly = Regex("""^\d{4}-\d{2}-\d{2}$""")

private fun CalendarId.toUuid(): Uuid = Uuid.parse(value)
