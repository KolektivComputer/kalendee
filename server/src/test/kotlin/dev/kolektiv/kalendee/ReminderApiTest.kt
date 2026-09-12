package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.api.EventRemindersResponse
import dev.kolektiv.kalendee.api.HiddenBody
import dev.kolektiv.kalendee.api.ReminderInstanceResponse
import dev.kolektiv.kalendee.api.ReminderSettingsBody
import dev.kolektiv.kalendee.api.ReminderSettingsResponse
import dev.kolektiv.kalendee.api.SetEventRemindersBody
import dev.kolektiv.kalendee.api.ShareCalendarBody
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.Recurrence
import dev.kolektiv.kalendee.calendar.RecurrenceFrequency
import dev.kolektiv.kalendee.reminders.ReminderService
import dev.kolektiv.keel.Keel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.ktor.ext.get

class ReminderApiTest {
    @Test
    fun reminderSettingsRoundtripAndValidation() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()

        val saved = client.putReminderSettings(listOf(600, 3600), notifyAtStart = false)
        assertEquals(listOf(600, 3600), saved.defaultOffsetsSeconds)
        assertEquals(false, saved.notifyAtStart)

        val fetched = client.get("/api/v1/reminders/settings")
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertEquals(saved, fetched.body<ReminderSettingsResponse>())

        val normalized = client.putReminderSettings(listOf(0, 1200, 1200, 600), notifyAtStart = true)
        assertEquals(listOf(600, 1200), normalized.defaultOffsetsSeconds)
        assertTrue(normalized.notifyAtStart)

        val tooMany = client.putReminderSettingsRaw((1..11).map { it * 60 }, notifyAtStart = true)
        assertEquals(HttpStatusCode.BadRequest, tooMany.status)
        assertEquals("invalid", tooMany.body<ErrorBody>().error)

        assertEquals(
            HttpStatusCode.BadRequest,
            client.putReminderSettingsRaw(listOf(-60), notifyAtStart = true).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.putReminderSettingsRaw(listOf(31_536_001), notifyAtStart = true).status,
        )
    }

    @Test
    fun upcomingUsesDefaultsAndAtStartToggle() = testApplication {
        installApi()
        val now = Instant.parse("2026-09-07T12:00:00Z")
        var service: ReminderService? = null
        application {
            service = ReminderService(database = get(), store = get(), clock = fixedClock(now))
        }
        val client = jsonClient()
        val user = client.registerAndLogin()
        val calendar = client.newCalendar("Work")
        val event = client.newEvent(calendar.id.value, "Standup", now + 30.minutes, now + 60.minutes)
        client.putReminderSettings(listOf(600, 3600), notifyAtStart = true)

        val reminders = checkNotNull(service)
        val withAtStart = runBlocking { reminders.upcoming(user.id, 48) }
            .filter { it.eventId == event.id.value }
        assertEquals(listOf(3600, 600, 0), withAtStart.map { it.offsetSeconds })
        assertEquals(
            listOf(now - 30.minutes, now + 20.minutes, now + 30.minutes),
            withAtStart.map { it.remindAt },
        )

        val disabled = client.putReminderSettings(listOf(600, 3600), notifyAtStart = false)
        assertEquals(false, disabled.notifyAtStart)
        val withoutAtStart = runBlocking { reminders.upcoming(user.id, 48) }
            .filter { it.eventId == event.id.value }
        assertEquals(listOf(3600, 600), withoutAtStart.map { it.offsetSeconds })
    }

    @Test
    fun customEventRemindersReplaceDefaultsAndCanRestore() = testApplication {
        installApi()
        val now = Instant.parse("2026-09-07T12:00:00Z")
        var service: ReminderService? = null
        application {
            service = ReminderService(database = get(), store = get(), clock = fixedClock(now))
        }
        val client = jsonClient()
        val user = client.registerAndLogin()
        val calendar = client.newCalendar("Work")
        val event = client.newEvent(calendar.id.value, "Standup", now + 30.minutes, now + 60.minutes)
        client.putReminderSettings(listOf(600, 3600), notifyAtStart = false)

        val custom = client.setEventReminders(event.id.value, listOf(1200), useDefaults = false)
        assertEquals(listOf(1200), custom.offsetsSeconds)
        assertEquals(false, custom.useDefaults)
        assertEquals(listOf(600, 3600), custom.defaultOffsetsSeconds)

        val reminders = checkNotNull(service)
        val customInstances = runBlocking { reminders.upcoming(user.id, 48) }
            .filter { it.eventId == event.id.value }
        assertEquals(listOf(1200), customInstances.map { it.offsetSeconds })
        assertEquals(listOf(now + 10.minutes), customInstances.map { it.remindAt })

        val restored = client.setEventReminders(event.id.value, emptyList(), useDefaults = true)
        assertTrue(restored.useDefaults)
        assertEquals(emptyList(), restored.offsetsSeconds)

        val defaultInstances = runBlocking { reminders.upcoming(user.id, 48) }
            .filter { it.eventId == event.id.value }
        assertEquals(listOf(3600, 600), defaultInstances.map { it.offsetSeconds })

        val fetched = client.get("/api/v1/events/${event.id.value}/reminders")
        assertEquals(HttpStatusCode.OK, fetched.status)
        val fetchedBody = fetched.body<EventRemindersResponse>()
        assertEquals(emptyList(), fetchedBody.offsetsSeconds)
        assertTrue(fetchedBody.useDefaults)
    }

    @Test
    fun remindersRespectVisibilityAndArePerUser() = testApplication {
        installApi()
        val now = Instant.parse("2026-09-07T12:00:00Z")
        var service: ReminderService? = null
        application {
            service = ReminderService(database = get(), store = get(), clock = fixedClock(now))
        }
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        val bobUser = bob.registerAndLogin("bob")

        val shared = alice.newCalendar("Shared")
        val hidden = alice.newCalendar("Hidden")
        val sharedEvent = alice.newEvent(shared.id.value, "Shared standup", now + 30.minutes)
        val hiddenEvent = alice.newEvent(hidden.id.value, "Secret", now + 30.minutes)

        alice.post("/api/v1/calendars/${shared.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody(ShareCalendarBody(username = "bob", permission = "read"))
        }
        alice.put("/api/v1/calendars/${hidden.id.value}/hidden") {
            contentType(ContentType.Application.Json)
            setBody(HiddenBody(hidden = true))
        }
        alice.putReminderSettings(listOf(600), notifyAtStart = false)
        bob.putReminderSettings(listOf(1200), notifyAtStart = false)

        val reminders = checkNotNull(service)
        val aliceUpcoming = runBlocking { reminders.upcoming(aliceUser.id, 48) }
        assertEquals(listOf(sharedEvent.id.value), aliceUpcoming.map { it.eventId })
        assertEquals(listOf(600), aliceUpcoming.map { it.offsetSeconds })
        assertTrue(aliceUpcoming.none { it.eventId == hiddenEvent.id.value })

        val bobUpcoming = runBlocking { reminders.upcoming(bobUser.id, 48) }
        assertEquals(listOf(sharedEvent.id.value), bobUpcoming.map { it.eventId })
        assertEquals(listOf(1200), bobUpcoming.map { it.offsetSeconds })
    }

    @Test
    fun upcomingWindowBoundariesAndSorting() = testApplication {
        installApi()
        val now = Instant.parse("2026-09-07T12:00:00Z")
        var service: ReminderService? = null
        application {
            service = ReminderService(database = get(), store = get(), clock = fixedClock(now))
        }
        val client = jsonClient()
        val user = client.registerAndLogin()
        val calendar = client.newCalendar("Work")
        val lowerBoundary = client.newEvent(calendar.id.value, "Lower", now + 600.seconds, now + 1500.seconds)
        val justAfter = client.newEvent(calendar.id.value, "Just after", now + 601.seconds, now + 1501.seconds)
        val alreadyStarted = client.newEvent(calendar.id.value, "Before", now + 100.seconds, now + 1000.seconds)
        val endBoundary = client.newEvent(
            calendar.id.value,
            "End",
            now + 48.hours + 3600.seconds,
            now + 48.hours + 5400.seconds,
        )
        client.putReminderSettings(listOf(600, 7200), notifyAtStart = false)

        val instances = runBlocking { checkNotNull(service).upcoming(user.id, 48) }
        assertEquals(
            listOf(
                alreadyStarted.id.value to 7200,
                lowerBoundary.id.value to 7200,
                justAfter.id.value to 7200,
                alreadyStarted.id.value to 600,
                lowerBoundary.id.value to 600,
                justAfter.id.value to 600,
                endBoundary.id.value to 7200,
            ),
            instances.map { it.eventId to it.offsetSeconds },
        )
        assertEquals(
            listOf(
                now - 7100.seconds,
                now - 6600.seconds,
                now - 6599.seconds,
                now - 500.seconds,
                now,
                now + 1.seconds,
                now + 48.hours - 3600.seconds,
            ),
            instances.map { it.remindAt },
        )
        assertTrue(instances.map { it.eventId }.contains(alreadyStarted.id.value))
    }

    @Test
    fun overdueRemindersFireAndFarEventsStayOutOfWindow() = testApplication {
        installApi()
        val now = Instant.parse("2026-09-07T12:00:00Z")
        var service: ReminderService? = null
        application {
            service = ReminderService(database = get(), store = get(), clock = fixedClock(now))
        }
        val client = jsonClient()
        val user = client.registerAndLogin()
        val calendar = client.newCalendar("Work")
        val soon = client.newEvent(calendar.id.value, "Soon", now + 5.minutes, now + 35.minutes)
        val far = client.newEvent(calendar.id.value, "Far", now + 8.hours, now + 9.hours)
        client.putReminderSettings(listOf(600), notifyAtStart = false)

        val instances = runBlocking { checkNotNull(service).upcoming(user.id, 1) }
        val soonInstances = instances.filter { it.eventId == soon.id.value }
        assertEquals(1, soonInstances.size)
        assertEquals(now - 5.minutes, soonInstances.single().remindAt)
        assertTrue(instances.none { it.eventId == far.id.value })
    }

    @Test
    fun recurringOccurrencesProduceReminders() = testApplication {
        installApi()
        val now = Instant.parse("2026-09-07T12:00:00Z")
        var service: ReminderService? = null
        application {
            service = ReminderService(database = get(), store = get(), clock = fixedClock(now))
        }
        val client = jsonClient()
        val user = client.registerAndLogin()
        val calendar = client.newCalendar("Work")
        val event = client.newEvent(
            calendar.id.value,
            "Daily standup",
            now + 2.hours,
            now + 2.hours + 30.minutes,
            recurrence = Recurrence(frequency = RecurrenceFrequency.DAILY, interval = 1, count = 3),
        )
        client.putReminderSettings(listOf(600), notifyAtStart = false)

        val instances = runBlocking { checkNotNull(service).upcoming(user.id, 48) }
        assertEquals(2, instances.size)
        assertEquals(listOf(event.id.value, event.id.value), instances.map { it.eventId })
        assertEquals(listOf(600, 600), instances.map { it.offsetSeconds })
        assertEquals(
            listOf(now + 1.hours + 50.minutes, now + 25.hours + 50.minutes),
            instances.map { it.remindAt },
        )
        assertEquals(
            listOf(now + 2.hours, now + 26.hours),
            instances.map { it.start },
        )
    }

    @Test
    fun upcomingApiListsDueReminders() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val calendar = client.newCalendar("Work")
        val start = Instant.fromEpochSeconds((Clock.System.now() + 30.minutes).epochSeconds)
        val event = client.newEvent(calendar.id.value, "Standup", start, start + 30.minutes)
        client.putReminderSettings(listOf(600), notifyAtStart = false)

        val response = client.get("/api/v1/reminders/upcoming")
        assertEquals(HttpStatusCode.OK, response.status)
        val instance = response.body<List<ReminderInstanceResponse>>()
            .single { it.eventId == event.id.value }
        assertEquals(600, instance.offsetSeconds)
        assertEquals("Work", instance.calendarName)
        assertEquals(calendar.color, instance.calendarColor)
        assertEquals(start.toString(), instance.start)
        assertEquals((start - 10.minutes).toString(), instance.remindAt)
        assertEquals(false, instance.allDay)

        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/v1/reminders/upcoming?hours=nope").status,
        )
    }

    @Test
    fun updateReminderSettingsActionReportsOffsetsError() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val response = client.post("${Keel.ACTION_PATH}/kalendee.updateReminderSettings") {
            contentType(ContentType.Application.Json)
            setBody("""{"defaultOffsetsSeconds":[-60],"notifyAtStart":true}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(response.bodyAsText().contains("\"offsets\""))
    }

    @Test
    fun remindersRequireLogin() = testApplication {
        installApi()
        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().get("/api/v1/reminders/settings").status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().get("/api/v1/reminders/upcoming").status,
        )
    }
}

private fun fixedClock(now: Instant): Clock = object : Clock {
    override fun now(): Instant = now
}

private suspend fun HttpClient.putReminderSettings(
    offsets: List<Int>,
    notifyAtStart: Boolean,
): ReminderSettingsResponse {
    val response = putReminderSettingsRaw(offsets, notifyAtStart)
    check(response.status == HttpStatusCode.OK) { "update reminder settings failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.putReminderSettingsRaw(
    offsets: List<Int>,
    notifyAtStart: Boolean,
): HttpResponse = put("/api/v1/reminders/settings") {
    contentType(ContentType.Application.Json)
    setBody(ReminderSettingsBody(defaultOffsetsSeconds = offsets, notifyAtStart = notifyAtStart))
}

private suspend fun HttpClient.setEventReminders(
    eventId: String,
    offsets: List<Int>,
    useDefaults: Boolean,
): EventRemindersResponse {
    val response = put("/api/v1/events/$eventId/reminders") {
        contentType(ContentType.Application.Json)
        setBody(SetEventRemindersBody(offsetsSeconds = offsets, useDefaults = useDefaults))
    }
    check(response.status == HttpStatusCode.OK) { "set event reminders failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.newCalendar(name: String): Calendar {
    val response = post("/api/v1/calendars") {
        contentType(ContentType.Application.Json)
        setBody(CreateCalendar(displayName = name))
    }
    check(response.status == HttpStatusCode.Created) { "create calendar failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.newEvent(
    calendarId: String,
    title: String,
    start: Instant,
    end: Instant = start + 30.minutes,
    recurrence: Recurrence? = null,
): Event {
    val response = post("/api/v1/calendars/$calendarId/events") {
        contentType(ContentType.Application.Json)
        setBody(CreateEvent(title = title, start = start, end = end, recurrence = recurrence))
    }
    check(response.status == HttpStatusCode.Created) { "create event failed: ${response.status}" }
    return response.body()
}
