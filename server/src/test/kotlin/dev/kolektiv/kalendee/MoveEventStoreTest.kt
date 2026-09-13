package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.InstantRange
import dev.kolektiv.kalendee.calendar.Recurrence
import dev.kolektiv.kalendee.calendar.RecurrenceFrequency
import dev.kolektiv.kalendee.db.EventAttendeesTable
import dev.kolektiv.kalendee.db.EventReminderSettingsTable
import dev.kolektiv.kalendee.db.EventRemindersTable
import dev.kolektiv.kalendee.db.EventsTable
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.ktor.ext.get

class MoveEventStoreTest {
    @Test
    fun ownerMovesNonRecurringEventBetweenOwnedCalendars() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        installApi(configure = { store = get(); auth = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val source = store.createCalendar(alice.id, CreateCalendar(displayName = "Source"))
        val destination = store.createCalendar(alice.id, CreateCalendar(displayName = "Destination"))
        val event = store.createEvent(source.id, alice.id, CreateEvent(title = "Standup", start = start, end = end))

        val result = store.moveEvent(event.id, alice.id, destination.id)
        val moved = requireNotNull(result).single()
        assertEquals(event.id, moved.id)
        assertEquals(destination.id, moved.calendarId)
        assertNotEquals(event.etag, moved.etag)

        assertEquals(destination.id, store.getEvent(event.id, alice.id)?.calendarId)
        assertTrue(store.listEvents(source.id, alice.id).isEmpty())
        assertEquals(listOf(event.id), store.listEvents(destination.id, alice.id).map { it.id })
    }

    @Test
    fun writeSharesOnBothCalendarsAllowMoveWithoutOwnership() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        installApi(configure = { store = get(); auth = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val source = store.createCalendar(alice.id, CreateCalendar(displayName = "Source"))
        val destination = store.createCalendar(alice.id, CreateCalendar(displayName = "Destination"))
        store.addShare(source.id, alice.id, bob.id, CalendarPermission.WRITE)
        store.addShare(destination.id, alice.id, bob.id, CalendarPermission.WRITE)
        val event = store.createEvent(source.id, alice.id, CreateEvent(title = "Standup", start = start, end = end))

        val result = store.moveEvent(event.id, bob.id, destination.id)
        val moved = requireNotNull(result).single()
        assertEquals(destination.id, moved.calendarId)
        assertEquals(destination.id, store.getEvent(event.id, bob.id)?.calendarId)
        assertTrue(store.listEvents(source.id, alice.id).isEmpty())
        assertEquals(listOf(event.id), store.listEvents(destination.id, bob.id).map { it.id })
    }

    @Test
    fun readOnlySourceIsForbidden() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        installApi(configure = { store = get(); auth = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val source = store.createCalendar(alice.id, CreateCalendar(displayName = "Source"))
        val destination = store.createCalendar(bob.id, CreateCalendar(displayName = "Destination"))
        store.addShare(source.id, alice.id, bob.id, CalendarPermission.READ)
        val event = store.createEvent(source.id, alice.id, CreateEvent(title = "Standup", start = start, end = end))

        assertFailsWith<CalendarException.Forbidden> {
            store.moveEvent(event.id, bob.id, destination.id)
        }
        assertEquals(source.id, store.getEvent(event.id, alice.id)?.calendarId)
    }

    @Test
    fun readOnlyDestinationIsForbidden() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        installApi(configure = { store = get(); auth = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val source = store.createCalendar(alice.id, CreateCalendar(displayName = "Source"))
        val destination = store.createCalendar(alice.id, CreateCalendar(displayName = "Destination"))
        store.addShare(source.id, alice.id, bob.id, CalendarPermission.WRITE)
        store.addShare(destination.id, alice.id, bob.id, CalendarPermission.READ)
        val event = store.createEvent(source.id, alice.id, CreateEvent(title = "Standup", start = start, end = end))

        assertFailsWith<CalendarException.Forbidden> {
            store.moveEvent(event.id, bob.id, destination.id)
        }
        assertEquals(source.id, store.getEvent(event.id, alice.id)?.calendarId)
    }

    @Test
    fun invisibleDestinationIsNotFound() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        installApi(configure = { store = get(); auth = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val source = store.createCalendar(alice.id, CreateCalendar(displayName = "Source"))
        val destination = store.createCalendar(alice.id, CreateCalendar(displayName = "Destination"))
        store.addShare(source.id, alice.id, bob.id, CalendarPermission.WRITE)
        val event = store.createEvent(source.id, alice.id, CreateEvent(title = "Standup", start = start, end = end))

        assertFailsWith<CalendarException.NotFound> {
            store.moveEvent(event.id, bob.id, destination.id)
        }
    }

    @Test
    fun sameCalendarIsInvalid() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        installApi(configure = { store = get(); auth = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val calendar = store.createCalendar(alice.id, CreateCalendar(displayName = "Source"))
        val event = store.createEvent(calendar.id, alice.id, CreateEvent(title = "Standup", start = start, end = end))

        assertFailsWith<CalendarException.Invalid> {
            store.moveEvent(event.id, alice.id, calendar.id)
        }
    }

    @Test
    fun staleEtagIsPreconditionFailed() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        installApi(configure = { store = get(); auth = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val source = store.createCalendar(alice.id, CreateCalendar(displayName = "Source"))
        val destination = store.createCalendar(alice.id, CreateCalendar(displayName = "Destination"))
        val event = store.createEvent(source.id, alice.id, CreateEvent(title = "Standup", start = start, end = end))

        assertFailsWith<CalendarException.PreconditionFailed> {
            store.moveEvent(event.id, alice.id, destination.id, expectedEtag = "stale")
        }
        assertEquals(source.id, store.getEvent(event.id, alice.id)?.calendarId)
    }

    @Test
    fun movingFollowingOccurrencesSplitsTheSeries() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        installApi(configure = { store = get(); auth = get() })
        startApplication()

        val alice = registerUser(auth, "alice")
        val source = store.createCalendar(alice.id, CreateCalendar(displayName = "Source"))
        val destination = store.createCalendar(alice.id, CreateCalendar(displayName = "Destination"))
        val event = store.createEvent(
            source.id,
            alice.id,
            CreateEvent(
                title = "Daily",
                start = start,
                end = end,
                recurrence = Recurrence(frequency = RecurrenceFrequency.DAILY, interval = 1, count = 5),
            ),
        )
        val range = InstantRange(
            start = Instant.parse("2026-09-01T00:00:00Z"),
            end = Instant.parse("2026-10-01T00:00:00Z"),
        )
        val original = store.listEvents(alice.id, range).map { it.start }
        assertEquals(5, original.size)
        val from = original[2]

        val result = requireNotNull(store.moveEvent(event.id, alice.id, destination.id, occurrenceStart = from))
        assertEquals(2, result.size)
        val truncated = result[0]
        val following = result[1]
        assertEquals(event.id, truncated.id)
        assertEquals(source.id, truncated.calendarId)
        assertEquals(2, truncated.recurrence?.count)
        assertEquals(3, following.recurrence?.count)
        assertEquals(from, following.start)
        assertEquals(destination.id, following.calendarId)
        assertNotEquals(event.id, following.id)

        val sourceOccurrences = store.listEvents(source.id, alice.id, range)
        assertEquals(2, sourceOccurrences.size)
        assertEquals(original.take(2), sourceOccurrences.map { it.start })
        val destinationOccurrences = store.listEvents(destination.id, alice.id, range)
        assertEquals(3, destinationOccurrences.size)
        assertEquals(original.drop(2), destinationOccurrences.map { it.start })

        assertEquals(original, store.listEvents(alice.id, range).map { it.start })
    }

    @Test
    fun splitCopiesAttendeesAndReminderSettingsWithoutTokens() = testApplication {
        lateinit var store: CalendarStore
        lateinit var auth: AuthService
        lateinit var database: Database
        installApi(
            configure = {
                store = get()
                auth = get()
                database = get()
            },
        )
        startApplication()

        val alice = registerUser(auth, "alice")
        val bob = registerUser(auth, "bob")
        val source = store.createCalendar(alice.id, CreateCalendar(displayName = "Source"))
        val destination = store.createCalendar(alice.id, CreateCalendar(displayName = "Destination"))
        val event = store.createEvent(
            source.id,
            alice.id,
            CreateEvent(
                title = "Daily",
                start = start,
                end = end,
                recurrence = Recurrence(frequency = RecurrenceFrequency.DAILY, interval = 1, count = 4),
            ),
        )
        val now = Clock.System.now()
        suspendTransaction(database) {
            EventAttendeesTable.insert {
                it[EventAttendeesTable.id] = Uuid.random()
                it[eventId] = Uuid.parse(event.id.value)
                it[userId] = Uuid.parse(bob.id.value)
                it[email] = "bob@example.com"
                it[name] = "Bob"
                it[status] = "yes"
                it[invitedBy] = Uuid.parse(alice.id.value)
                it[tokenHash] = "token-${event.id.value}"
                it[createdAt] = now
                it[respondedAt] = now
            }
            EventReminderSettingsTable.insert {
                it[userId] = Uuid.parse(alice.id.value)
                it[eventId] = Uuid.parse(event.id.value)
                it[useDefaults] = false
                it[updatedAt] = now
            }
            EventRemindersTable.insert {
                it[userId] = Uuid.parse(alice.id.value)
                it[eventId] = Uuid.parse(event.id.value)
                it[offsetSeconds] = 600
                it[createdAt] = now
            }
            EventsTable.update({ EventsTable.id eq Uuid.parse(event.id.value) }) {
                it[EventsTable.rsvpOverride] = false
                it[EventsTable.anonymousRsvpOverride] = true
            }
        }

        val result = requireNotNull(
            store.moveEvent(event.id, alice.id, destination.id, occurrenceStart = start + 1.days),
        )
        val newId = result[1].id
        assertEquals(false, result[1].rsvpOverride)
        assertEquals(true, result[1].anonymousRsvpOverride)
        assertEquals(false, result[1].rsvpEnabled)
        assertEquals(true, result[1].openRsvp)

        suspendTransaction(database) {
            val attendees = EventAttendeesTable.selectAll()
                .where { EventAttendeesTable.eventId eq Uuid.parse(newId.value) }
                .toList()
            assertEquals(1, attendees.size)
            val attendee = attendees.single()
            assertEquals(Uuid.parse(bob.id.value), attendee[EventAttendeesTable.userId])
            assertEquals("bob@example.com", attendee[EventAttendeesTable.email])
            assertEquals("yes", attendee[EventAttendeesTable.status])
            assertNull(attendee[EventAttendeesTable.tokenHash])

            val settings = EventReminderSettingsTable.selectAll()
                .where { EventReminderSettingsTable.eventId eq Uuid.parse(newId.value) }
                .toList()
            assertEquals(1, settings.size)
            assertFalse(settings.single()[EventReminderSettingsTable.useDefaults])

            val reminders = EventRemindersTable.selectAll()
                .where { EventRemindersTable.eventId eq Uuid.parse(newId.value) }
                .toList()
            assertEquals(1, reminders.size)
            assertEquals(600, reminders.single()[EventRemindersTable.offsetSeconds])

            val movedEvent = EventsTable.selectAll()
                .where { EventsTable.id eq Uuid.parse(newId.value) }
                .single()
            assertEquals(false, movedEvent[EventsTable.rsvpOverride])
            assertEquals(true, movedEvent[EventsTable.anonymousRsvpOverride])

            val sourceAttendees = EventAttendeesTable.selectAll()
                .where { EventAttendeesTable.eventId eq Uuid.parse(event.id.value) }
                .toList()
            assertEquals(1, sourceAttendees.size)
            assertEquals("token-${event.id.value}", sourceAttendees.single()[EventAttendeesTable.tokenHash])
        }
    }

    private suspend fun registerUser(auth: AuthService, username: String): User =
        auth.register(RegisterUser(username = username, password = "password12")).session?.user
            ?: error("registration did not create a session")

    private companion object {
        val start: Instant = Instant.parse("2026-09-07T10:00:00Z")
        val end: Instant = Instant.parse("2026-09-07T10:30:00Z")
    }
}
