package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.calendar.UpdateEvent
import dev.kolektiv.kalendee.db.CalendarConnectionsTable
import dev.kolektiv.kalendee.db.EventsTable
import dev.kolektiv.kalendee.db.ExternalCalendarsTable
import dev.kolektiv.kalendee.events.EventInviteService
import dev.kolektiv.kalendee.external.store.ExternalEventStore
import dev.kolektiv.kalendee.oauth.discord.ImportedCalendarEvent
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.ktor.ext.get

class ExternalEventStoreTest {
    @Test
    fun upsertStoresMirroredEvent() = testApplication {
        val fixture = installFixture()
        val event = importedEvent()

        fixture.external.upsert(fixture.calendar.id, event)

        val stored = fixture.external.listExternal(fixture.calendar.id).single()
        assertEquals(event.uid, stored.uid)
        assertEquals(event.start, stored.start)
        assertEquals(event.end, stored.end)
        assertEquals(EventStatus.CONFIRMED, stored.status)

        val row = fixture.rawRows().single()
        val externalCalendarId = Uuid.parse(fixture.externalId.toString())
        assertEquals(externalCalendarId, row[EventsTable.externalCalendarId])
        assertEquals(event.uid, row[EventsTable.externalUid])
        assertNull(row[EventsTable.recurrenceFrequency])
        assertNull(row[EventsTable.recurrenceInterval])
        assertTrue(row[EventsTable.etag].isNotBlank())
        assertEquals(fixture.calendar.id.value, row[EventsTable.calendarId].toString())
    }

    @Test
    fun upsertIsIdempotentForUnchangedEvents() = testApplication {
        val fixture = installFixture()
        val event = importedEvent()

        fixture.external.upsert(fixture.calendar.id, event)
        val first = fixture.rawRows().single()
        fixture.external.upsert(fixture.calendar.id, event)
        val second = fixture.rawRows().single()

        assertEquals(1, fixture.rawRows().size)
        assertEquals(first[EventsTable.id], second[EventsTable.id])
        assertEquals(first[EventsTable.etag], second[EventsTable.etag])
        assertEquals(first[EventsTable.createdAt], second[EventsTable.createdAt])
        assertEquals(first[EventsTable.updatedAt], second[EventsTable.updatedAt])
    }

    @Test
    fun upsertPropagatesChangesAndBumpsEtag() = testApplication {
        val fixture = installFixture()
        val event = importedEvent()
        fixture.external.upsert(fixture.calendar.id, event)
        val first = fixture.rawRows().single()

        fixture.external.upsert(
            fixture.calendar.id,
            event.copy(title = "Renamed", start = event.start + 1.hours, end = event.end + 1.hours),
        )

        val second = fixture.rawRows().single()
        assertEquals("Renamed", second[EventsTable.title])
        assertEquals(event.start + 1.hours, second[EventsTable.startAt])
        assertNotEquals(first[EventsTable.etag], second[EventsTable.etag])
        assertEquals(first[EventsTable.createdAt], second[EventsTable.createdAt])
        assertTrue(second[EventsTable.updatedAt] >= first[EventsTable.updatedAt])
    }

    @Test
    fun deleteByUidsRemovesOnlyListedEvents() = testApplication {
        val fixture = installFixture()
        val first = importedEvent(uid = "discord:guild-1:event-1")
        val second = importedEvent(uid = "discord:guild-1:event-2")
        fixture.external.upsert(fixture.calendar.id, first)
        fixture.external.upsert(fixture.calendar.id, second)

        fixture.external.deleteByUids(fixture.calendar.id, listOf(first.uid))

        assertEquals(listOf(second.uid), fixture.external.listExternal(fixture.calendar.id).map { it.uid })
        assertEquals(1, fixture.rawRows().size)
    }

    @Test
    fun markCancelledSetsStatusWithoutRemovingTheRow() = testApplication {
        val fixture = installFixture()
        val event = importedEvent()
        fixture.external.upsert(fixture.calendar.id, event)
        val before = fixture.rawRows().single()

        fixture.external.markCancelled(fixture.calendar.id, event.uid)

        val after = fixture.rawRows().single()
        assertEquals(EventStatus.CANCELLED.name, after[EventsTable.status])
        assertNotEquals(before[EventsTable.etag], after[EventsTable.etag])
        assertEquals(EventStatus.CANCELLED, fixture.external.listExternal(fixture.calendar.id).single().status)
    }

    @Test
    fun mirroredCalendarRejectsLocalEventMutations() = testApplication {
        val fixture = installFixture()
        val event = importedEvent()
        fixture.external.upsert(fixture.calendar.id, event)
        val mirroredEventId = fixture.mirroredEventId()
        val destination = fixture.store.createCalendar(
            fixture.user.id,
            CreateCalendar(displayName = "Local"),
        )
        val localEvent = fixture.store.createEvent(
            destination.id,
            fixture.user.id,
            CreateEvent(title = "Local", start = start, end = end),
        )

        assertFailsWith<CalendarException.Forbidden> {
            fixture.store.createEvent(
                fixture.calendar.id,
                fixture.user.id,
                CreateEvent(title = "Nope", start = start, end = end),
            )
        }
        assertFailsWith<CalendarException.Forbidden> {
            fixture.store.updateEvent(mirroredEventId, fixture.user.id, UpdateEvent(title = "Nope"))
        }
        assertFailsWith<CalendarException.Forbidden> {
            fixture.store.deleteEvent(mirroredEventId, fixture.user.id)
        }
        assertFailsWith<CalendarException.Forbidden> {
            fixture.store.moveEvent(mirroredEventId, fixture.user.id, destination.id)
        }
        assertFailsWith<CalendarException.Forbidden> {
            fixture.store.moveEvent(localEvent.id, fixture.user.id, fixture.calendar.id)
        }
        assertEquals(1, fixture.store.listEvents(fixture.calendar.id, fixture.user.id).size)
    }

    @Test
    fun openRsvpOnMirroredCalendarIsRejected() = testApplication {
        val fixture = installFixture()
        fixture.external.upsert(fixture.calendar.id, importedEvent())
        val mirroredEventId = fixture.mirroredEventId()

        assertFailsWith<CalendarException.Forbidden> {
            fixture.invites.setOpenRsvp(mirroredEventId, fixture.user.id, enabled = true)
        }
    }

    private class Fixture(
        val store: CalendarStore,
        val external: ExternalEventStore,
        val invites: EventInviteService,
        val auth: AuthService,
        val database: Database,
        val user: User,
        val calendar: Calendar,
        val externalId: Uuid,
    ) {
        suspend fun rawRows(): List<ResultRow> = withContext(Dispatchers.IO) {
            suspendTransaction(database) {
                EventsTable.selectAll()
                    .where {
                        EventsTable.externalCalendarId eq Uuid.parse(externalId.toString())
                    }
                    .toList()
            }
        }

        suspend fun mirroredEventId(): EventId = EventId(rawRows().single()[EventsTable.id].toString())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.installFixture(): Fixture {
        lateinit var store: CalendarStore
        lateinit var external: ExternalEventStore
        lateinit var invites: EventInviteService
        lateinit var auth: AuthService
        lateinit var database: Database
        installApi(
            configure = {
                store = get()
                external = get()
                invites = get()
                auth = get()
                database = get()
            },
        )
        startApplication()
        val user = auth.register(RegisterUser(username = "alice", password = "password12"))
            .session?.user
            ?: error("registration did not create a session")
        val calendar = store.createCalendar(user.id, CreateCalendar(displayName = "Mirror"))
        val externalId = insertExternalCalendar(database, user.id, calendar.id)
        return Fixture(
            store = store,
            external = external,
            invites = invites,
            auth = auth,
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
                    it[ExternalCalendarsTable.calendarId] = Uuid.parse(calendarId.value)
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

    private fun importedEvent(uid: String = "discord:guild-1:event-1"): ImportedCalendarEvent =
        ImportedCalendarEvent(
            uid = uid,
            title = "Community call",
            description = "Discord event: https://discord.com/events/guild-1/event-1",
            location = "Voice Lounge",
            start = start,
            end = end,
            status = EventStatus.CONFIRMED,
        )

    private companion object {
        val start: Instant = Instant.parse("2026-10-01T10:00:00Z")
        val end: Instant = Instant.parse("2026-10-01T11:00:00Z")
    }
}
