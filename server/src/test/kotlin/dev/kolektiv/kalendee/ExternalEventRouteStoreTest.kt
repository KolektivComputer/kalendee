package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.db.CalendarConnectionsTable
import dev.kolektiv.kalendee.db.DiscordEventRoutesTable
import dev.kolektiv.kalendee.db.ExternalCalendarsTable
import dev.kolektiv.kalendee.external.store.ExternalEventRouteStore
import dev.kolektiv.kalendee.external.store.RouteTarget
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.ktor.ext.get

class ExternalEventRouteStoreTest {
    @Test
    fun replaceRoutesRoundTripsCalendarAndSkipTargets() = testApplication {
        val fixture = installFixture()
        val anime = fixture.store.createCalendar(fixture.user.id, CreateCalendar(displayName = "Anime"))

        fixture.routes.replaceRoutes(
            fixture.externalId,
            mapOf(
                "event-1" to RouteTarget.Calendar(anime.id),
                "event-2" to RouteTarget.Skip,
            ),
        )

        assertEquals(
            mapOf(
                "event-1" to RouteTarget.Calendar(anime.id),
                "event-2" to RouteTarget.Skip,
            ),
            fixture.routes.routes(fixture.externalId),
        )

        fixture.routes.replaceRoutes(fixture.externalId, mapOf("event-2" to RouteTarget.Skip))

        assertEquals(
            mapOf("event-2" to RouteTarget.Skip),
            fixture.routes.routes(fixture.externalId),
        )

        fixture.routes.deleteRoutes(fixture.externalId)

        assertTrue(fixture.routes.routes(fixture.externalId).isEmpty())
    }

    @Test
    fun routesForOtherSourcesAreNotAffected() = testApplication {
        val fixture = installFixture()
        val other = fixture.insertAnotherSource()
        val anime = fixture.store.createCalendar(fixture.user.id, CreateCalendar(displayName = "Anime"))

        fixture.routes.replaceRoutes(fixture.externalId, mapOf("event-1" to RouteTarget.Calendar(anime.id)))
        fixture.routes.replaceRoutes(other, mapOf("event-1" to RouteTarget.Skip))

        assertEquals(
            mapOf("event-1" to RouteTarget.Calendar(anime.id)),
            fixture.routes.routes(fixture.externalId),
        )
        assertEquals(mapOf("event-1" to RouteTarget.Skip), fixture.routes.routes(other))
    }

    @Test
    fun nonSkippedRouteWithoutCalendarFailsTheCheckConstraint() = testApplication {
        val fixture = installFixture()

        assertFailsWith<Exception> {
            fixture.insertRoute(
                eventId = "event-1",
                calendarId = null,
                skipped = false,
            )
        }
        assertTrue(fixture.routes.routes(fixture.externalId).isEmpty())
    }

    @Test
    fun skippedRouteWithCalendarFailsTheCheckConstraint() = testApplication {
        val fixture = installFixture()

        assertFailsWith<Exception> {
            fixture.insertRoute(
                eventId = "event-1",
                calendarId = fixture.calendar.id.toUuid(),
                skipped = true,
            )
        }
        assertTrue(fixture.routes.routes(fixture.externalId).isEmpty())
    }

    @Test
    fun duplicateEventForTheSameSourceFailsTheUniqueConstraint() = testApplication {
        val fixture = installFixture()
        fixture.insertRoute(eventId = "event-1", calendarId = null, skipped = true)

        assertFailsWith<Exception> {
            fixture.insertRoute(eventId = "event-1", calendarId = null, skipped = true)
        }

        val routes = fixture.routes.routes(fixture.externalId)
        assertEquals(1, routes.size)
        assertEquals(RouteTarget.Skip, routes["event-1"])
    }

    @Test
    fun routesCascadeWhenTheSourceIsDeleted() = testApplication {
        val fixture = installFixture()
        fixture.routes.replaceRoutes(fixture.externalId, mapOf("event-1" to RouteTarget.Skip))

        fixture.deleteSource()

        assertTrue(fixture.routes.routes(fixture.externalId).isEmpty())
        assertNotNull(fixture.store.getCalendar(fixture.calendar.id, fixture.user.id))
    }

    private class Fixture(
        val routes: ExternalEventRouteStore,
        val store: CalendarStore,
        val database: Database,
        val user: User,
        val calendar: Calendar,
        val externalId: Uuid,
    ) {
        suspend fun insertRoute(eventId: String, calendarId: Uuid?, skipped: Boolean) {
            withContext(Dispatchers.IO) {
                suspendTransaction(database) {
                    DiscordEventRoutesTable.insert {
                        it[id] = Uuid.random()
                        it[DiscordEventRoutesTable.externalCalendarId] = externalId
                        it[DiscordEventRoutesTable.eventId] = eventId
                        it[DiscordEventRoutesTable.calendarId] = calendarId
                        it[DiscordEventRoutesTable.skipped] = skipped
                        it[createdAt] = Clock.System.now()
                        it[updatedAt] = Clock.System.now()
                    }
                }
            }
        }

        suspend fun insertAnotherSource(): Uuid {
            val connectionId = withContext(Dispatchers.IO) {
                suspendTransaction(database) {
                    ExternalCalendarsTable.selectAll()
                        .where { ExternalCalendarsTable.id eq externalId }
                        .single()[ExternalCalendarsTable.connectionId]
                }
            }
            val other = Uuid.random()
            val otherCalendar = store.createCalendar(user.id, CreateCalendar(displayName = "Second mirror"))
            val now = Clock.System.now()
            withContext(Dispatchers.IO) {
                suspendTransaction(database) {
                    ExternalCalendarsTable.insert {
                        it[id] = other
                        it[ExternalCalendarsTable.connectionId] = connectionId
                        it[ExternalCalendarsTable.externalId] = "guild-2"
                        it[ExternalCalendarsTable.calendarId] = otherCalendar.id.toUuid()
                        it[externalName] = "Second"
                        it[syncDirection] = "pull"
                        it[enabled] = true
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
            }
            return other
        }

        suspend fun deleteSource() {
            val source = externalId
            withContext(Dispatchers.IO) {
                suspendTransaction(database) {
                    ExternalCalendarsTable.deleteWhere { ExternalCalendarsTable.id eq source }
                }
            }
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.installFixture(): Fixture {
        lateinit var routes: ExternalEventRouteStore
        lateinit var store: CalendarStore
        lateinit var database: Database
        lateinit var auth: AuthService
        installApi(
            configure = {
                routes = get()
                store = get()
                database = get()
                auth = get()
            },
        )
        startApplication()
        val user = auth.register(RegisterUser(username = "alice", password = "password12"))
            .session?.user
            ?: error("registration did not create a session")
        val calendar = store.createCalendar(user.id, CreateCalendar(displayName = "Discord · Kolektiv"))
        val externalId = insertExternalCalendar(database, user.id, calendar.id)
        return Fixture(
            routes = routes,
            store = store,
            database = database,
            user = user,
            calendar = calendar,
            externalId = externalId,
        )
    }

    private suspend fun insertExternalCalendar(
        database: Database,
        userId: dev.kolektiv.kalendee.auth.UserId,
        calendarId: CalendarId,
    ): Uuid {
        val connectionId = Uuid.random()
        val externalId = Uuid.random()
        val now = Clock.System.now()
        withContext(Dispatchers.IO) {
            suspendTransaction(database) {
                CalendarConnectionsTable.insert {
                    it[id] = connectionId
                    it[CalendarConnectionsTable.userId] = Uuid.parse(userId.value)
                    it[provider] = "discord"
                    it[externalAccountId] = "discord-account"
                    it[accessTokenCiphertext] = "sealed"
                    it[accessTokenNonce] = "nonce"
                    it[tokenKeyVersion] = 1
                    it[status] = "active"
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                ExternalCalendarsTable.insert {
                    it[id] = externalId
                    it[ExternalCalendarsTable.connectionId] = connectionId
                    it[ExternalCalendarsTable.externalId] = "guild-1"
                    it[ExternalCalendarsTable.calendarId] = calendarId.toUuid()
                    it[externalName] = "Kolektiv"
                    it[syncDirection] = "pull"
                    it[enabled] = true
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
        return externalId
    }
}

private fun CalendarId.toUuid(): Uuid = Uuid.parse(value)
