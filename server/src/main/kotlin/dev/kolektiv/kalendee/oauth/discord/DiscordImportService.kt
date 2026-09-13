package dev.kolektiv.kalendee.oauth.discord

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.db.CalendarConnectionsTable
import dev.kolektiv.kalendee.db.ExternalCalendarsTable
import dev.kolektiv.kalendee.external.store.ExternalEventRouteStore
import dev.kolektiv.kalendee.external.store.ExternalEventStore
import dev.kolektiv.kalendee.external.store.RouteTarget
import dev.kolektiv.kalendee.external.store.StoredExternalEvent
import dev.kolektiv.kalendee.oauth.ConnectionService
import dev.kolektiv.kalendee.oauth.OAuthReauthRequiredException
import dev.kolektiv.kalendee.oauth.OAuthSettings
import dev.kolektiv.kalendee.oauth.providers.DiscordApi
import dev.kolektiv.kalendee.oauth.providers.DiscordApiException
import dev.kolektiv.kalendee.oauth.providers.DiscordApiHttpException
import dev.kolektiv.kalendee.oauth.providers.DiscordAuthException
import dev.kolektiv.kalendee.oauth.providers.DiscordBotNotConfiguredException
import dev.kolektiv.kalendee.oauth.providers.DiscordGuild
import dev.kolektiv.kalendee.oauth.providers.DiscordScheduledEvent
import io.ktor.http.URLBuilder
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.slf4j.LoggerFactory

data class DiscordGuildSummary(
    val id: String,
    val name: String,
    val icon: String?,
    val owner: Boolean = false,
    val botPresent: Boolean,
    val manageable: Boolean = false,
    val imported: Boolean,
    val enabled: Boolean,
    val externalCalendarId: String?,
    val calendarId: String? = null,
    val lastSyncAt: String? = null,
    val lastError: String? = null,
    val inviteUrl: String?,
)

class DiscordBotNotInGuildException(
    val guildId: String,
    val inviteUrl: String?,
) : RuntimeException("the Kalendee bot is not in Discord guild $guildId")

class DiscordImportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** `external_calendars.sync_direction` values used by Discord imports. */
const val SyncDirectionPull: String = "pull"
const val SyncDirectionBoth: String = "both"

/** One Discord scheduled event with its effective import destination. */
data class DiscordEventRouteSummary(
    val id: String,
    val name: String,
    val start: String,
    val recurring: Boolean,
    val calendarId: String?,
    val skipped: Boolean,
)

/** Setup state for per-event routing of one imported Discord guild. */
data class DiscordSyncSetup(
    val defaultCalendarId: String?,
    val calendars: List<Calendar>,
    val events: List<DiscordEventRouteSummary>,
    val imported: Boolean,
    val enabled: Boolean,
    val lastSyncAt: String?,
    val lastError: String?,
)

/** A requested route for one Discord base event. */
data class DiscordRouteAssignment(
    val eventId: String,
    val calendarId: String?,
    val skipped: Boolean,
)

/**
 * Imports Discord guild scheduled events into Kalendee calendars. One guild
 * source keeps a default target calendar and can route individual base events
 * to other calendars or skip them; occurrences inherit their series' route.
 * Pulls happen on demand and through [syncNow]. When both the connected user
 * and the bot may manage a guild's scheduled events, the import is marked
 * `both` so reschedules can be pushed back through the Discord push service.
 */
class DiscordImportService(
    private val database: Database,
    private val connections: ConnectionService,
    private val store: CalendarStore,
    private val externalEvents: ExternalEventStore,
    private val routeStore: ExternalEventRouteStore,
    private val api: DiscordApi,
    private val settings: OAuthSettings,
    private val clock: Clock = Clock.System,
) {
    private val log = LoggerFactory.getLogger(DiscordImportService::class.java)
    private val syncLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun guilds(userId: UserId, connectionId: String): List<DiscordGuildSummary> {
        val connection = requireConnection(userId, connectionId)
        val token = connections.validAccessToken(userId, connectionId)
        val userGuilds = try {
            api.guilds(token)
        } catch (cause: DiscordAuthException) {
            throw reauthRequired(userId, connectionId, cause)
        }
        val botGuilds = botGuildList().associateBy { it.id }
        val mappings = dbQuery { externalMappings(connection) }
        refreshSyncDirections(connection, userGuilds, mappings, botGuilds)
        val invite = inviteUrl()
        return userGuilds
            .map { guild ->
                summary(
                    guild = guild,
                    botPresent = guild.id in botGuilds,
                    mapping = mappings[guild.id],
                    inviteUrl = invite,
                )
            }
            .filter { it.botPresent || it.manageable || it.imported }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun importGuild(userId: UserId, connectionId: String, guildId: String): DiscordGuildSummary {
        val connection = requireConnection(userId, connectionId)
        if (settings.discord.botToken.isNullOrBlank()) throw DiscordBotNotConfiguredException()
        val invite = inviteUrl()
        val token = connections.validAccessToken(userId, connectionId)
        val userGuilds = try {
            api.guilds(token)
        } catch (cause: DiscordAuthException) {
            throw reauthRequired(userId, connectionId, cause)
        }
        val guild = userGuilds.firstOrNull { it.id == guildId }
            ?: throw CalendarException.NotFound("discord guild not found")
        val botGuild = botGuildList().firstOrNull { it.id == guildId }
            ?: throw DiscordBotNotInGuildException(guildId, invite)
        val existing = dbQuery { mappingRow(connection, guildId) }
        if (existing != null) {
            val externalCalendarId = existing[ExternalCalendarsTable.id].toString()
            syncNow(userId, externalCalendarId)
            val mapping = dbQuery { mappingRow(connection, guildId) } ?: existing
            return summary(guild, botPresent = true, mapping = mapping, inviteUrl = invite)
        }
        val calendar = store.createCalendar(userId, CreateCalendar(displayName = "Discord · ${guild.name}"))
        val externalCalendarId = Uuid.random()
        val now = clock.now()
        dbQuery {
            ExternalCalendarsTable.insert {
                it[id] = externalCalendarId
                it[ExternalCalendarsTable.connectionId] = connection
                it[externalId] = guildId
                it[calendarId] = calendar.id.toUuid()
                it[externalName] = guild.name
                it[syncDirection] = syncDirection(guild, botGuild)
                it[enabled] = true
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        syncNow(userId, externalCalendarId.toString())
        val mapping = dbQuery { mappingRow(connection, guildId) }
            ?: error("discord import mapping disappeared")
        return summary(guild, botPresent = true, mapping = mapping, inviteUrl = invite)
    }

    /**
     * The current summary for one imported guild. Used by the web actions to
     * return fresh state after sync/enable changes without re-listing.
     *
     * This fallback only runs when the guild is absent from the user's guild
     * list, so there is no permission data to judge manageability. It reports
     * `manageable = false` and omits the invite link rather than guessing.
     */
    suspend fun guildSummary(userId: UserId, externalCalendarId: String): DiscordGuildSummary {
        val uuid = Uuid.parseOrNull(externalCalendarId)
            ?: throw CalendarException.Invalid("invalid external calendar id")
        val context = loadSyncContext(userId, uuid)
            ?: throw CalendarException.NotFound("external calendar not found")
        if (context.provider != DiscordProviderId) {
            throw CalendarException.Invalid("connection is not a Discord connection")
        }
        guilds(userId, context.connectionId.toString())
            .firstOrNull { it.externalCalendarId == externalCalendarId }
            ?.let { return it }
        val mapping = dbQuery {
            ExternalCalendarsTable.selectAll()
                .where { ExternalCalendarsTable.id eq uuid }
                .singleOrNull()
        } ?: throw CalendarException.NotFound("external calendar not found")
        return DiscordGuildSummary(
            id = context.guildId,
            name = context.guildName ?: "Discord server",
            icon = null,
            owner = false,
            botPresent = botGuildList().any { it.id == context.guildId },
            manageable = false,
            imported = true,
            enabled = mapping[ExternalCalendarsTable.enabled],
            externalCalendarId = externalCalendarId,
            calendarId = mapping[ExternalCalendarsTable.calendarId].toString(),
            lastSyncAt = mapping[ExternalCalendarsTable.lastSyncAt]?.toString(),
            lastError = mapping[ExternalCalendarsTable.lastError],
            inviteUrl = null,
        )
    }

    /**
     * Routing state for one Discord guild: the current default target calendar,
     * the calendars a user may route to, and every scheduled event with its
     * effective destination.
     */
    suspend fun syncSetup(userId: UserId, connectionId: String, guildId: String): DiscordSyncSetup {
        val connection = requireConnection(userId, connectionId)
        val mapping = dbQuery { mappingRow(connection, guildId) }
        val externalCalendarId = mapping?.get(ExternalCalendarsTable.id)
        val defaultCalendarId = mapping?.get(ExternalCalendarsTable.calendarId)?.toString()
        val configured = externalCalendarId?.let { routeStore.routes(it) }.orEmpty()
        val events = try {
            api.scheduledEvents(guildId)
        } catch (cause: DiscordAuthException) {
            throw reauthRequired(userId, connectionId, cause)
        }.sortedBy { it.startInstant() }
        return DiscordSyncSetup(
            defaultCalendarId = defaultCalendarId,
            calendars = writableCalendars(userId, sourceDefault = mapping?.get(ExternalCalendarsTable.calendarId)),
            events = events.map { event ->
                val route = configured[event.id]
                DiscordEventRouteSummary(
                    id = event.id,
                    name = event.name,
                    start = event.scheduledStartTime,
                    recurring = event.recurrenceRule != null,
                    calendarId = when (route) {
                        is RouteTarget.Calendar -> route.calendarId.value
                        RouteTarget.Skip -> null
                        null -> defaultCalendarId
                    },
                    skipped = route == RouteTarget.Skip,
                )
            },
            imported = mapping != null,
            enabled = mapping?.get(ExternalCalendarsTable.enabled) ?: false,
            lastSyncAt = mapping?.get(ExternalCalendarsTable.lastSyncAt)?.toString(),
            lastError = mapping?.get(ExternalCalendarsTable.lastError),
        )
    }

    /**
     * Persists per-event routes for one Discord guild, bootstrapping the import
     * mapping (and its auto-created default calendar) through [importGuild] when
     * the guild has not been imported yet.
     */
    suspend fun saveSync(
        userId: UserId,
        connectionId: String,
        guildId: String,
        defaultCalendarId: String?,
        routes: List<DiscordRouteAssignment>,
        enabled: Boolean,
    ): DiscordGuildSummary {
        val connection = requireConnection(userId, connectionId)
        val accessible = store.listCalendars(userId)
            .filter { it.permission.canWrite }
            .associateBy { it.id.toUuid() }
        val managed = dbQuery { managedCalendarIds(accessible.keys.toList()) }

        fun requireTargetCalendar(raw: String, allowManaged: Uuid?): Uuid {
            val id = Uuid.parseOrNull(raw) ?: throw CalendarException.Invalid("invalid calendar id")
            if (id !in accessible) throw CalendarException.Forbidden("calendar is not writable")
            if (id in managed && id != allowManaged) {
                throw CalendarException.Forbidden("calendar is managed by an external provider")
            }
            return id
        }

        val existing = dbQuery { mappingRow(connection, guildId) }
        val existingDefault = existing?.get(ExternalCalendarsTable.calendarId)
        val requestedDefault = defaultCalendarId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { requireTargetCalendar(it, allowManaged = existingDefault) }

        val mapping = if (existing == null) {
            importGuild(userId, connectionId, guildId)
            dbQuery { mappingRow(connection, guildId) }
                ?: error("discord import mapping disappeared")
        } else {
            existing
        }
        val externalCalendarId = mapping[ExternalCalendarsTable.id]
        val currentDefault = mapping[ExternalCalendarsTable.calendarId]
        val effectiveDefault = requestedDefault ?: currentDefault
        if (requestedDefault != null && requestedDefault != currentDefault) {
            dbQuery {
                ExternalCalendarsTable.update({ ExternalCalendarsTable.id eq externalCalendarId }) {
                    it[calendarId] = requestedDefault
                    it[updatedAt] = clock.now()
                }
            }
        }

        val routeEntries = LinkedHashMap<String, RouteTarget>()
        routes.forEach { assignment ->
            val eventId = assignment.eventId.trim()
            if (eventId.isEmpty()) throw CalendarException.Invalid("invalid event id")
            val target = if (assignment.skipped) {
                RouteTarget.Skip
            } else {
                val raw = assignment.calendarId?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                RouteTarget.Calendar(
                    CalendarId(requireTargetCalendar(raw, allowManaged = effectiveDefault).toString()),
                )
            }
            routeEntries[eventId] = target
        }
        routeEntries.entries.removeAll { (_, target) ->
            target is RouteTarget.Calendar && target.calendarId.toUuid() == effectiveDefault
        }
        routeStore.replaceRoutes(externalCalendarId, routeEntries)

        setEnabled(userId, externalCalendarId.toString(), enabled)
        syncNow(userId, externalCalendarId.toString())
        return guildSummary(userId, externalCalendarId.toString())
    }

    suspend fun syncNow(userId: UserId, externalCalendarId: String) {
        val uuid = Uuid.parseOrNull(externalCalendarId)
            ?: throw CalendarException.Invalid("invalid external calendar id")
        val lock = syncLocks.computeIfAbsent(externalCalendarId) { Mutex() }
        lock.withLock {
            val context = loadSyncContext(userId, uuid)
                ?: throw CalendarException.NotFound("external calendar not found")
            if (context.provider != DiscordProviderId) {
                throw CalendarException.Invalid("connection is not a Discord connection")
            }
            try {
                runSync(userId, context)
                dbQuery { markSyncSuccess(context, clock.now()) }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: OAuthReauthRequiredException) {
                dbQuery { markSyncFailure(context, cause.message ?: ReauthMessage, clock.now()) }
                throw cause
            } catch (cause: DiscordAuthException) {
                val message = cause.message ?: "discord rejected the access token"
                connections.markNeedsReauth(userId, context.connectionId.toString(), message)
                dbQuery { markSyncFailure(context, message, clock.now()) }
                throw OAuthReauthRequiredException(ReauthMessage)
            } catch (cause: DiscordBotNotConfiguredException) {
                dbQuery { markSyncFailure(context, cause.message ?: "discord bot is not configured", clock.now()) }
                throw cause
            } catch (cause: DiscordApiException) {
                val message = cause.message ?: "discord sync failed"
                dbQuery { markSyncFailure(context, message, clock.now()) }
                throw DiscordImportException("discord sync failed: $message", cause)
            } catch (cause: Exception) {
                val message = cause.message ?: "discord sync failed"
                dbQuery { markSyncFailure(context, message, clock.now()) }
                throw DiscordImportException("discord sync failed: $message", cause)
            }
        }
    }

    suspend fun setEnabled(userId: UserId, externalCalendarId: String, enabled: Boolean) {
        val uuid = Uuid.parseOrNull(externalCalendarId)
            ?: throw CalendarException.Invalid("invalid external calendar id")
        val updated = dbQuery {
            ExternalCalendarsTable.update({
                (ExternalCalendarsTable.id eq uuid) and
                    (ExternalCalendarsTable.connectionId inList ownedConnectionIds(userId))
            }) {
                it[ExternalCalendarsTable.enabled] = enabled
                it[updatedAt] = clock.now()
            }
        }
        if (updated == 0) throw CalendarException.NotFound("external calendar not found")
    }

    /**
     * Removes the import mapping but keeps the local calendar and its events.
     * The mirrored events detach through the `ON DELETE SET NULL` foreign key
     * on `events.external_calendar_id`, so they become regular local events
     * that the user can edit or delete.
     */
    suspend fun removeImport(userId: UserId, externalCalendarId: String) {
        val uuid = Uuid.parseOrNull(externalCalendarId)
            ?: throw CalendarException.Invalid("invalid external calendar id")
        val deleted = dbQuery {
            ExternalCalendarsTable.deleteWhere {
                (ExternalCalendarsTable.id eq uuid) and
                    (ExternalCalendarsTable.connectionId inList ownedConnectionIds(userId))
            }
        }
        if (deleted == 0) throw CalendarException.NotFound("external calendar not found")
    }

    private suspend fun runSync(userId: UserId, context: SyncContext) {
        val token = connections.validAccessToken(userId, context.connectionId.toString())
        val fetched = api.scheduledEvents(context.guildId)
        val now = clock.now()
        val routes = routeStore.routes(context.externalCalendarId)
        val mapped = buildList {
            fetched.forEach { event ->
                val target = when (val route = routes[event.id]) {
                    is RouteTarget.Calendar -> route.calendarId
                    RouteTarget.Skip -> return@forEach
                    null -> context.calendarId
                }
                mapDiscordEvent(event, context.guildName, now).forEach { add(target to it) }
            }
        }
        mapped.forEach { (calendarId, event) ->
            externalEvents.upsert(context.externalCalendarId, calendarId, event)
        }
        val stored = externalEvents.listBySource(context.externalCalendarId)
        val desired = mapped.map { it.second.uid }.toSet()
        deleteStaleOccurrences(context, fetched, routes, stored, desired, now)
        reconcileMissingEvents(context, fetched, stored, now)
        applyExceptions(context, fetched)
    }

    /**
     * Expansion above always rewrites the occurrence rows to their original
     * times, so Discord's overrides are applied afterwards. Rows are matched
     * by the exception id Kalendee stored when it created the exception;
     * exceptions created directly in Discord have no original start in any
     * response and cannot be correlated (zero rows updated).
     */
    private suspend fun applyExceptions(context: SyncContext, fetched: List<DiscordScheduledEvent>) {
        fetched.forEach { event ->
            event.exceptions.forEach { exception ->
                externalEvents.applyException(
                    externalCalendarId = context.externalCalendarId,
                    exceptionId = exception.exceptionId,
                    start = exception.scheduledStartTime?.let { parseInstant(it) },
                    end = exception.scheduledEndTime?.let { parseInstant(it) },
                    cancelled = exception.isCanceled,
                )
            }
        }
    }

    private suspend fun deleteStaleOccurrences(
        context: SyncContext,
        fetched: List<DiscordScheduledEvent>,
        routes: Map<String, RouteTarget>,
        stored: List<StoredExternalEvent>,
        desired: Set<String>,
        now: Instant,
    ) {
        val stale = mutableSetOf<String>()
        fetched.forEach { event ->
            val prefix = discordOccurrenceUidPrefix(context.guildId, event.id)
            val baseUid = discordEventUid(context.guildId, event.id)
            if (routes[event.id] == RouteTarget.Skip) {
                stored.filter { it.uid == baseUid || it.uid.startsWith(prefix) }
                    .forEach { stale += it.uid }
                return@forEach
            }
            val recurring = desired.any { it.startsWith(prefix) }
            // Rows with an exception keep an override that may move them
            // outside the materialization window; never treat them as stale,
            // because the original start encoded in the uid is the only
            // correlation we have.
            stored.filter {
                it.uid.startsWith(prefix) && it.uid !in desired && it.end > now && it.exceptionId == null
            }.forEach { stale += it.uid }
            if (recurring) {
                stored.filter { it.uid == baseUid && it.end > now }.forEach { stale += it.uid }
            }
        }
        if (stale.isNotEmpty()) externalEvents.deleteByUids(context.externalCalendarId, stale)
    }

    private suspend fun reconcileMissingEvents(
        context: SyncContext,
        fetched: List<DiscordScheduledEvent>,
        stored: List<StoredExternalEvent>,
        now: Instant,
    ) {
        val fetchedIds = fetched.map { it.id }.toSet()
        val missingIds = stored.mapNotNull { discordBaseEventId(context.guildId, it.uid) }.toSet() - fetchedIds
        missingIds.forEach { eventId ->
            val baseUid = discordEventUid(context.guildId, eventId)
            val prefix = discordOccurrenceUidPrefix(context.guildId, eventId)
            val rows = stored.filter { it.uid == baseUid || it.uid.startsWith(prefix) }
            if (rows.isEmpty() || rows.all { it.end <= now }) return@forEach
            try {
                val event = api.scheduledEvent(context.guildId, eventId)
                if (event.status == DiscordScheduledEvent.STATUS_CANCELED) {
                    rows.forEach { externalEvents.markCancelled(context.externalCalendarId, it.uid) }
                }
            } catch (cause: DiscordApiHttpException) {
                if (cause.statusCode == HttpStatusCodeNotFound) {
                    externalEvents.deleteByUids(context.externalCalendarId, rows.map { it.uid })
                } else {
                    throw cause
                }
            }
        }
    }

    private suspend fun requireConnection(userId: UserId, connectionId: String): Uuid {
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
        if (provider != DiscordProviderId) {
            throw CalendarException.Invalid("connection is not a Discord connection")
        }
        return uuid
    }

    private suspend fun reauthRequired(
        userId: UserId,
        connectionId: String,
        cause: DiscordAuthException,
    ): OAuthReauthRequiredException {
        val message = cause.message ?: "discord rejected the access token"
        connections.markNeedsReauth(userId, connectionId, message)
        return OAuthReauthRequiredException(ReauthMessage)
    }

    private suspend fun botGuildList(): List<DiscordGuild> = try {
        api.botGuilds()
    } catch (cause: DiscordBotNotConfiguredException) {
        emptyList()
    } catch (cause: DiscordAuthException) {
        log.warn("discord bot token was rejected: {}", cause.message)
        emptyList()
    } catch (cause: DiscordApiHttpException) {
        log.warn("discord bot guild listing failed: {}", cause.message)
        emptyList()
    }

    /**
     * Recomputes push capability for every already-imported mapping from the
     * latest user and bot guild lists. A guild missing from the bot list (or a
     * bot list that could not be fetched) is treated as pull-only.
     */
    private suspend fun refreshSyncDirections(
        connectionId: Uuid,
        userGuilds: List<DiscordGuild>,
        mappings: Map<String, ResultRow>,
        botGuilds: Map<String, DiscordGuild>,
    ) {
        val updates = userGuilds.mapNotNull { guild ->
            val mapping = mappings[guild.id] ?: return@mapNotNull null
            val direction = syncDirection(guild, botGuilds[guild.id])
            if (mapping[ExternalCalendarsTable.syncDirection] == direction) {
                null
            } else {
                mapping[ExternalCalendarsTable.id] to direction
            }
        }
        if (updates.isEmpty()) return
        dbQuery {
            val now = clock.now()
            updates.forEach { (externalCalendarId, direction) ->
                ExternalCalendarsTable.update({ ExternalCalendarsTable.id eq externalCalendarId }) {
                    it[syncDirection] = direction
                    it[updatedAt] = now
                }
            }
        }
    }

    private fun JdbcTransaction.externalMappings(connectionId: Uuid): Map<String, ResultRow> =
        ExternalCalendarsTable.selectAll()
            .where { ExternalCalendarsTable.connectionId eq connectionId }
            .associateBy { it[ExternalCalendarsTable.externalId] }

    private fun JdbcTransaction.mappingRow(connectionId: Uuid, guildId: String): ResultRow? =
        ExternalCalendarsTable.selectAll()
            .where {
                (ExternalCalendarsTable.connectionId eq connectionId) and
                    (ExternalCalendarsTable.externalId eq guildId)
            }
            .singleOrNull()

    private fun JdbcTransaction.ownedConnectionIds(userId: UserId): List<Uuid> =
        CalendarConnectionsTable.selectAll()
            .where { CalendarConnectionsTable.userId eq userId.toUuid() }
            .map { it[CalendarConnectionsTable.id] }

    private fun JdbcTransaction.managedCalendarIds(calendarIds: List<Uuid>): Set<Uuid> {
        if (calendarIds.isEmpty()) return emptySet()
        return ExternalCalendarsTable.selectAll()
            .where { ExternalCalendarsTable.calendarId inList calendarIds }
            .map { it[ExternalCalendarsTable.calendarId] }
            .toSet()
    }

    /**
     * The calendars a user may route Discord events to: writable calendars that
     * are not mirrors of some external provider, plus the source's own default
     * calendar when one exists.
     */
    private suspend fun writableCalendars(userId: UserId, sourceDefault: Uuid?): List<Calendar> {
        val calendars = store.listCalendars(userId).filter { it.permission.canWrite }
        val managed = dbQuery { managedCalendarIds(calendars.map { it.id.toUuid() }) }
        return calendars
            .filter { it.id.toUuid() !in managed || it.id.toUuid() == sourceDefault }
            .sortedBy { it.displayName.lowercase() }
    }

    private fun DiscordScheduledEvent.startInstant(): Instant =
        runCatching { Instant.parse(scheduledStartTime) }.getOrNull() ?: Instant.DISTANT_FUTURE

    private fun parseInstant(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()

    private data class SyncContext(
        val externalCalendarId: Uuid,
        val connectionId: Uuid,
        val provider: String,
        val guildId: String,
        val guildName: String?,
        val calendarId: CalendarId,
    )

    private suspend fun loadSyncContext(userId: UserId, externalCalendarId: Uuid): SyncContext? = dbQuery {
        (ExternalCalendarsTable innerJoin CalendarConnectionsTable)
            .selectAll()
            .where {
                (ExternalCalendarsTable.id eq externalCalendarId) and
                    (CalendarConnectionsTable.userId eq userId.toUuid())
            }
            .singleOrNull()
            ?.let { row ->
                SyncContext(
                    externalCalendarId = row[ExternalCalendarsTable.id],
                    connectionId = row[ExternalCalendarsTable.connectionId],
                    provider = row[CalendarConnectionsTable.provider],
                    guildId = row[ExternalCalendarsTable.externalId],
                    guildName = row[ExternalCalendarsTable.externalName],
                    calendarId = CalendarId(row[ExternalCalendarsTable.calendarId].toString()),
                )
            }
    }

    private fun JdbcTransaction.markSyncSuccess(context: SyncContext, now: Instant) {
        ExternalCalendarsTable.update({ ExternalCalendarsTable.id eq context.externalCalendarId }) {
            it[lastSyncAt] = now
            it[lastError] = null
            it[updatedAt] = now
        }
        CalendarConnectionsTable.update({ CalendarConnectionsTable.id eq context.connectionId }) {
            it[lastSyncAt] = now
            it[lastError] = null
        }
    }

    private fun JdbcTransaction.markSyncFailure(context: SyncContext, message: String, now: Instant) {
        val truncated = message.take(MaxLastErrorLength)
        ExternalCalendarsTable.update({ ExternalCalendarsTable.id eq context.externalCalendarId }) {
            it[lastError] = truncated
            it[updatedAt] = now
        }
        CalendarConnectionsTable.update({ CalendarConnectionsTable.id eq context.connectionId }) {
            it[lastError] = truncated
        }
    }

    private fun summary(
        guild: DiscordGuild,
        botPresent: Boolean,
        mapping: ResultRow?,
        inviteUrl: String?,
    ): DiscordGuildSummary {
        val manageable = guild.owner || hasManageGuildPermission(guild.permissions)
        return DiscordGuildSummary(
            id = guild.id,
            name = guild.name,
            icon = guild.icon,
            owner = guild.owner,
            botPresent = botPresent,
            manageable = manageable,
            imported = mapping != null,
            enabled = mapping?.get(ExternalCalendarsTable.enabled) ?: false,
            externalCalendarId = mapping?.get(ExternalCalendarsTable.id)?.toString(),
            calendarId = mapping?.get(ExternalCalendarsTable.calendarId)?.toString(),
            lastSyncAt = mapping?.get(ExternalCalendarsTable.lastSyncAt)?.toString(),
            lastError = mapping?.get(ExternalCalendarsTable.lastError),
            inviteUrl = inviteUrl.takeIf { !botPresent && manageable },
        )
    }

    /**
     * Events can be pushed back to Discord only when both the connected user
     * and the Kalendee bot may manage the guild's scheduled events.
     */
    private fun syncDirection(userGuild: DiscordGuild, botGuild: DiscordGuild?): String =
        if (botGuild != null && canManageEvents(userGuild) && canManageEvents(botGuild)) {
            SyncDirectionBoth
        } else {
            SyncDirectionPull
        }

    private fun canManageEvents(guild: DiscordGuild): Boolean =
        guild.owner ||
            hasPermission(guild.permissions, ManageEventsPermissionBit) ||
            hasPermission(guild.permissions, AdministratorPermissionBit)

    /**
     * Parses the decimal permissions bitfield from `/users/@me/guilds`. Discord
     * sends an unsigned 64-bit value, so use [BigInteger] rather than a signed
     * `Long` and treat anything unparseable as "no permission".
     */
    private fun hasPermission(permissions: String?, bit: Int): Boolean {
        val raw = permissions?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return try {
            BigInteger(raw).testBit(bit)
        } catch (_: NumberFormatException) {
            false
        }
    }

    private fun hasManageGuildPermission(permissions: String?): Boolean =
        hasPermission(permissions, ManageGuildPermissionBit)

    private fun inviteUrl(): String? {
        val clientId = settings.discord.clientId.takeIf { it.isNotBlank() } ?: return null
        if (settings.discord.botToken.isNullOrBlank()) return null
        return URLBuilder(DiscordInviteUrl).apply {
            parameters.append("client_id", clientId)
            parameters.append("scope", "bot")
            parameters.append("permissions", DiscordInvitePermissions)
        }.buildString()
    }

    private suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, statement = block)
        }

    private companion object {
        const val DiscordProviderId = "discord"
        const val ReauthMessage = "external connection must be reconnected"
        const val MaxLastErrorLength = 500
        const val HttpStatusCodeNotFound = 404
        const val ManageGuildPermissionBit = 5
        const val ManageEventsPermissionBit = 33
        const val AdministratorPermissionBit = 3
        const val DiscordInviteUrl = "https://discord.com/oauth2/authorize"
        const val DiscordInvitePermissions = "1024"
    }
}

private fun CalendarId.toUuid(): Uuid = Uuid.parse(value)
