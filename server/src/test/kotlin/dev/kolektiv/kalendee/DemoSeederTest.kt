package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.availability.AvailabilityService
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.EventRsvpStatus
import dev.kolektiv.kalendee.calendar.RecurrenceFrequency
import dev.kolektiv.kalendee.demo.DemoSeeder
import dev.kolektiv.kalendee.events.EventInviteService
import dev.kolektiv.kalendee.friends.FriendshipService
import dev.kolektiv.kalendee.notifications.NotificationService
import dev.kolektiv.kalendee.reminders.ReminderService
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.ktor.ext.get

class DemoSeederTest {
    @Test
    fun seedsRichDemoDataOnceAndIsIdempotent() = testApplication {
        lateinit var seeder: DemoSeeder
        lateinit var auth: AuthService
        lateinit var store: CalendarStore
        lateinit var notifications: NotificationService
        lateinit var friendships: FriendshipService
        lateinit var reminders: ReminderService
        lateinit var availability: AvailabilityService
        lateinit var invites: EventInviteService
        installApi(
            configure = {
                seeder = get()
                auth = get()
                store = get()
                notifications = get()
                friendships = get()
                reminders = get()
                availability = get()
                invites = get()
            },
        )
        startApplication()

        val first = runBlocking { seeder.seed() }
        assertTrue(first, "first seed should create demo data")

        val demo = runBlocking { auth.userByIdentifier("demo") }
        assertNotNull(demo)
        assertEquals("Demo User", demo.displayName)
        assertTrue(demo.emailVerified)
        assertFalse(demo.admin)

        val sam = runBlocking { auth.userByIdentifier("sam") }
        assertNotNull(sam)
        val alex = runBlocking { auth.userByIdentifier("alex") }
        assertNotNull(alex)

        val calendars = runBlocking { store.listCalendars(demo.id) }
        val owned = calendars.filter { it.ownerId == demo.id }
        assertTrue(owned.size >= 4, "expected at least 4 owned calendars, got ${owned.size}")
        assertEquals(
            owned.size,
            owned.map { it.color }.distinct().size,
            "demo calendars should use distinct colors",
        )

        val eventsByCalendar = runBlocking {
            owned.associate { calendar ->
                calendar.displayName to store.listEvents(calendar.id, demo.id)
            }
        }
        val summary = eventsByCalendar.entries.joinToString(", ") { "${it.key}=${it.value.size}" }
        println("demo event counts: $summary")

        val totalEvents = eventsByCalendar.values.sumOf { it.size }
        assertTrue(totalEvents >= 15, "expected a rich event set, got $totalEvents")
        for (name in listOf("Work", "Personal", "Family", "Birthdays")) {
            assertTrue(eventsByCalendar[name].orEmpty().isNotEmpty(), "$name should have events")
        }

        val zone = TimeZone.of("Europe/Berlin")
        val today = Clock.System.now().toLocalDateTime(zone).date
        val eventsToday = eventsByCalendar.values.flatten().filter { event ->
            event.start.toLocalDateTime(zone).date == today
        }
        assertTrue(eventsToday.isNotEmpty(), "there should be at least one event on today")

        val work = owned.first { it.displayName == "Work" }
        val workEvents = eventsByCalendar.getValue("Work")
        val lakeTrip = eventsByCalendar.getValue("Family").first { it.title == "Weekend trip to the lake" }
        assertTrue(lakeTrip.allDay, "the weekend trip should be an all-day event")
        val birthday = eventsByCalendar.getValue("Birthdays").first { it.title == "Sam's birthday" }
        assertTrue(birthday.allDay, "Sam's birthday should be an all-day event")
        assertEquals(RecurrenceFrequency.YEARLY, birthday.recurrence?.frequency)

        assertTrue(
            runBlocking { notifications.unreadCount(demo.id) } > 0,
            "demo should have unread notifications",
        )
        assertNotNull(runBlocking { auth.avatarKey(demo.id) }, "demo avatar should be seeded")

        val shares = runBlocking { store.listShares(work.id, demo.id) }
        assertTrue(
            shares.any { it.userId == sam.id && it.permission == CalendarPermission.READ },
            "Work should be shared read-only with sam",
        )

        val followed = calendars.firstOrNull { it.displayName == "Community events" }
        assertNotNull(followed, "demo should follow sam's Community events calendar")
        assertEquals(CalendarPermission.FOLLOW, followed.permission)
        val community = runBlocking { store.listCalendars(sam.id) }
            .first { it.displayName == "Community events" }
        assertTrue(community.publicLinkEnabled, "Community events should have a public link")
        assertNotNull(community.publicLinkToken)
        assertTrue(runBlocking { store.listEvents(community.id, sam.id) }.size >= 2)

        assertTrue(
            runBlocking { friendships.friends(demo.id) }.any { it.username == "alex" },
            "demo and alex should be friends",
        )
        assertTrue(
            runBlocking { friendships.incomingRequests(demo.id) }.any { it.user.username == "sam" },
            "sam's friend request should be pending for demo",
        )

        val reminderSettings = runBlocking { reminders.settings(demo.id) }
        assertEquals(listOf(600, 3600), reminderSettings.defaultOffsetsSeconds)
        assertTrue(reminderSettings.notifyAtStart)
        val designReview = workEvents.first { it.title == "Design review" }
        val eventReminders = runBlocking { reminders.eventReminders(demo.id, designReview.id) }
        assertEquals(listOf(1800), eventReminders.offsetsSeconds)
        assertFalse(eventReminders.useDefaults)

        val holidayPrefs = runBlocking { store.holidayPrefs(demo.id) }
        assertTrue(holidayPrefs.showHolidays)
        assertTrue(holidayPrefs.subscribedIds.contains("christmas-day"))

        val availabilitySettings = runBlocking { availability.settings(work.id, demo.id) }
        assertTrue(availabilitySettings.requestsEnabled)
        assertEquals(30, availabilitySettings.slotMinutes)

        val designReviewAttendees = runBlocking { invites.attendees(designReview.id, demo.id) }
        assertTrue(
            designReviewAttendees.attendees.any {
                it.userId == sam.id && it.status == EventRsvpStatus.MAYBE.wire
            },
            "sam should have responded maybe to the design review",
        )
        val productSync = workEvents.first { it.title == "Product sync" }
        val productSyncAttendees = runBlocking { invites.attendees(productSync.id, demo.id) }
        assertTrue(
            productSyncAttendees.attendees.any {
                it.userId == demo.id && it.status == EventRsvpStatus.INVITED.wire
            },
            "sam's invitation to demo should still be pending",
        )

        val demoClient = jsonClient()
        demoClient.login("demo", "demo")
        val home = demoClient.get("/") {
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, home.status)
        val homeSeed = home.bodyAsText()
        assertTrue(homeSeed.contains("Team standup"), "week view should contain the standup")
        assertTrue(homeSeed.contains("Focus time"), "week view should contain today's focus block")

        val second = runBlocking { seeder.seed() }
        assertFalse(second, "second seed should be a no-op")

        val calendarsAgain = runBlocking { store.listCalendars(demo.id) }
        assertEquals(calendars.size, calendarsAgain.size, "calendar count must not change on re-seed")
        val eventCountsAgain = runBlocking {
            calendarsAgain.filter { it.ownerId == demo.id }.associate { calendar ->
                calendar.displayName to store.listEvents(calendar.id, demo.id).size
            }
        }
        assertEquals(
            eventsByCalendar.mapValues { it.value.size },
            eventCountsAgain,
            "event counts must not change on re-seed",
        )
    }
}
