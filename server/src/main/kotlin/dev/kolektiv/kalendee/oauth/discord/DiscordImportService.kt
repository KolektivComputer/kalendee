package dev.kolektiv.kalendee.oauth.discord

import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.auth.toUuid
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.db.CalendarConnectionsTable
import dev.kolektiv.kalendee.db.ExternalCalendarsTable
import dev.kolektiv.kalendee.external.store.ExternalEventStore
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

/**
 * Imports Discord guild scheduled events into a mirrored, read-only Kalendee
 * calendar. Pulls happen on demand and through [syncNow]; nothing is ever
 * pushed back to Discord.
 */
class DiscordImportService(
    private val database: Database,
    private val connections: ConnectionService,
    private val store: CalendarStore,
    private val externalEvents: ExternalEventStore,
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
        val botGuilds = botGuildIds()
        val mappings = dbQuery { externalMappings(connection) }
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
        if (guildId !in botGuildIds()) {
            throw DiscordBotNotInGuildException(guildId, invite)
        }
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
                it[syncDirection] = SyncDirectionPull
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
            botPresent = context.guildId in botGuildIds(),
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
        val mapped = fetched.flatMap { event -> mapDiscordEvent(event, context.guildName, now) }
        mapped.forEach { event -> externalEvents.upsert(context.calendarId, event) }
        val stored = externalEvents.listExternal(context.calendarId)
        val desired = mapped.map { it.uid }.toSet()
        deleteStaleOccurrences(context, fetched, stored, desired, now)
        reconcileMissingEvents(context, fetched, stored, now)
    }

    private suspend fun deleteStaleOccurrences(
        context: SyncContext,
        fetched: List<DiscordScheduledEvent>,
        stored: List<StoredExternalEvent>,
        desired: Set<String>,
        now: Instant,
    ) {
        val stale = mutableSetOf<String>()
        fetched.forEach { event ->
            val prefix = discordOccurrenceUidPrefix(context.guildId, event.id)
            val recurring = desired.any { it.startsWith(prefix) }
            stored.filter { it.uid.startsWith(prefix) && it.uid !in desired && it.end > now }
                .forEach { stale += it.uid }
            if (recurring) {
                val baseUid = discordEventUid(context.guildId, event.id)
                stored.filter { it.uid == baseUid && it.end > now }.forEach { stale += it.uid }
            }
        }
        if (stale.isNotEmpty()) externalEvents.deleteByUids(context.calendarId, stale)
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
                    rows.forEach { externalEvents.markCancelled(context.calendarId, it.uid) }
                }
            } catch (cause: DiscordApiHttpException) {
                if (cause.statusCode == HttpStatusCodeNotFound) {
                    externalEvents.deleteByUids(context.calendarId, rows.map { it.uid })
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

    private suspend fun botGuildIds(): Set<String> = try {
        api.botGuilds().map { it.id }.toSet()
    } catch (cause: DiscordBotNotConfiguredException) {
        emptySet()
    } catch (cause: DiscordAuthException) {
        log.warn("discord bot token was rejected: {}", cause.message)
        emptySet()
    } catch (cause: DiscordApiHttpException) {
        log.warn("discord bot guild listing failed: {}", cause.message)
        emptySet()
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
     * Parses the decimal permissions bitfield from `/users/@me/guilds`. Discord
     * sends an unsigned 64-bit value, so use [BigInteger] rather than a signed
     * `Long` and treat anything unparseable as "no permission".
     */
    private fun hasManageGuildPermission(permissions: String?): Boolean {
        val raw = permissions?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return try {
            BigInteger(raw).testBit(ManageGuildPermissionBit)
        } catch (_: NumberFormatException) {
            false
        }
    }

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
        const val SyncDirectionPull = "pull"
        const val ReauthMessage = "external connection must be reconnected"
        const val MaxLastErrorLength = 500
        const val HttpStatusCodeNotFound = 404
        const val ManageGuildPermissionBit = 5
        const val DiscordInviteUrl = "https://discord.com/oauth2/authorize"
        const val DiscordInvitePermissions = "1024"
    }
}

private fun CalendarId.toUuid(): Uuid = Uuid.parse(value)
