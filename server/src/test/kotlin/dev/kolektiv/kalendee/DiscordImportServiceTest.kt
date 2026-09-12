package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.calendar.UpdateEvent
import dev.kolektiv.kalendee.db.EventsTable
import dev.kolektiv.kalendee.db.ExternalCalendarsTable
import dev.kolektiv.kalendee.external.store.ExternalEventStore
import dev.kolektiv.kalendee.oauth.ConnectionService
import dev.kolektiv.kalendee.oauth.OAuthCallbackOutcome
import dev.kolektiv.kalendee.oauth.OAuthReauthRequiredException
import dev.kolektiv.kalendee.oauth.OAuthStateService
import dev.kolektiv.kalendee.oauth.Pkce
import dev.kolektiv.kalendee.oauth.discord.DiscordBotNotInGuildException
import dev.kolektiv.kalendee.oauth.discord.DiscordImportException
import dev.kolektiv.kalendee.oauth.discord.DiscordImportService
import dev.kolektiv.kalendee.oauth.providers.DiscordBotNotConfiguredException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.ktor.ext.get

class DiscordImportServiceTest {
    @Test
    fun importGuildCreatesCalendarMappingAndRows() = testApplication {
        val start = Clock.System.now() + 2.days
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok("[${eventJson(start = start)}]") }))

        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "guild-1")

        assertTrue(summary.imported)
        assertTrue(summary.enabled)
        assertTrue(summary.botPresent)
        assertNotNull(summary.externalCalendarId)
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)
        assertEquals("Discord · Kolektiv", calendar.displayName)
        val stored = fixture.external.listExternal(calendar.id).single()
        assertEquals("discord:guild-1:event-1", stored.uid)
        val event = fixture.store.listEvents(calendar.id, fixture.user.id).single()
        assertEquals("Community call", event.title)
        val row = fixture.externalRow(externalCalendarId)
        assertEquals("guild-1", row[ExternalCalendarsTable.externalId])
        assertEquals("pull", row[ExternalCalendarsTable.syncDirection])
        assertTrue(row[ExternalCalendarsTable.enabled])
        assertNotNull(row[ExternalCalendarsTable.lastSyncAt])
        assertNull(row[ExternalCalendarsTable.lastError])
    }

    @Test
    fun guildListingReportsBotPresenceAndImportState() = testApplication {
        val start = Clock.System.now() + 2.days
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok("[${eventJson(start = start)}]") }))

        val before = fixture.imports.guilds(fixture.user.id, fixture.connectionId).single()
        assertEquals("guild-1", before.id)
        assertTrue(before.botPresent)
        assertFalse(before.imported)
        assertFalse(before.enabled)
        assertNull(before.externalCalendarId)
        val invite = assertNotNull(before.inviteUrl)
        assertEquals("discord-client", Url(invite).parameters["client_id"])

        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "guild-1")
        val after = fixture.imports.guilds(fixture.user.id, fixture.connectionId).single()
        assertTrue(after.imported)
        assertTrue(after.enabled)
        assertEquals(summary.externalCalendarId, after.externalCalendarId)
    }

    @Test
    fun guildListingWithoutBotTokenStillListsGuildsWithNullInvite() = testApplication {
        val fixture = installImportFixture(discordEngine(), botToken = null)

        val guild = fixture.imports.guilds(fixture.user.id, fixture.connectionId).single()

        assertFalse(guild.botPresent)
        assertFalse(guild.imported)
        assertNull(guild.inviteUrl)
    }

    @Test
    fun secondSyncIsIdempotent() = testApplication {
        val start = Clock.System.now() + 2.days
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok("[${eventJson(start = start)}]") }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "guild-1")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)
        val before = fixture.external.listExternal(calendar.id)

        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        assertEquals(before, fixture.external.listExternal(calendar.id))
        assertEquals(1, fixture.eventCount(externalCalendarId))
    }

    @Test
    fun syncPropagatesEventUpdates() = testApplication {
        val start = Clock.System.now() + 2.days
        var body = "[${eventJson(start = start, name = "First")}]"
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok(body) }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "guild-1")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)

        body = "[${eventJson(start = start, name = "Second")}]"
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        assertEquals("Second", fixture.store.listEvents(calendar.id, fixture.user.id).single().title)
        assertEquals(1, fixture.eventCount(externalCalendarId))
    }

    @Test
    fun missingFutureEventWith404RemovesStoredRows() = testApplication {
        val start = Clock.System.now() + 2.days
        var list = "[${eventJson(start = start)}]"
        val fixture = installImportFixture(
            discordEngine(
                scheduledEvents = { ok(list) },
                scheduledEvent = { HttpStatusCode.NotFound to "{}" },
            ),
        )
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "guild-1")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)
        assertEquals(1, fixture.external.listExternal(calendar.id).size)

        list = "[]"
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        assertTrue(fixture.external.listExternal(calendar.id).isEmpty())
        assertEquals(0, fixture.eventCount(externalCalendarId))
    }

    @Test
    fun missingPastEventWithout404LookupIsKeptAsHistory() = testApplication {
        val pastStart = Clock.System.now() - 5.days
        var list = "[${eventJson(start = pastStart)}]"
        val fixture = installImportFixture(
            discordEngine(
                scheduledEvents = { ok(list) },
                scheduledEvent = { throw AssertionError("past events must not be re-fetched") },
            ),
        )
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "guild-1")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)

        list = "[]"
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        assertEquals(1, fixture.external.listExternal(calendar.id).size)
        assertEquals(1, fixture.store.listEvents(calendar.id, fixture.user.id).size)
    }

    @Test
    fun canceledEventMarksStoredRowsCancelled() = testApplication {
        val start = Clock.System.now() + 2.days
        var list = "[${eventJson(start = start)}]"
        val fixture = installImportFixture(
            discordEngine(
                scheduledEvents = { ok(list) },
                scheduledEvent = { ok(eventJson(start = start, status = 4)) },
            ),
        )
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "guild-1")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)

        list = "[]"
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        assertEquals(EventStatus.CANCELLED, fixture.external.listExternal(calendar.id).single().status)
        assertEquals(EventStatus.CANCELLED, fixture.store.listEvents(calendar.id, fixture.user.id).single().status)
    }

    @Test
    fun ruleChangeRemovesStaleOccurrences() = testApplication {
        val start = Clock.System.now() + 1.days
        val weekday = start.toLocalDateTime(TimeZone.UTC).dayOfWeek.isoDayNumber - 1
        fun recurring(day: Int) =
            "[${eventJson(start = start, rule = """{"start":"$start","frequency":2,"interval":1,"by_weekday":[$day]}""")}]"
        var body = recurring(weekday)
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok(body) }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "guild-1")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)
        val oldUids = fixture.external.listExternal(calendar.id).map { it.uid }
        assertTrue(oldUids.size > 1)
        assertTrue(oldUids.all { "discord:guild-1:event-1:" in it })

        body = recurring((weekday + 1) % 7)
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        val newUids = fixture.external.listExternal(calendar.id).map { it.uid }
        assertTrue(newUids.isNotEmpty())
        assertTrue(oldUids.none { it in newUids })
    }

    @Test
    fun importWithoutBotTokenFails() = testApplication {
        val fixture = installImportFixture(discordEngine(), botToken = null)

        assertFailsWith<DiscordBotNotConfiguredException> {
            fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "guild-1")
        }
        assertTrue(fixture.externalRowsForConnection().isEmpty())
    }

    @Test
    fun importWhenBotIsNotInGuildFailsWithInvite() = testApplication {
        val fixture = installImportFixture(discordEngine(botGuilds = { ok("[]") }))

        val failure = assertFailsWith<DiscordBotNotInGuildException> {
            fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "guild-1")
        }

        assertEquals("guild-1", failure.guildId)
        val url = Url(assertNotNull(failure.inviteUrl))
        assertEquals("discord-client", url.parameters["client_id"])
        assertEquals("bot", url.parameters["scope"])
        assertEquals("1024", url.parameters["permissions"])
        assertTrue(fixture.externalRowsForConnection().isEmpty())
    }

    @Test
    fun guildListing401MarksConnectionNeedsReauth() = testApplication {
        val fixture = installImportFixture(
            discordEngine(userGuilds = { HttpStatusCode.Unauthorized to """{"message":"401"}""" }),
        )

        assertFailsWith<OAuthReauthRequiredException> {
            fixture.imports.guilds(fixture.user.id, fixture.connectionId)
        }

        assertEquals("needs_reauth", fixture.connections.connections(fixture.user.id).single().status)
    }

    @Test
    fun concurrentSyncsDoNotDuplicateRows() = testApplication {
        val start = Clock.System.now() + 2.days
        val body = "[${eventJson(id = "event-1", start = start)},${eventJson(id = "event-2", start = start)}]"
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok(body) }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "guild-1")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)

        coroutineScope {
            listOf(
                async { fixture.imports.syncNow(fixture.user.id, externalCalendarId) },
                async { fixture.imports.syncNow(fixture.user.id, externalCalendarId) },
            ).awaitAll()
        }

        assertEquals(2, fixture.external.listExternal(calendar.id).size)
        assertEquals(2, fixture.eventCount(externalCalendarId))
    }

    @Test
    fun removeImportDetachesEventsAndKeepsTheCalendarEditable() = testApplication {
        val start = Clock.System.now() + 2.days
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok("[${eventJson(start = start)}]") }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "guild-1")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)

        fixture.imports.setEnabled(fixture.user.id, externalCalendarId, enabled = false)
        assertFalse(fixture.externalRow(externalCalendarId)[ExternalCalendarsTable.enabled])

        fixture.imports.removeImport(fixture.user.id, externalCalendarId)

        assertTrue(fixture.externalRowsForConnection().isEmpty())
        assertNotNull(fixture.store.getCalendar(calendar.id, fixture.user.id))
        val detached = fixture.store.listEvents(calendar.id, fixture.user.id).single()
        assertNull(fixture.rawEvent(detached.id)[EventsTable.externalCalendarId])
        fixture.store.updateEvent(detached.id, fixture.user.id, UpdateEvent(title = "Edited"))
        assertEquals("Edited", fixture.store.getEvent(detached.id, fixture.user.id)?.title)
    }

    @Test
    fun syncFailureRecordsLastErrorOnBothRows() = testApplication {
        val fixture = installImportFixture(
            discordEngine(scheduledEvents = { HttpStatusCode.InternalServerError to "{}" }),
        )

        assertFailsWith<DiscordImportException> {
            fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "guild-1")
        }

        val row = fixture.externalRowsForConnection().single()
        assertNotNull(row[ExternalCalendarsTable.lastError])
        assertNotNull(fixture.connections.connections(fixture.user.id).single().lastError)
    }

    private class ImportFixture(
        val connections: ConnectionService,
        val imports: DiscordImportService,
        val store: CalendarStore,
        val external: ExternalEventStore,
        val database: Database,
        val user: User,
        val connectionId: String,
    ) {
        suspend fun calendarFor(externalCalendarId: String): Calendar {
            val calendarId = withContext(Dispatchers.IO) {
                suspendTransaction(database) {
                    ExternalCalendarsTable.selectAll()
                        .where { ExternalCalendarsTable.id eq Uuid.parse(externalCalendarId) }
                        .single()[ExternalCalendarsTable.calendarId]
                }
            }
            return requireNotNull(store.getCalendar(CalendarId(calendarId.toString()), user.id))
        }

        suspend fun externalRow(externalCalendarId: String): ResultRow = withContext(Dispatchers.IO) {
            suspendTransaction(database) {
                ExternalCalendarsTable.selectAll()
                    .where { ExternalCalendarsTable.id eq Uuid.parse(externalCalendarId) }
                    .single()
            }
        }

        suspend fun externalRowsForConnection(): List<ResultRow> = withContext(Dispatchers.IO) {
            suspendTransaction(database) {
                ExternalCalendarsTable.selectAll()
                    .where { ExternalCalendarsTable.connectionId eq Uuid.parse(connectionId) }
                    .toList()
            }
        }

        suspend fun eventCount(externalCalendarId: String): Long = withContext(Dispatchers.IO) {
            suspendTransaction(database) {
                EventsTable.selectAll()
                    .where { EventsTable.externalCalendarId eq Uuid.parse(externalCalendarId) }
                    .count()
            }
        }

        suspend fun rawEvent(eventId: EventId): ResultRow = withContext(Dispatchers.IO) {
            suspendTransaction(database) {
                EventsTable.selectAll()
                    .where { EventsTable.id eq Uuid.parse(eventId.value) }
                    .single()
            }
        }
    }

    private suspend fun ApplicationTestBuilder.installImportFixture(
        engine: MockEngine,
        botToken: String? = "bot-token",
    ): ImportFixture {
        lateinit var auth: AuthService
        lateinit var states: OAuthStateService
        lateinit var connections: ConnectionService
        lateinit var imports: DiscordImportService
        lateinit var store: CalendarStore
        lateinit var external: ExternalEventStore
        lateinit var database: Database
        installApi(
            httpClient = HttpClient(engine),
            extraConfig = discordTestConfig(botToken),
            configure = {
                auth = get()
                states = get()
                connections = get()
                imports = get()
                store = get()
                external = get()
                database = get()
            },
        )
        startApplication()
        val user = auth.register(RegisterUser(username = "mey", password = "password12"))
            .session?.user
            ?: error("registration did not create a session")
        val state = states.create(
            userId = user.id,
            provider = "discord",
            pkce = Pkce.generate(),
            redirectUri = "https://kalendee.test/api/v1/oauth/discord/callback",
            returnTo = null,
        )
        val outcome = connections.handleCallback("discord", "mock-code", state, user.id)
        val connectionId = (outcome as OAuthCallbackOutcome.Connected).connectionId
        return ImportFixture(
            connections = connections,
            imports = imports,
            store = store,
            external = external,
            database = database,
            user = user,
            connectionId = connectionId,
        )
    }

    private fun discordEngine(
        userGuilds: () -> Pair<HttpStatusCode, String> = { ok(UserGuildsJson) },
        botGuilds: () -> Pair<HttpStatusCode, String> = { ok(UserGuildsJson) },
        scheduledEvents: () -> Pair<HttpStatusCode, String> = { ok("[]") },
        scheduledEvent: (String) -> Pair<HttpStatusCode, String> = { HttpStatusCode.NotFound to "{}" },
    ): MockEngine = MockEngine { request ->
        val authorization = request.headers[HttpHeaders.Authorization].orEmpty()
        val path = request.url.encodedPath
        val response = when {
            request.method == HttpMethod.Post && path.endsWith("/oauth2/token") -> ok(TokenJson)
            request.method == HttpMethod.Post && path.endsWith("/oauth2/token/revoke") -> ok("{}")
            path.endsWith("/users/@me") && authorization.startsWith("Bearer") -> ok(IdentityJson)
            path.endsWith("/users/@me/guilds") && authorization.startsWith("Bearer") -> userGuilds()
            path.endsWith("/users/@me/guilds") && authorization.startsWith("Bot") -> botGuilds()
            path.contains("/scheduled-events/") -> scheduledEvent(path.substringAfterLast('/'))
            path.endsWith("/scheduled-events") -> scheduledEvents()
            else -> HttpStatusCode.NotFound to "{}"
        }
        respond(
            content = response.second,
            status = response.first,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun eventJson(
        id: String = "event-1",
        name: String = "Community call",
        start: Instant,
        status: Int = 1,
        rule: String? = null,
    ): String = buildString {
        append("""{"id":"$id","guild_id":"guild-1","channel_id":null,"name":"$name",""")
        append(""""description":"Hello","scheduled_start_time":"$start","scheduled_end_time":"${start + 1.hours}",""")
        append(""""privacy_level":2,"status":$status,"entity_type":3,"entity_metadata":{"location":"Lounge"},""")
        append(""""user_count":3""")
        rule?.let { append(""","recurrence_rule":$it""") }
        append('}')
    }

    private fun discordTestConfig(botToken: String?): Map<String, String> = mapOf(
        "oauth.discord.clientId" to "discord-client",
        "oauth.discord.clientSecret" to "discord-secret",
        "oauth.discord.botToken" to (botToken ?: ""),
        "oauth.secretKey" to discordTestSecretKey,
    )

    private fun ok(body: String): Pair<HttpStatusCode, String> = HttpStatusCode.OK to body

    private companion object {
        val UserGuildsJson = """[{"id":"guild-1","name":"Kolektiv","icon":"icon-hash"}]"""
        val IdentityJson = """{"id":"discord-1","username":"mey","global_name":"Mey"}"""
        val TokenJson =
            """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":604800,""" +
                """"scope":"identify guilds","token_type":"Bearer"}"""
        val discordTestSecretKey: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    }
}
