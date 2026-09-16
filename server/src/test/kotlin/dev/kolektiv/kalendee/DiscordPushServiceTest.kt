package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.calendar.OptionalField
import dev.kolektiv.kalendee.calendar.UpdateEvent
import dev.kolektiv.kalendee.db.CalendarConnectionsTable
import dev.kolektiv.kalendee.db.EventsTable
import dev.kolektiv.kalendee.db.ExternalCalendarsTable
import dev.kolektiv.kalendee.events.EventUpdateService
import dev.kolektiv.kalendee.external.store.ExternalEventStore
import dev.kolektiv.kalendee.oauth.ConnectionService
import dev.kolektiv.kalendee.oauth.OAuthCallbackOutcome
import dev.kolektiv.kalendee.oauth.OAuthStateService
import dev.kolektiv.kalendee.oauth.Pkce
import dev.kolektiv.kalendee.oauth.discord.DiscordImportService
import dev.kolektiv.kalendee.oauth.discord.ImportedCalendarEvent
import dev.kolektiv.kalendee.oauth.providers.DiscordAuthException
import dev.kolektiv.kalendee.web.HomePage
import dev.kolektiv.keel.KeelJson
import dev.kolektiv.keel.seed.KeelSeed
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.ktor.ext.get

class DiscordPushServiceTest {
    @Test
    fun reschedulePatchesDiscordAndUpdatesLocalRow() = testApplication {
        val start = secondsFromNow(2.days)
        val newStart = start + 3.hours
        val newEnd = newStart + 1.hours
        val mock = MockDiscord(
            scheduledEvents = { ok("[${eventJson(start = start)}]") },
            modifyEvent = { ok(eventJson(start = newStart, end = newEnd)) },
        )
        val fixture = installPushFixture(mock)
        val imported = fixture.importInto()
        val event = fixture.store.listEvents(imported.calendar.id, fixture.user.id).single()

        val updated = fixture.updates.update(
            event.id,
            fixture.user.id,
            UpdateEvent(start = newStart, end = newEnd),
            expectedEtag = event.etag,
        )

        assertEquals(newStart, updated.start)
        assertEquals(newEnd, updated.end)
        assertNotEquals(event.etag, updated.etag)
        val stored = fixture.store.getEvent(event.id, fixture.user.id)
        assertEquals(newStart, stored?.start)
        assertEquals(newEnd, stored?.end)
        assertEquals(updated.etag, stored?.etag)

        val patch = mock.patches.single()
        assertEquals(HttpMethod.Patch, patch.method)
        assertEquals("/api/v10/guilds/101/scheduled-events/201", patch.url.encodedPath)
        assertEquals("Bot bot-token", patch.headers[HttpHeaders.Authorization])
        val body = (patch.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
        assertTrue("\"scheduled_start_time\":\"$newStart\"" in body, body)
        assertTrue("\"scheduled_end_time\":\"$newEnd\"" in body, body)
    }

    @Test
    fun pullOnlyImportRejectsRescheduleWithoutDiscordCall() = testApplication {
        val start = secondsFromNow(2.days)
        val newStart = start + 3.hours
        val mock = MockDiscord(
            botGuilds = { ok("[${guildJson("101", permissions = "1024")}]") },
            scheduledEvents = { ok("[${eventJson(start = start)}]") },
        )
        val fixture = installPushFixture(mock)
        val imported = fixture.importInto()
        val event = fixture.store.listEvents(imported.calendar.id, fixture.user.id).single()

        val failure = assertFailsWith<CalendarException.Forbidden> {
            fixture.updates.update(
                event.id,
                fixture.user.id,
                UpdateEvent(start = newStart, end = newStart + 1.hours),
                expectedEtag = event.etag,
            )
        }

        assertTrue("two-way sync" in failure.message.orEmpty(), failure.message)
        assertTrue(mock.patches.isEmpty())
        assertEquals(start, fixture.store.getEvent(event.id, fixture.user.id)?.start)
    }

    @Test
    fun reschedulingOccurrenceCreatesDiscordExceptionAndUpdatesLocalRow() = testApplication {
        val start = secondsFromNow(1.days)
        val weekday = start.toLocalDateTime(TimeZone.UTC).dayOfWeek.isoDayNumber - 1
        val rule = """{"start":"$start","frequency":2,"interval":1,"by_weekday":[$weekday]}"""
        val newStart = start + 3.hours
        val newEnd = newStart + 1.hours
        val mock = MockDiscord(
            scheduledEvents = { ok("[${eventJson(start = start, rule = rule)}]") },
            createException = { ok(exceptionJson(start = newStart, end = newEnd)) },
        )
        val fixture = installPushFixture(mock)
        val imported = fixture.importInto()
        val occurrence = fixture.store.listEvents(imported.calendar.id, fixture.user.id)
            .first { it.externalUid?.startsWith("discord:101:201:") == true }
        assertEquals(start, occurrence.start)

        val updated = fixture.updates.update(
            occurrence.id,
            fixture.user.id,
            UpdateEvent(start = newStart, end = newEnd),
            expectedEtag = occurrence.etag,
        )

        assertEquals(newStart, updated.start)
        assertEquals(newEnd, updated.end)
        val request = mock.exceptionPosts.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v10/guilds/101/scheduled-events/201/exceptions", request.url.encodedPath)
        assertEquals("Bot bot-token", request.headers[HttpHeaders.Authorization])
        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
        assertTrue("\"original_scheduled_start_time\":\"$start\"" in body, body)
        assertTrue("\"scheduled_start_time\":\"$newStart\"" in body, body)
        assertTrue("\"scheduled_end_time\":\"$newEnd\"" in body, body)
        assertEquals("901", fixture.exceptionId(occurrence.id))
        assertTrue(mock.patches.isEmpty())
    }

    @Test
    fun reschedulingOccurrenceAgainPatchesTheSameException() = testApplication {
        val start = secondsFromNow(1.days)
        val weekday = start.toLocalDateTime(TimeZone.UTC).dayOfWeek.isoDayNumber - 1
        val rule = """{"start":"$start","frequency":2,"interval":1,"by_weekday":[$weekday]}"""
        val firstStart = start + 3.hours
        val firstEnd = firstStart + 1.hours
        val secondStart = start + 5.hours
        val secondEnd = secondStart + 1.hours
        val mock = MockDiscord(
            scheduledEvents = { ok("[${eventJson(start = start, rule = rule)}]") },
            createException = { ok(exceptionJson(start = firstStart, end = firstEnd)) },
            modifyException = { ok(exceptionJson(start = secondStart, end = secondEnd)) },
        )
        val fixture = installPushFixture(mock)
        val imported = fixture.importInto()
        val occurrence = fixture.store.listEvents(imported.calendar.id, fixture.user.id)
            .first { it.externalUid?.startsWith("discord:101:201:") == true }

        fixture.updates.update(
            occurrence.id,
            fixture.user.id,
            UpdateEvent(start = firstStart, end = firstEnd),
            expectedEtag = occurrence.etag,
        )
        val refreshed = requireNotNull(fixture.store.getEvent(occurrence.id, fixture.user.id))
        val updated = fixture.updates.update(
            occurrence.id,
            fixture.user.id,
            UpdateEvent(start = secondStart, end = secondEnd),
            expectedEtag = refreshed.etag,
        )

        assertEquals(secondStart, updated.start)
        assertEquals(secondEnd, updated.end)
        assertEquals(1, mock.exceptionPosts.size)
        val patch = mock.exceptionPatches.single()
        assertEquals(HttpMethod.Patch, patch.method)
        assertEquals(
            "/api/v10/guilds/101/scheduled-events/201/exceptions/901",
            patch.url.encodedPath,
        )
        val body = (patch.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
        assertTrue("\"scheduled_start_time\":\"$secondStart\"" in body, body)
        assertTrue("\"scheduled_end_time\":\"$secondEnd\"" in body, body)
        assertTrue("\"original_scheduled_start_time\"" !in body, body)
        assertEquals("901", fixture.exceptionId(occurrence.id))
    }

    @Test
    fun disabledImportRejectsOccurrenceReschedule() = testApplication {
        val start = secondsFromNow(1.days)
        val weekday = start.toLocalDateTime(TimeZone.UTC).dayOfWeek.isoDayNumber - 1
        val rule = """{"start":"$start","frequency":2,"interval":1,"by_weekday":[$weekday]}"""
        val mock = MockDiscord(scheduledEvents = { ok("[${eventJson(start = start, rule = rule)}]") })
        val fixture = installPushFixture(mock)
        val imported = fixture.importInto()
        fixture.imports.setEnabled(fixture.user.id, imported.externalCalendarId, enabled = false)
        val occurrence = fixture.store.listEvents(imported.calendar.id, fixture.user.id)
            .first { it.externalUid?.startsWith("discord:101:201:") == true }

        val failure = assertFailsWith<CalendarException.Forbidden> {
            fixture.updates.update(
                occurrence.id,
                fixture.user.id,
                UpdateEvent(start = occurrence.start + 1.hours, end = occurrence.end + 1.hours),
                expectedEtag = occurrence.etag,
            )
        }

        assertTrue("disabled" in failure.message.orEmpty(), failure.message)
        assertTrue(mock.exceptionPosts.isEmpty())
        assertTrue(mock.patches.isEmpty())
    }

    @Test
    fun writerCanRescheduleImportedEvent() = testApplication {
        val start = secondsFromNow(2.days)
        val newStart = start + 1.hours
        val newEnd = start + 2.hours
        val mock = MockDiscord(
            scheduledEvents = { ok("[${eventJson(start = start)}]") },
            modifyEvent = { ok(eventJson(start = newStart, end = newEnd)) },
        )
        val fixture = installPushFixture(mock)
        val imported = fixture.importInto()
        val other = fixture.auth.register(RegisterUser(username = "bob", password = "password12"))
            .session?.user
            ?: error("registration did not create a session")
        fixture.store.addShare(
            imported.calendar.id,
            fixture.user.id,
            other.id,
            CalendarPermission.WRITE,
        )
        val event = fixture.store.listEvents(imported.calendar.id, other.id).single()

        val updated = fixture.updates.update(
            event.id,
            other.id,
            UpdateEvent(start = newStart, end = newEnd),
            expectedEtag = event.etag,
        )

        assertEquals(newStart, updated.start)
        assertEquals(newEnd, updated.end)
        assertEquals(1, mock.patches.size)
        assertEquals(newStart, fixture.store.getEvent(event.id, other.id)?.start)
    }

    @Test
    fun readerCannotRescheduleImportedEvent() = testApplication {
        val start = secondsFromNow(2.days)
        val mock = MockDiscord(scheduledEvents = { ok("[${eventJson(start = start)}]") })
        val fixture = installPushFixture(mock)
        val imported = fixture.importInto()
        val other = fixture.auth.register(RegisterUser(username = "bob", password = "password12"))
            .session?.user
            ?: error("registration did not create a session")
        fixture.store.addShare(
            imported.calendar.id,
            fixture.user.id,
            other.id,
            CalendarPermission.READ,
        )
        val event = fixture.store.listEvents(imported.calendar.id, other.id).single()

        val failure = assertFailsWith<CalendarException.Forbidden> {
            fixture.updates.update(
                event.id,
                other.id,
                UpdateEvent(start = event.start + 1.hours, end = event.end + 1.hours),
                expectedEtag = event.etag,
            )
        }

        assertEquals("you do not have permission to reschedule this event", failure.message)
        assertTrue(mock.patches.isEmpty())
        assertEquals(start, fixture.store.getEvent(event.id, fixture.user.id)?.start)
    }

    @Test
    fun otherFieldChangesOnImportedEventRejectReschedule() = testApplication {
        val start = secondsFromNow(2.days)
        val mock = MockDiscord(scheduledEvents = { ok("[${eventJson(start = start)}]") })
        val fixture = installPushFixture(mock)
        val imported = fixture.importInto()
        val event = fixture.store.listEvents(imported.calendar.id, fixture.user.id).single()

        val renamed = assertFailsWith<CalendarException.Forbidden> {
            fixture.updates.update(
                event.id,
                fixture.user.id,
                UpdateEvent(title = "Renamed"),
                expectedEtag = event.etag,
            )
        }
        assertEquals("imported events can only be rescheduled", renamed.message)

        val cleared = assertFailsWith<CalendarException.Forbidden> {
            fixture.updates.update(
                event.id,
                fixture.user.id,
                UpdateEvent(description = OptionalField.Present(null)),
                expectedEtag = event.etag,
            )
        }
        assertEquals("imported events can only be rescheduled", cleared.message)
        assertTrue(mock.patches.isEmpty())
    }

    @Test
    fun discordPermissionDenialMapsToForbidden() = testApplication {
        val start = secondsFromNow(2.days)
        val mock = MockDiscord(
            scheduledEvents = { ok("[${eventJson(start = start)}]") },
            modifyEvent = { HttpStatusCode.Forbidden to """{"message":"Missing Permissions","code":50013}""" },
        )
        val fixture = installPushFixture(mock)
        val imported = fixture.importInto()
        val event = fixture.store.listEvents(imported.calendar.id, fixture.user.id).single()

        val failure = assertFailsWith<CalendarException.Forbidden> {
            fixture.updates.update(
                event.id,
                fixture.user.id,
                UpdateEvent(start = event.start + 1.hours, end = event.end + 1.hours),
                expectedEtag = event.etag,
            )
        }

        assertTrue("MANAGE_EVENTS" in failure.message.orEmpty(), failure.message)
        assertEquals(1, mock.patches.size)
        assertEquals(start, fixture.store.getEvent(event.id, fixture.user.id)?.start)
    }

    @Test
    fun missingDiscordEventMapsToNotFound() = testApplication {
        val start = secondsFromNow(2.days)
        val mock = MockDiscord(
            scheduledEvents = { ok("[${eventJson(start = start)}]") },
            modifyEvent = { HttpStatusCode.NotFound to """{"message":"Unknown Event","code":10070}""" },
        )
        val fixture = installPushFixture(mock)
        val imported = fixture.importInto()
        val event = fixture.store.listEvents(imported.calendar.id, fixture.user.id).single()

        assertFailsWith<CalendarException.NotFound> {
            fixture.updates.update(
                event.id,
                fixture.user.id,
                UpdateEvent(start = event.start + 1.hours, end = event.end + 1.hours),
                expectedEtag = event.etag,
            )
        }
    }

    @Test
    fun discordAuthFailurePropagates() = testApplication {
        val start = secondsFromNow(2.days)
        val mock = MockDiscord(
            scheduledEvents = { ok("[${eventJson(start = start)}]") },
            modifyEvent = { HttpStatusCode.Unauthorized to """{"message":"401: Unauthorized","code":0}""" },
        )
        val fixture = installPushFixture(mock)
        val imported = fixture.importInto()
        val event = fixture.store.listEvents(imported.calendar.id, fixture.user.id).single()

        assertFailsWith<DiscordAuthException> {
            fixture.updates.update(
                event.id,
                fixture.user.id,
                UpdateEvent(start = event.start + 1.hours, end = event.end + 1.hours),
                expectedEtag = event.etag,
            )
        }
    }

    @Test
    fun googleImportedEventRescheduleIsRejectedAsReadOnly() = testApplication {
        val start = secondsFromNow(2.days)
        val mock = MockDiscord(scheduledEvents = { ok("[]") })
        val fixture = installPushFixture(mock)
        val calendar = fixture.store.createCalendar(
            fixture.user.id,
            CreateCalendar(displayName = "Google"),
        )
        val connectionId = Uuid.random()
        val externalCalendarId = Uuid.random()
        val now = Clock.System.now()
        withContext(Dispatchers.IO) {
            suspendTransaction(fixture.database) {
                CalendarConnectionsTable.insert {
                    it[id] = connectionId
                    it[CalendarConnectionsTable.userId] = Uuid.parse(fixture.user.id.value)
                    it[provider] = "google"
                    it[externalAccountId] = "google-account"
                    it[accessTokenCiphertext] = "sealed"
                    it[accessTokenNonce] = "nonce"
                    it[tokenKeyVersion] = 1
                    it[status] = "active"
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                ExternalCalendarsTable.insert {
                    it[id] = externalCalendarId
                    it[ExternalCalendarsTable.connectionId] = connectionId
                    it[ExternalCalendarsTable.externalId] = "primary"
                    it[ExternalCalendarsTable.calendarId] = Uuid.parse(calendar.id.value)
                    it[externalName] = "Google"
                    it[syncDirection] = "pull"
                    it[enabled] = true
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
        fixture.external.upsert(
            externalCalendarId,
            calendar.id,
            ImportedCalendarEvent(
                uid = "google:event-1",
                title = "Google event",
                description = null,
                location = null,
                start = start,
                end = start + 1.hours,
                status = EventStatus.CONFIRMED,
            ),
        )
        val event = fixture.store.listEvents(calendar.id, fixture.user.id).single()

        val failure = assertFailsWith<CalendarException.Forbidden> {
            fixture.updates.update(
                event.id,
                fixture.user.id,
                UpdateEvent(start = start + 1.hours, end = start + 2.hours),
                expectedEtag = event.etag,
            )
        }

        assertEquals(
            "this external calendar is read-only; reschedules are only supported for two-way Discord imports",
            failure.message,
        )
        assertTrue(mock.patches.isEmpty())
        assertEquals(start, fixture.store.getEvent(event.id, fixture.user.id)?.start)
    }

    @Test
    fun homePageCalendarsExposeSyncMetadata() = testApplication {
        val start = secondsFromNow(2.days)
        val mock = MockDiscord(scheduledEvents = { ok("[${eventJson(start = start)}]") })
        val fixture = installPushFixture(mock)
        val imported = fixture.importInto()

        val client = jsonClient(followRedirects = false)
        client.login(username = "mey", password = "password12")
        val response = client.get("/") {
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val seed = KeelJson.codec.decodeFromString(KeelSeed.serializer(), response.bodyAsText())
        assertEquals("kalendee.home", seed.page)
        val page = KeelJson.codec.decodeFromJsonElement(HomePage.serializer(), seed.data)
        val calendar = page.calendars.single { it.id == imported.calendar.id.value }

        assertEquals(fixture.connectionId, calendar.connectionId)
        assertEquals("both", calendar.syncDirection)
        assertEquals("ok", calendar.syncStatus)
        assertNull(calendar.syncError)
        assertNull(calendar.syncBlockedReason)
    }

    private class ImportedGuild(
        val externalCalendarId: String,
        val calendar: Calendar,
    )

    private class PushFixture(
        val mock: MockDiscord,
        val store: CalendarStore,
        val external: ExternalEventStore,
        val imports: DiscordImportService,
        val updates: EventUpdateService,
        val auth: AuthService,
        val database: Database,
        val user: User,
        val connectionId: String,
    ) {
        suspend fun importInto(guildId: String = "101"): ImportedGuild {
            val summary = imports.importGuild(user.id, connectionId, guildId)
            val externalCalendarId = requireNotNull(summary.externalCalendarId) {
                "guild was not imported"
            }
            val row = externalRow(externalCalendarId)
            val calendarId = CalendarId(row[ExternalCalendarsTable.calendarId].toString())
            val calendar = requireNotNull(store.getCalendar(calendarId, user.id)) {
                "imported calendar not found"
            }
            return ImportedGuild(externalCalendarId, calendar)
        }

        suspend fun externalRow(externalCalendarId: String): ResultRow = withContext(Dispatchers.IO) {
            suspendTransaction(database) {
                ExternalCalendarsTable.selectAll()
                    .where { ExternalCalendarsTable.id eq Uuid.parse(externalCalendarId) }
                    .single()
            }
        }

        suspend fun exceptionId(eventId: EventId): String? = withContext(Dispatchers.IO) {
            suspendTransaction(database) {
                EventsTable.selectAll()
                    .where { EventsTable.id eq Uuid.parse(eventId.value) }
                    .single()[EventsTable.externalExceptionId]
            }
        }
    }

    private class MockDiscord(
        var userGuilds: () -> Pair<HttpStatusCode, String> = { ok(OwnerGuildsJson) },
        var botGuilds: () -> Pair<HttpStatusCode, String> = { ok(ManageEventsBotGuildsJson) },
        var scheduledEvents: () -> Pair<HttpStatusCode, String> = { ok("[]") },
        var modifyEvent: (String) -> Pair<HttpStatusCode, String> = { ok(DefaultEventJson) },
        var createException: () -> Pair<HttpStatusCode, String> = { ok(DefaultExceptionJson) },
        var modifyException: () -> Pair<HttpStatusCode, String> = { ok(DefaultExceptionJson) },
    ) {
        val requests = mutableListOf<HttpRequestData>()

        val patches: List<HttpRequestData>
            get() = requests.filter {
                it.method == HttpMethod.Patch &&
                    "/scheduled-events/" in it.url.encodedPath &&
                    "/exceptions/" !in it.url.encodedPath
            }

        val exceptionPosts: List<HttpRequestData>
            get() = requests.filter {
                it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/exceptions")
            }

        val exceptionPatches: List<HttpRequestData>
            get() = requests.filter {
                it.method == HttpMethod.Patch && "/exceptions/" in it.url.encodedPath
            }

        val engine: MockEngine = MockEngine { request ->
            requests += request
            val authorization = request.headers[HttpHeaders.Authorization].orEmpty()
            val path = request.url.encodedPath
            val response = when {
                request.method == HttpMethod.Post && path.endsWith("/oauth2/token") -> ok(TokenJson)
                request.method == HttpMethod.Post && path.endsWith("/oauth2/token/revoke") -> ok("{}")
                path.endsWith("/users/@me") && authorization.startsWith("Bearer") -> ok(IdentityJson)
                path.endsWith("/users/@me/guilds") && authorization.startsWith("Bearer") -> userGuilds()
                path.endsWith("/users/@me/guilds") && authorization.startsWith("Bot") -> botGuilds()
                request.method == HttpMethod.Post && path.endsWith("/exceptions") -> createException()
                request.method == HttpMethod.Patch && "/exceptions/" in path -> modifyException()
                request.method == HttpMethod.Patch && "/scheduled-events/" in path ->
                    modifyEvent(path.substringAfterLast('/'))
                "/scheduled-events/" in path -> ok(DefaultEventJson)
                path.endsWith("/scheduled-events") -> scheduledEvents()
                else -> HttpStatusCode.NotFound to "{}"
            }
            respond(
                content = response.second,
                status = response.first,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
    }

    private suspend fun ApplicationTestBuilder.installPushFixture(
        mock: MockDiscord,
        botToken: String? = "bot-token",
    ): PushFixture {
        lateinit var auth: AuthService
        lateinit var states: OAuthStateService
        lateinit var connections: ConnectionService
        lateinit var imports: DiscordImportService
        lateinit var updates: EventUpdateService
        lateinit var store: CalendarStore
        lateinit var external: ExternalEventStore
        lateinit var database: Database
        installApi(
            httpClient = HttpClient(mock.engine),
            extraConfig = pushTestConfig(botToken),
            configure = {
                auth = get()
                states = get()
                connections = get()
                imports = get()
                updates = get()
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
        return PushFixture(
            mock = mock,
            store = store,
            external = external,
            imports = imports,
            updates = updates,
            auth = auth,
            database = database,
            user = user,
            connectionId = connectionId,
        )
    }

    private fun eventJson(
        id: String = "201",
        name: String = "Community call",
        start: Instant,
        end: Instant = start + 1.hours,
        status: Int = 1,
        rule: String? = null,
    ): String = buildString {
        append("""{"id":"$id","guild_id":"101","channel_id":null,"name":"$name",""")
        append(""""description":"Hello","scheduled_start_time":"$start","scheduled_end_time":"$end",""")
        append(""""privacy_level":2,"status":$status,"entity_type":3,"entity_id":null,""")
        append(""""entity_metadata":{"location":"Lounge"},"user_count":3""")
        rule?.let { append(""","recurrence_rule":$it""") }
        append('}')
    }

    private fun exceptionJson(
        id: String = "901",
        start: Instant,
        end: Instant? = start + 1.hours,
        canceled: Boolean = false,
    ): String {
        val endValue = end?.let { "\"$it\"" } ?: "null"
        return """{"event_id":"201","event_exception_id":"$id",""" +
            """"scheduled_start_time":"$start",""" +
            """"scheduled_end_time":$endValue,"is_canceled":$canceled}"""
    }

    private fun guildJson(
        id: String,
        owner: Boolean = false,
        permissions: String? = null,
    ): String {
        val permissionField = if (permissions != null) ",\"permissions\":\"$permissions\"" else ""
        return "{\"id\":\"$id\",\"name\":\"$id\",\"icon\":null,\"owner\":$owner$permissionField,\"features\":[]}"
    }

    private fun pushTestConfig(botToken: String?): Map<String, String> = mapOf(
        "oauth.discord.clientId" to "discord-client",
        "oauth.discord.clientSecret" to "discord-secret",
        "oauth.discord.botToken" to (botToken ?: ""),
        "oauth.secretKey" to pushTestSecretKey,
    )
}

private fun ok(body: String): Pair<HttpStatusCode, String> = HttpStatusCode.OK to body

/**
 * Mirrored rows are stored with sub-second precision truncated by the
 * database, so tests compare against whole-second instants.
 */
private fun secondsFromNow(offset: Duration): Instant =
    Instant.fromEpochSeconds((Clock.System.now() + offset).epochSeconds)

private val OwnerGuildsJson = """[{"id":"101","name":"Kolektiv","icon":"icon-hash","owner":true,"features":[]}]"""

private val ManageEventsBotGuildsJson =
    """[{"id":"101","name":"Kolektiv","icon":"icon-hash","owner":false,""" +
        """"permissions":"8589934592","features":[]}]"""

private val DefaultEventJson =
    """{"id":"201","guild_id":"101","channel_id":null,"name":"Community call",""" +
        """"description":"Hello","scheduled_start_time":"2026-09-20T14:00:00Z",""" +
        """"scheduled_end_time":"2026-09-20T15:00:00Z","privacy_level":2,"status":1,""" +
        """"entity_type":3,"entity_id":null,"entity_metadata":{"location":"Lounge"},"user_count":3}"""

private val DefaultExceptionJson =
    """{"event_id":"201","event_exception_id":"901","scheduled_start_time":"2026-09-20T15:00:00Z",""" +
        """"scheduled_end_time":"2026-09-20T16:00:00Z","is_canceled":false}"""

private val IdentityJson = """{"id":"302","username":"mey","global_name":"Mey","avatar":null}"""

private val TokenJson =
    """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":604800,""" +
        """"scope":"identify guilds","token_type":"Bearer"}"""

private val pushTestSecretKey: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
