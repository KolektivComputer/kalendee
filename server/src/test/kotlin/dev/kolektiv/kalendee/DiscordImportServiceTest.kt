package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.calendar.UpdateEvent
import dev.kolektiv.kalendee.db.EventsTable
import dev.kolektiv.kalendee.db.ExternalCalendarsTable
import dev.kolektiv.kalendee.external.store.ExternalEventRouteStore
import dev.kolektiv.kalendee.external.store.ExternalEventStore
import dev.kolektiv.kalendee.external.store.RouteTarget
import dev.kolektiv.kalendee.external.store.StoredExternalEvent
import dev.kolektiv.kalendee.oauth.ConnectionService
import dev.kolektiv.kalendee.oauth.OAuthCallbackOutcome
import dev.kolektiv.kalendee.oauth.OAuthReauthRequiredException
import dev.kolektiv.kalendee.oauth.OAuthStateService
import dev.kolektiv.kalendee.oauth.Pkce
import dev.kolektiv.kalendee.oauth.discord.DiscordBotNotInGuildException
import dev.kolektiv.kalendee.oauth.discord.DiscordImportException
import dev.kolektiv.kalendee.oauth.discord.DiscordImportService
import dev.kolektiv.kalendee.oauth.discord.DiscordRouteAssignment
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
import kotlin.time.Duration
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
import org.jetbrains.exposed.v1.core.and
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

        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")

        assertTrue(summary.imported)
        assertTrue(summary.enabled)
        assertTrue(summary.botPresent)
        assertNotNull(summary.externalCalendarId)
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)
        assertEquals("Discord · Kolektiv", calendar.displayName)
        val stored = fixture.stored(externalCalendarId).single()
        assertEquals("discord:101:201", stored.uid)
        val event = fixture.store.listEvents(calendar.id, fixture.user.id).single()
        assertEquals("Community call", event.title)
        val row = fixture.externalRow(externalCalendarId)
        assertEquals("101", row[ExternalCalendarsTable.externalId])
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
        assertEquals("101", before.id)
        assertTrue(before.botPresent)
        assertTrue(before.manageable)
        assertFalse(before.imported)
        assertFalse(before.enabled)
        assertNull(before.externalCalendarId)
        assertNull(before.inviteUrl)

        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
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
        assertTrue(guild.manageable)
        assertFalse(guild.imported)
        assertNull(guild.inviteUrl)
    }

    @Test
    fun guildListingIncludesBotPresentButUnmanageableGuilds() = testApplication {
        val fixture = installImportFixture(
            discordEngine(
                userGuilds = { ok("[${guildJson("102", permissions = "1024")}]") },
                botGuilds = { ok("[${guildJson("102")}]") },
            ),
        )

        val guild = fixture.imports.guilds(fixture.user.id, fixture.connectionId).single()

        assertEquals("102", guild.id)
        assertTrue(guild.botPresent)
        assertFalse(guild.manageable)
        assertNull(guild.inviteUrl)
    }

    @Test
    fun guildListingExcludesUnmanageableGuildsWithoutBot() = testApplication {
        val fixture = installImportFixture(
            discordEngine(
                userGuilds = { ok("[${guildJson("102", permissions = "1024")}]") },
                botGuilds = { ok("[]") },
            ),
        )

        assertTrue(fixture.imports.guilds(fixture.user.id, fixture.connectionId).isEmpty())
    }

    @Test
    fun guildListingIncludesManageableGuildWithoutBotAndInvite() = testApplication {
        val fixture = installImportFixture(
            discordEngine(
                userGuilds = { ok("[${guildJson("102", permissions = "32")}]") },
                botGuilds = { ok("[]") },
            ),
        )

        val guild = fixture.imports.guilds(fixture.user.id, fixture.connectionId).single()

        assertEquals("102", guild.id)
        assertFalse(guild.botPresent)
        assertTrue(guild.manageable)
        val invite = assertNotNull(guild.inviteUrl)
        assertEquals("discord-client", Url(invite).parameters["client_id"])
    }

    @Test
    fun manageabilityParsesOwnerManageGuildBitAndLargePermissions() = testApplication {
        val fixture = installImportFixture(
            discordEngine(
                userGuilds = {
                    // Kord deserializes the permissions bitfield itself and rejects
                    // unparseable values for the whole list, so the old
                    // "garbage permissions" tolerance can no longer be exercised.
                    ok(
                        "[" +
                            guildJson("111", owner = true) + "," +
                            guildJson("112", permissions = "32") + "," +
                            guildJson("113", permissions = "1024") + "," +
                            guildJson("114", permissions = "18446744073709551615") + "," +
                            guildJson("115", permissions = "9223372036854775808") +
                            "]",
                    )
                },
                botGuilds = { ok("[]") },
            ),
        )

        val listed = fixture.imports.guilds(fixture.user.id, fixture.connectionId).associateBy { it.id }

        assertEquals(setOf("111", "112", "114"), listed.keys)
        assertTrue(listed.getValue("111").manageable)
        assertTrue(listed.getValue("112").manageable)
        assertTrue(listed.getValue("114").manageable)
        assertFalse(listed.getValue("111").botPresent)
        val invite = assertNotNull(listed.getValue("111").inviteUrl)
        assertEquals("discord-client", Url(invite).parameters["client_id"])
    }

    @Test
    fun importStoresBothDirectionWhenUserAndBotCanManageEvents() = testApplication {
        val manageEvents = "8589934592"
        val fixture = installImportFixture(
            discordEngine(
                userGuilds = {
                    ok(
                        "[" +
                            guildJson("111", owner = true) + "," +
                            guildJson("112", permissions = manageEvents) + "," +
                            guildJson("113", permissions = "8") +
                            "]",
                    )
                },
                botGuilds = {
                    ok(
                        "[" +
                            guildJson("111", permissions = manageEvents) + "," +
                            guildJson("112", permissions = manageEvents) + "," +
                            guildJson("113", permissions = manageEvents) +
                            "]",
                    )
                },
            ),
        )

        listOf("111", "112", "113").forEach { guildId ->
            val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, guildId)
            val row = fixture.externalRow(assertNotNull(summary.externalCalendarId))
            assertEquals("both", row[ExternalCalendarsTable.syncDirection])
        }
    }

    @Test
    fun importStoresPullDirectionWhenEitherSideLacksManageEvents() = testApplication {
        val manageEvents = "8589934592"
        val fixture = installImportFixture(
            discordEngine(
                userGuilds = {
                    ok("[${guildJson("111", owner = true)},${guildJson("112")}]")
                },
                botGuilds = {
                    ok(
                        "[" +
                            guildJson("111", permissions = "1024") + "," +
                            guildJson("112", permissions = manageEvents) +
                            "]",
                    )
                },
            ),
        )

        listOf("111", "112").forEach { guildId ->
            val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, guildId)
            val row = fixture.externalRow(assertNotNull(summary.externalCalendarId))
            assertEquals("pull", row[ExternalCalendarsTable.syncDirection])
        }
    }

    @Test
    fun guildListingRefreshesStaleSyncDirection() = testApplication {
        val manageEvents = "8589934592"
        var userGuilds = "[${guildJson("101", permissions = "1024")}]"
        var botGuilds = "[${guildJson("101", permissions = "1024")}]"
        val fixture = installImportFixture(
            discordEngine(
                userGuilds = { ok(userGuilds) },
                botGuilds = { ok(botGuilds) },
            ),
        )
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        assertEquals("pull", fixture.externalRow(externalCalendarId)[ExternalCalendarsTable.syncDirection])

        userGuilds = "[${guildJson("101", permissions = manageEvents)}]"
        botGuilds = "[${guildJson("101", permissions = manageEvents)}]"
        fixture.imports.guilds(fixture.user.id, fixture.connectionId)

        assertEquals("both", fixture.externalRow(externalCalendarId)[ExternalCalendarsTable.syncDirection])

        botGuilds = "[${guildJson("101", permissions = "1024")}]"
        fixture.imports.guilds(fixture.user.id, fixture.connectionId)

        assertEquals("pull", fixture.externalRow(externalCalendarId)[ExternalCalendarsTable.syncDirection])
    }

    @Test
    fun importedGuildStaysListedAfterLosingBotAndManageability() = testApplication {
        var userGuilds = "[${guildJson("101", permissions = "32")}]"
        var botGuilds = "[${guildJson("101")}]"
        val fixture = installImportFixture(
            discordEngine(
                userGuilds = { ok(userGuilds) },
                botGuilds = { ok(botGuilds) },
                scheduledEvents = { ok("[]") },
            ),
        )
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        assertTrue(summary.imported)

        userGuilds = "[${guildJson("101", permissions = "1024")}]"
        botGuilds = "[]"
        val guild = fixture.imports.guilds(fixture.user.id, fixture.connectionId).single()

        assertTrue(guild.imported)
        assertEquals(summary.externalCalendarId, guild.externalCalendarId)
        assertFalse(guild.botPresent)
        assertFalse(guild.manageable)
        assertNull(guild.inviteUrl)
    }

    @Test
    fun secondSyncIsIdempotent() = testApplication {
        val start = Clock.System.now() + 2.days
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok("[${eventJson(start = start)}]") }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val before = fixture.stored(externalCalendarId)

        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        assertEquals(before, fixture.stored(externalCalendarId))
        assertEquals(1, fixture.eventCount(externalCalendarId))
    }

    @Test
    fun syncPropagatesEventUpdates() = testApplication {
        val start = Clock.System.now() + 2.days
        var body = "[${eventJson(start = start, name = "First")}]"
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok(body) }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)

        body = "[${eventJson(start = start, name = "Second")}]"
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        assertEquals("Second", fixture.store.listEvents(calendar.id, fixture.user.id).single().title)
        assertEquals(1, fixture.eventCount(externalCalendarId))
    }

    @Test
    fun localEditIsRejectedAndSyncPropagatesProviderChanges() = testApplication {
        val start = Clock.System.now() + 2.days
        var body = "[${eventJson(start = start, name = "First")}]"
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok(body) }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)
        val event = fixture.store.listEvents(calendar.id, fixture.user.id).single()

        assertFailsWith<CalendarException.Forbidden> {
            fixture.store.updateEvent(event.id, fixture.user.id, UpdateEvent(title = "Locally edited"))
        }
        assertEquals("First", fixture.store.listEvents(calendar.id, fixture.user.id).single().title)

        body = "[${eventJson(start = start, name = "Provider rename")}]"
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        assertEquals("Provider rename", fixture.store.listEvents(calendar.id, fixture.user.id).single().title)
        assertEquals(1, fixture.eventCount(externalCalendarId))
    }

    @Test
    fun discordExceptionAppliesOverrideTimesToOccurrence() = testApplication {
        val start = wholeSecondsFromNow(1.days)
        val weekday = start.toLocalDateTime(TimeZone.UTC).dayOfWeek.isoDayNumber - 1
        val rule = """{"start":"$start","frequency":2,"interval":1,"by_weekday":[$weekday]}"""
        val movedStart = start + 3.hours
        val movedEnd = movedStart + 2.hours
        var body = "[${eventJson(start = start, rule = rule)}]"
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok(body) }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)
        val occurrenceUid = "discord:101:201:$start"
        fixture.external.setExceptionId(fixture.eventIdFor(externalCalendarId, occurrenceUid), "901")

        body = "[${eventJson(
            start = start,
            rule = rule,
            exceptions = "[${exceptionJson(start = movedStart, end = movedEnd)}]",
        )}]"
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        val stored = fixture.stored(externalCalendarId).single { it.uid == occurrenceUid }
        assertEquals(movedStart, stored.start)
        assertEquals(movedEnd, stored.end)
        assertEquals(EventStatus.CONFIRMED, stored.status)
        val event = fixture.store.listEvents(calendar.id, fixture.user.id)
            .single { it.externalUid == occurrenceUid }
        assertEquals(movedStart, event.start)
        assertEquals(movedEnd, event.end)
        assertEquals(EventStatus.CONFIRMED, event.status)
    }

    @Test
    fun canceledDiscordExceptionMarksOccurrenceCancelled() = testApplication {
        val start = wholeSecondsFromNow(1.days)
        val weekday = start.toLocalDateTime(TimeZone.UTC).dayOfWeek.isoDayNumber - 1
        val rule = """{"start":"$start","frequency":2,"interval":1,"by_weekday":[$weekday]}"""
        var body = "[${eventJson(start = start, rule = rule)}]"
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok(body) }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)
        val occurrenceUid = "discord:101:201:$start"
        fixture.external.setExceptionId(fixture.eventIdFor(externalCalendarId, occurrenceUid), "901")

        body = "[${eventJson(
            start = start,
            rule = rule,
            exceptions = "[${exceptionJson(canceled = true)}]",
        )}]"
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        val stored = fixture.stored(externalCalendarId).single { it.uid == occurrenceUid }
        assertEquals(EventStatus.CANCELLED, stored.status)
        assertEquals(start, stored.start)
        assertEquals(
            EventStatus.CANCELLED,
            fixture.store.listEvents(calendar.id, fixture.user.id)
                .single { it.externalUid == occurrenceUid }
                .status,
        )
    }

    @Test
    fun discordExceptionWithoutLocalExceptionIdIsIgnored() = testApplication {
        val start = wholeSecondsFromNow(1.days)
        val weekday = start.toLocalDateTime(TimeZone.UTC).dayOfWeek.isoDayNumber - 1
        val rule = """{"start":"$start","frequency":2,"interval":1,"by_weekday":[$weekday]}"""
        var body = "[${eventJson(start = start, rule = rule)}]"
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok(body) }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val occurrenceUid = "discord:101:201:$start"

        // No local row tracks exception 901 (it was created in the Discord
        // client), so there is no occurrence to correlate it with.
        body = "[${eventJson(
            start = start,
            rule = rule,
            exceptions = "[${exceptionJson(start = start + 3.hours)}]",
        )}]"
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        assertEquals(start, fixture.stored(externalCalendarId).single { it.uid == occurrenceUid }.start)
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
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        assertEquals(1, fixture.stored(externalCalendarId).size)

        list = "[]"
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        assertTrue(fixture.stored(externalCalendarId).isEmpty())
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
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)

        list = "[]"
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        assertEquals(1, fixture.stored(externalCalendarId).size)
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
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val calendar = fixture.calendarFor(externalCalendarId)

        list = "[]"
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        assertEquals(EventStatus.CANCELLED, fixture.stored(externalCalendarId).single().status)
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
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val oldUids = fixture.stored(externalCalendarId).map { it.uid }
        assertTrue(oldUids.size > 1)
        assertTrue(oldUids.all { "discord:101:201:" in it })

        body = recurring((weekday + 1) % 7)
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        val newUids = fixture.stored(externalCalendarId).map { it.uid }
        assertTrue(newUids.isNotEmpty())
        assertTrue(oldUids.none { it in newUids })
    }

    @Test
    fun unmappedEventsImportIntoTheDefaultCalendar() = testApplication {
        val start = Clock.System.now() + 2.days
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok("[${eventJson(start = start)}]") }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val default = fixture.calendarFor(externalCalendarId)
        val anime = fixture.store.createCalendar(fixture.user.id, CreateCalendar(displayName = "Anime"))
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        val stored = fixture.stored(externalCalendarId).single()
        assertEquals(default.id, stored.calendarId)
        assertEquals(1, fixture.store.listEvents(default.id, fixture.user.id).size)
        assertTrue(fixture.store.listEvents(anime.id, fixture.user.id).isEmpty())
    }

    @Test
    fun assignedEventsImportIntoTheRoutedCalendar() = testApplication {
        val start = Clock.System.now() + 2.days
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok("[${eventJson(start = start)}]") }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val default = fixture.calendarFor(externalCalendarId)
        val anime = fixture.store.createCalendar(fixture.user.id, CreateCalendar(displayName = "Anime"))
        fixture.routes.replaceRoutes(
            Uuid.parse(externalCalendarId),
            mapOf("201" to RouteTarget.Calendar(anime.id)),
        )

        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        val stored = fixture.stored(externalCalendarId).single()
        assertEquals(anime.id, stored.calendarId)
        assertEquals(1, fixture.store.listEvents(anime.id, fixture.user.id).size)
        assertTrue(fixture.store.listEvents(default.id, fixture.user.id).isEmpty())
    }

    @Test
    fun newEventsFallBackToTheDefaultCalendar() = testApplication {
        val start = Clock.System.now() + 2.days
        var body = "[${eventJson(id = "201", start = start)}]"
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok(body) }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val default = fixture.calendarFor(externalCalendarId)
        val anime = fixture.store.createCalendar(fixture.user.id, CreateCalendar(displayName = "Anime"))
        fixture.routes.replaceRoutes(
            Uuid.parse(externalCalendarId),
            mapOf("201" to RouteTarget.Calendar(anime.id)),
        )

        body = "[${eventJson(id = "201", start = start)},${eventJson(id = "202", start = start + 1.hours)}]"
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        val stored = fixture.stored(externalCalendarId).associateBy { it.uid }
        assertEquals(anime.id, stored.getValue("discord:101:201").calendarId)
        assertEquals(default.id, stored.getValue("discord:101:202").calendarId)
    }

    @Test
    fun skippedEventsAreRemovedFromImport() = testApplication {
        val start = Clock.System.now() + 2.days
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok("[${eventJson(start = start)}]") }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val default = fixture.calendarFor(externalCalendarId)
        assertEquals(1, fixture.stored(externalCalendarId).size)

        fixture.routes.replaceRoutes(Uuid.parse(externalCalendarId), mapOf("201" to RouteTarget.Skip))
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        assertTrue(fixture.stored(externalCalendarId).isEmpty())
        assertTrue(fixture.store.listEvents(default.id, fixture.user.id).isEmpty())
        assertEquals(RouteTarget.Skip, fixture.routes.routes(Uuid.parse(externalCalendarId))["201"])
    }

    @Test
    fun targetChangeMovesRowsWithoutDuplicates() = testApplication {
        val start = Clock.System.now() + 2.days
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok("[${eventJson(start = start)}]") }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val default = fixture.calendarFor(externalCalendarId)
        val anime = fixture.store.createCalendar(fixture.user.id, CreateCalendar(displayName = "Anime"))
        val gaming = fixture.store.createCalendar(fixture.user.id, CreateCalendar(displayName = "Gaming"))

        fixture.routes.replaceRoutes(
            Uuid.parse(externalCalendarId),
            mapOf("201" to RouteTarget.Calendar(anime.id)),
        )
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)
        assertEquals(1, fixture.store.listEvents(anime.id, fixture.user.id).size)

        fixture.routes.replaceRoutes(
            Uuid.parse(externalCalendarId),
            mapOf("201" to RouteTarget.Calendar(gaming.id)),
        )
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        val stored = fixture.stored(externalCalendarId).single()
        assertEquals(gaming.id, stored.calendarId)
        assertEquals(1, fixture.store.listEvents(gaming.id, fixture.user.id).size)
        assertTrue(fixture.store.listEvents(anime.id, fixture.user.id).isEmpty())
        assertTrue(fixture.store.listEvents(default.id, fixture.user.id).isEmpty())
        assertEquals(1, fixture.eventCount(externalCalendarId))
    }

    @Test
    fun recurringOccurrencesInheritTheBaseRoute() = testApplication {
        val start = Clock.System.now() + 1.days
        val weekday = start.toLocalDateTime(TimeZone.UTC).dayOfWeek.isoDayNumber - 1
        val rule = """{"start":"$start","frequency":2,"interval":1,"by_weekday":[$weekday]}"""
        val fixture = installImportFixture(
            discordEngine(scheduledEvents = { ok("[${eventJson(start = start, rule = rule)}]") }),
        )
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val default = fixture.calendarFor(externalCalendarId)
        val anime = fixture.store.createCalendar(fixture.user.id, CreateCalendar(displayName = "Anime"))
        val occurrences = fixture.stored(externalCalendarId).size
        assertTrue(occurrences > 1)

        fixture.routes.replaceRoutes(
            Uuid.parse(externalCalendarId),
            mapOf("201" to RouteTarget.Calendar(anime.id)),
        )
        fixture.imports.syncNow(fixture.user.id, externalCalendarId)

        val stored = fixture.stored(externalCalendarId)
        assertEquals(occurrences, stored.size)
        assertTrue(stored.all { it.calendarId == anime.id })
        assertTrue(stored.all { it.uid.startsWith("discord:101:201:") })
        assertEquals(occurrences, fixture.store.listEvents(anime.id, fixture.user.id).size)
        assertTrue(fixture.store.listEvents(default.id, fixture.user.id).isEmpty())
    }

    @Test
    fun saveSyncBootstrapsDefaultAndRoutes() = testApplication {
        val start = Clock.System.now() + 2.days
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok("[${eventJson(start = start)}]") }))
        val anime = fixture.store.createCalendar(fixture.user.id, CreateCalendar(displayName = "Anime"))
        val gaming = fixture.store.createCalendar(fixture.user.id, CreateCalendar(displayName = "Gaming"))

        val summary = fixture.imports.saveSync(
            userId = fixture.user.id,
            connectionId = fixture.connectionId,
            guildId = "101",
            defaultCalendarId = anime.id.value,
            routes = listOf(
                DiscordRouteAssignment(eventId = "201", calendarId = gaming.id.value, skipped = false),
            ),
            enabled = true,
        )

        assertTrue(summary.imported)
        assertTrue(summary.enabled)
        assertEquals(anime.id.value, summary.calendarId)
        val externalCalendarId = assertNotNull(summary.externalCalendarId)
        val stored = fixture.stored(externalCalendarId).single()
        assertEquals(gaming.id, stored.calendarId)
        assertEquals(1, fixture.store.listEvents(gaming.id, fixture.user.id).size)
        assertTrue(fixture.store.listEvents(anime.id, fixture.user.id).isEmpty())
        assertEquals(
            mapOf("201" to RouteTarget.Calendar(gaming.id)),
            fixture.routes.routes(Uuid.parse(externalCalendarId)),
        )

        val setup = fixture.imports.syncSetup(fixture.user.id, fixture.connectionId, "101")
        assertEquals(anime.id.value, setup.defaultCalendarId)
        assertTrue(setup.calendars.any { it.id == anime.id })
        assertTrue(setup.calendars.any { it.id == gaming.id })
        assertEquals(gaming.id.value, setup.events.single().calendarId)
    }

    @Test
    fun importWithoutBotTokenFails() = testApplication {
        val fixture = installImportFixture(discordEngine(), botToken = null)

        assertFailsWith<DiscordBotNotConfiguredException> {
            fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        }
        assertTrue(fixture.externalRowsForConnection().isEmpty())
    }

    @Test
    fun importWhenBotIsNotInGuildFailsWithInvite() = testApplication {
        val fixture = installImportFixture(discordEngine(botGuilds = { ok("[]") }))

        val failure = assertFailsWith<DiscordBotNotInGuildException> {
            fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        }

        assertEquals("101", failure.guildId)
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
        val body = "[${eventJson(id = "201", start = start)},${eventJson(id = "202", start = start)}]"
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok(body) }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
        val externalCalendarId = assertNotNull(summary.externalCalendarId)

        coroutineScope {
            listOf(
                async { fixture.imports.syncNow(fixture.user.id, externalCalendarId) },
                async { fixture.imports.syncNow(fixture.user.id, externalCalendarId) },
            ).awaitAll()
        }

        assertEquals(2, fixture.stored(externalCalendarId).size)
        assertEquals(2, fixture.eventCount(externalCalendarId))
    }

    @Test
    fun removeImportDetachesEventsAndKeepsTheCalendarEditable() = testApplication {
        val start = Clock.System.now() + 2.days
        val fixture = installImportFixture(discordEngine(scheduledEvents = { ok("[${eventJson(start = start)}]") }))
        val summary = fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
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
            fixture.imports.importGuild(fixture.user.id, fixture.connectionId, "101")
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
        val routes: ExternalEventRouteStore,
        val database: Database,
        val user: User,
        val connectionId: String,
    ) {
        suspend fun stored(externalCalendarId: String): List<StoredExternalEvent> =
            external.listBySource(Uuid.parse(externalCalendarId))

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

        suspend fun eventIdFor(externalCalendarId: String, uid: String): EventId =
            withContext(Dispatchers.IO) {
                suspendTransaction(database) {
                    EventId(
                        EventsTable.selectAll()
                            .where {
                                (EventsTable.externalCalendarId eq Uuid.parse(externalCalendarId)) and
                                    (EventsTable.externalUid eq uid)
                            }
                            .single()[EventsTable.id].toString(),
                    )
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
        lateinit var routes: ExternalEventRouteStore
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
                routes = get()
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
            routes = routes,
            database = database,
            user = user,
            connectionId = connectionId,
        )
    }

    private fun discordEngine(
        userGuilds: () -> Pair<HttpStatusCode, String> = { ok(UserGuildsJson) },
        botGuilds: () -> Pair<HttpStatusCode, String> = { ok(BotGuildsJson) },
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
        id: String = "201",
        name: String = "Community call",
        start: Instant,
        status: Int = 1,
        rule: String? = null,
        exceptions: String? = null,
    ): String = buildString {
        append("""{"id":"$id","guild_id":"101","channel_id":null,"name":"$name",""")
        append(""""description":"Hello","scheduled_start_time":"$start","scheduled_end_time":"${start + 1.hours}",""")
        append(""""privacy_level":2,"status":$status,"entity_type":3,"entity_id":null,""")
        append(""""entity_metadata":{"location":"Lounge"},"user_count":3""")
        rule?.let { append(""","recurrence_rule":$it""") }
        exceptions?.let { append(""","guild_scheduled_event_exceptions":$it""") }
        append('}')
    }

    private fun exceptionJson(
        id: String = "901",
        start: Instant? = null,
        end: Instant? = null,
        canceled: Boolean = false,
    ): String {
        val startValue = start?.let { "\"$it\"" } ?: "null"
        val endValue = end?.let { "\"$it\"" } ?: "null"
        return """{"event_id":"201","event_exception_id":"$id","scheduled_start_time":$startValue,""" +
            """"scheduled_end_time":$endValue,"is_canceled":$canceled}"""
    }

    private fun wholeSecondsFromNow(offset: Duration): Instant =
        Instant.fromEpochSeconds((Clock.System.now() + offset).epochSeconds)

    private fun guildJson(
        id: String,
        owner: Boolean = false,
        permissions: String? = null,
    ): String {
        val permissionField = if (permissions != null) ",\"permissions\":\"$permissions\"" else ""
        return "{\"id\":\"$id\",\"name\":\"$id\",\"icon\":null,\"owner\":$owner$permissionField,\"features\":[]}"
    }

    private fun discordTestConfig(botToken: String?): Map<String, String> = mapOf(
        "oauth.discord.clientId" to "discord-client",
        "oauth.discord.clientSecret" to "discord-secret",
        "oauth.discord.botToken" to (botToken ?: ""),
        "oauth.secretKey" to discordTestSecretKey,
    )

    private fun ok(body: String): Pair<HttpStatusCode, String> = HttpStatusCode.OK to body

    private companion object {
        val UserGuildsJson = """[{"id":"101","name":"Kolektiv","icon":"icon-hash","owner":true,"features":[]}]"""
        val BotGuildsJson =
            """[{"id":"101","name":"Kolektiv","icon":"icon-hash","owner":false,"permissions":"1024","features":[]}]"""
        val IdentityJson = """{"id":"302","username":"mey","global_name":"Mey","avatar":null}"""
        val TokenJson =
            """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":604800,""" +
                """"scope":"identify guilds","token_type":"Bearer"}"""
        val discordTestSecretKey: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    }
}
