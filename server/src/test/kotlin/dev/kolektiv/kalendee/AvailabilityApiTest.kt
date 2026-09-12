package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AvailabilityWindowBody
import dev.kolektiv.kalendee.api.NotificationOut
import dev.kolektiv.kalendee.api.PublicLinkBody
import dev.kolektiv.kalendee.api.RespondTimeSlotBody
import dev.kolektiv.kalendee.api.ShareCalendarBody
import dev.kolektiv.kalendee.api.UpdateAvailabilityBody
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.web.CalendarAvailabilityOut
import dev.kolektiv.kalendee.web.CalendarSharingOut
import dev.kolektiv.kalendee.web.CalendarSlotsOut
import dev.kolektiv.kalendee.web.FollowOut
import dev.kolektiv.kalendee.web.TimeSlotRequestSummary
import dev.kolektiv.keel.Keel
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AvailabilityApiTest {
    @Test
    fun ownerSetsOfficeHoursAndSlotsSplitIntoAvailableSlots() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")

        val updated = alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(workWeek(enabled = true))
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        val availability = updated.body<CalendarAvailabilityOut>()
        assertTrue(availability.requestsEnabled)
        assertEquals(60, availability.slotMinutes)
        assertEquals("inherit", availability.accessMode)
        assertEquals("UTC", availability.timeZone)
        assertEquals(5, availability.windows.size)

        val loaded = alice.get("/api/v1/calendars/${calendar.id.value}/availability")
        assertEquals(HttpStatusCode.OK, loaded.status)
        assertEquals(availability, loaded.body<CalendarAvailabilityOut>())

        val slots = alice.slots(calendar.id.value, monday)
        assertEquals("UTC", slots.timeZone)
        assertTrue(slots.requestsEnabled)
        val day = slots.days.single()
        assertEquals(monday, day.date)
        assertEquals(8, day.slots.size)
        assertEquals("2030-01-07T09:00:00Z", day.slots.first().start)
        assertEquals("2030-01-07T10:00:00Z", day.slots.first().end)
        assertEquals("2030-01-07T17:00:00Z", day.slots.last().end)
        assertTrue(day.slots.all { it.available })
    }

    @Test
    fun eventsOnCalendarAndOwnerCalendarsBlockSlots() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val work = alice.createCalendar("Work")
        val personal = alice.createCalendar("Personal")
        alice.put("/api/v1/calendars/${work.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(workWeek(enabled = true))
        }
        alice.createEvent(
            work.id.value,
            "Standup",
            Instant.parse("2030-01-07T10:00:00Z"),
            Instant.parse("2030-01-07T11:00:00Z"),
        )
        alice.createEvent(
            personal.id.value,
            "Dentist",
            Instant.parse("2030-01-07T14:00:00Z"),
            Instant.parse("2030-01-07T15:00:00Z"),
        )

        val day = alice.slots(work.id.value, monday).days.single()
        assertEquals(8, day.slots.size)
        assertEquals(listOf("09:00", "11:00", "12:00", "13:00", "15:00", "16:00"), day.slots.filter { it.available }.map { it.start.substring(11, 16) })
        assertFalse(day.slots.single { it.start == "2030-01-07T10:00:00Z" }.available)
        assertFalse(day.slots.single { it.start == "2030-01-07T14:00:00Z" }.available)
    }

    @Test
    fun allDayEventBlocksTheDay() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")
        alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(workWeek(enabled = true))
        }
        alice.createEvent(
            calendar.id.value,
            "Holiday",
            Instant.parse("2030-01-07T00:00:00Z"),
            Instant.parse("2030-01-08T00:00:00Z"),
            allDay = true,
        )

        val day = alice.slots(calendar.id.value, monday).days.single()
        assertEquals(8, day.slots.size)
        assertTrue(day.slots.none { it.available })
    }

    @Test
    fun invalidWindowsAreRejectedAndNonOwnerCannotUpdate() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val calendar = alice.createCalendar("Work")
        alice.post("/api/v1/calendars/${calendar.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody(ShareCalendarBody(username = "bob", permission = "read"))
        }

        val overlapping = alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateAvailabilityBody(
                    requestsEnabled = true,
                    slotMinutes = 60,
                    windows = listOf(
                        AvailabilityWindowBody(0, 540, 1020),
                        AvailabilityWindowBody(0, 960, 1080),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, overlapping.status)

        val inverted = alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateAvailabilityBody(
                    requestsEnabled = true,
                    slotMinutes = 60,
                    windows = listOf(AvailabilityWindowBody(0, 600, 540)),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, inverted.status)

        val shortSlots = alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateAvailabilityBody(
                    requestsEnabled = true,
                    slotMinutes = 5,
                    windows = listOf(AvailabilityWindowBody(0, 540, 1020)),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, shortSlots.status)

        val badWeekday = alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateAvailabilityBody(
                    requestsEnabled = true,
                    slotMinutes = 60,
                    windows = listOf(AvailabilityWindowBody(7, 540, 1020)),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, badWeekday.status)

        val denied = bob.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(workWeek(enabled = true))
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)
    }

    @Test
    fun readShareAndFollowerCanRequestSlots() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        val alice = jsonClient()
        alice.registerWithEmail("alice", "alice@example.com")
        val bob = jsonClient()
        bob.registerWithEmail("bob", "bob@example.com")
        val calendar = alice.createCalendar("Work")
        alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(workWeek(enabled = true))
        }
        alice.post("/api/v1/calendars/${calendar.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody(ShareCalendarBody(username = "bob", permission = "read"))
        }
        mail.clear()

        val created = bob.post("/api/v1/calendars/${calendar.id.value}/slot-requests") {
            contentType(ContentType.Application.Json)
            setBody(
                dev.kolektiv.kalendee.api.RequestTimeSlotBody(
                    start = Instant.parse("2030-01-07T09:00:00Z"),
                    end = Instant.parse("2030-01-07T10:00:00Z"),
                    message = "Can we sync?",
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val request = created.body<TimeSlotRequestSummary>()
        assertEquals("pending", request.status)
        assertEquals("bob", request.requesterUsername)
        assertEquals("bob", request.requesterName)

        val ownerNotifications = alice.get("/api/v1/notifications").body<List<NotificationOut>>()
        assertTrue(ownerNotifications.any { it.kind == "slot.request" && "bob" in it.title })
        assertTrue(mail.sent.any { it.to == "alice@example.com" && "Can we sync?" in it.text })

        val outside = bob.post("/api/v1/calendars/${calendar.id.value}/slot-requests") {
            contentType(ContentType.Application.Json)
            setBody(
                dev.kolektiv.kalendee.api.RequestTimeSlotBody(
                    start = Instant.parse("2030-01-07T18:00:00Z"),
                    end = Instant.parse("2030-01-07T19:00:00Z"),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, outside.status)

        val duplicate = bob.post("/api/v1/calendars/${calendar.id.value}/slot-requests") {
            contentType(ContentType.Application.Json)
            setBody(
                dev.kolektiv.kalendee.api.RequestTimeSlotBody(
                    start = Instant.parse("2030-01-07T09:30:00Z"),
                    end = Instant.parse("2030-01-07T10:30:00Z"),
                ),
            )
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)

        val disabledCalendar = alice.createCalendar("Closed")
        alice.post("/api/v1/calendars/${disabledCalendar.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody(ShareCalendarBody(username = "bob", permission = "read"))
        }
        val disabled = bob.post("/api/v1/calendars/${disabledCalendar.id.value}/slot-requests") {
            contentType(ContentType.Application.Json)
            setBody(
                dev.kolektiv.kalendee.api.RequestTimeSlotBody(
                    start = Instant.parse("2030-01-07T09:00:00Z"),
                    end = Instant.parse("2030-01-07T10:00:00Z"),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, disabled.status)

        val followerCalendar = alice.createCalendar("Followable")
        alice.put("/api/v1/calendars/${followerCalendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(workWeek(enabled = true))
        }
        val token = alice.enablePublicLink(followerCalendar.id.value)
        val followed = bob.post("/api/v1/public/calendars/$token/follow")
        assertEquals(HttpStatusCode.OK, followed.status)
        assertEquals(FollowOut(followerCalendar.id.value, true), followed.body<FollowOut>())
        assertEquals(
            CalendarPermission.FOLLOW,
            bob.get("/api/v1/calendars").body<List<Calendar>>()
                .single { it.id == followerCalendar.id }.permission,
        )
        val followerRequest = bob.post("/api/v1/calendars/${followerCalendar.id.value}/slot-requests") {
            contentType(ContentType.Application.Json)
            setBody(
                dev.kolektiv.kalendee.api.RequestTimeSlotBody(
                    start = Instant.parse("2030-01-07T13:00:00Z"),
                    end = Instant.parse("2030-01-07T14:00:00Z"),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, followerRequest.status)
    }

    @Test
    fun ownerSeesRequestsAndCanAcceptOrDecline() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        val alice = jsonClient()
        alice.registerWithEmail("alice", "alice@example.com")
        val bob = jsonClient()
        bob.registerWithEmail("bob", "bob@example.com")
        val calendar = alice.createCalendar("Work")
        alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(workWeek(enabled = true))
        }
        alice.post("/api/v1/calendars/${calendar.id.value}/shares") {
            contentType(ContentType.Application.Json)
            setBody(ShareCalendarBody(username = "bob", permission = "read"))
        }
        mail.clear()

        val first = bob.requestSlot(calendar.id.value, "2030-01-07T09:00:00Z", "2030-01-07T10:00:00Z")
        val second = bob.requestSlot(calendar.id.value, "2030-01-07T15:00:00Z", "2030-01-07T16:00:00Z")

        val listed = alice.get("/api/v1/calendars/${calendar.id.value}/slot-requests")
        assertEquals(HttpStatusCode.OK, listed.status)
        val requests = listed.body<List<TimeSlotRequestSummary>>()
        assertEquals(listOf(second.id, first.id), requests.map { it.id })

        val accepted = alice.post("/api/v1/slot-requests/${first.id}/respond") {
            contentType(ContentType.Application.Json)
            setBody(RespondTimeSlotBody(accept = true, message = "See you then"))
        }
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals("accepted", accepted.body<TimeSlotRequestSummary>().status)

        val events = alice.get("/api/v1/calendars/${calendar.id.value}/events").body<List<Event>>()
        val meeting = events.single()
        assertTrue("bob" in meeting.title)
        assertEquals(Instant.parse("2030-01-07T09:00:00Z"), meeting.start)
        assertEquals(Instant.parse("2030-01-07T10:00:00Z"), meeting.end)

        assertTrue(
            bob.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "slot.accepted" },
        )
        assertTrue(mail.sent.any { it.to == "bob@example.com" && "accepted" in it.subject })

        val declined = alice.post("/api/v1/slot-requests/${second.id}/respond") {
            contentType(ContentType.Application.Json)
            setBody(RespondTimeSlotBody(accept = false))
        }
        assertEquals(HttpStatusCode.OK, declined.status)
        assertEquals("declined", declined.body<TimeSlotRequestSummary>().status)
        assertTrue(
            bob.get("/api/v1/notifications").body<List<NotificationOut>>()
                .any { it.kind == "slot.declined" },
        )
        assertTrue(mail.sent.any { it.to == "bob@example.com" && "declined" in it.subject })

        val again = alice.post("/api/v1/slot-requests/${first.id}/respond") {
            contentType(ContentType.Application.Json)
            setBody(RespondTimeSlotBody(accept = true))
        }
        assertEquals(HttpStatusCode.Conflict, again.status)
    }

    @Test
    fun anonymousPublicSlotRequestsAndOwnerAcceptance() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail)
        val alice = jsonClient()
        alice.registerWithEmail("alice", "alice@example.com")
        val calendar = alice.createCalendar("Work")
        alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(workWeek(enabled = true).copy(accessMode = "public"))
        }
        val token = alice.enablePublicLink(calendar.id.value)
        mail.clear()

        val created = jsonClient().publicRequestSlot(
            token = token,
            name = "Guest",
            email = "guest@example.com",
            start = "2030-01-07T09:00:00Z",
            end = "2030-01-07T10:00:00Z",
            message = "Can we sync?",
        )
        assertEquals(HttpStatusCode.Created, created.status)
        val request = created.body<TimeSlotRequestSummary>()
        assertEquals("pending", request.status)
        assertEquals("Guest", request.requesterName)
        assertEquals("guest@example.com", request.requesterEmail)
        assertNull(request.requesterUserId)

        val ownerNotifications = alice.get("/api/v1/notifications").body<List<NotificationOut>>()
        assertTrue(ownerNotifications.any { it.kind == "slot.request" && "Guest" in it.title })
        assertTrue(mail.sent.any { it.to == "alice@example.com" && "Guest" in it.subject })

        val listed = alice.get("/api/v1/calendars/${calendar.id.value}/slot-requests")
        assertEquals(HttpStatusCode.OK, listed.status)
        val ownerRequest = listed.body<List<TimeSlotRequestSummary>>().single()
        assertEquals("Guest", ownerRequest.requesterName)
        assertEquals("guest@example.com", ownerRequest.requesterEmail)
        assertNull(ownerRequest.requesterUserId)

        val accepted = alice.post("/api/v1/slot-requests/${request.id}/respond") {
            contentType(ContentType.Application.Json)
            setBody(RespondTimeSlotBody(accept = true))
        }
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals("accepted", accepted.body<TimeSlotRequestSummary>().status)

        val events = alice.get("/api/v1/calendars/${calendar.id.value}/events").body<List<Event>>()
        val meeting = events.single()
        assertEquals("Meeting with Guest", meeting.title)
        assertEquals(Instant.parse("2030-01-07T09:00:00Z"), meeting.start)
        assertTrue(mail.sent.any { it.to == "guest@example.com" && "accepted" in it.subject })
    }

    @Test
    fun publicSlotRequestsRespectRequestsEnabledAndAccess() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")
        val token = alice.enablePublicLink(calendar.id.value)

        val disabled = jsonClient().publicRequestSlot(
            token = token,
            name = "Guest",
            email = null,
            start = "2030-01-07T09:00:00Z",
            end = "2030-01-07T10:00:00Z",
        )
        assertEquals(HttpStatusCode.BadRequest, disabled.status)

        alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(workWeek(enabled = true).copy(accessMode = "signed_in"))
        }

        val anonymous = jsonClient().publicRequestSlot(
            token = token,
            name = "Guest",
            email = "guest@example.com",
            start = "2030-01-07T09:00:00Z",
            end = "2030-01-07T10:00:00Z",
        )
        assertEquals(HttpStatusCode.NotFound, anonymous.status)

        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val signedIn = bob.publicRequestSlot(
            token = token,
            name = "Bob",
            email = "bob@example.com",
            start = "2030-01-07T11:00:00Z",
            end = "2030-01-07T12:00:00Z",
        )
        assertEquals(HttpStatusCode.Created, signedIn.status)
        assertEquals("Bob", signedIn.body<TimeSlotRequestSummary>().requesterName)
    }

    @Test
    fun publicSlotRequestsDedupeByEmail() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")
        alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(workWeek(enabled = true).copy(accessMode = "public"))
        }
        val token = alice.enablePublicLink(calendar.id.value)

        assertEquals(
            HttpStatusCode.Created,
            jsonClient().publicRequestSlot(
                token = token,
                name = "Guest",
                email = "guest@example.com",
                start = "2030-01-07T09:00:00Z",
                end = "2030-01-07T10:00:00Z",
            ).status,
        )
        assertEquals(
            HttpStatusCode.Conflict,
            jsonClient().publicRequestSlot(
                token = token,
                name = "Guest Again",
                email = "GUEST@example.com",
                start = "2030-01-07T09:30:00Z",
                end = "2030-01-07T10:30:00Z",
            ).status,
        )
        assertEquals(
            HttpStatusCode.Created,
            jsonClient().publicRequestSlot(
                token = token,
                name = "Anon",
                email = null,
                start = "2030-01-07T09:30:00Z",
                end = "2030-01-07T10:30:00Z",
            ).status,
        )
    }

    @Test
    fun publicRequestTimeSlotKeelActionWorksWithoutSession() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val calendar = alice.createCalendar("Work")
        alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(workWeek(enabled = true).copy(accessMode = "public"))
        }
        val token = alice.enablePublicLink(calendar.id.value)

        val response = jsonClient().post("${Keel.ACTION_PATH}/kalendee.publicRequestTimeSlot") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"calendarToken":"$token","name":"Guest","email":"guest@example.com",""" +
                    """"start":"2030-01-07T09:00:00Z","end":"2030-01-07T10:00:00Z"}""",
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"pending\""))
    }

    @Test
    fun privacyGatesAnonymousPublicSurfaces() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val calendar = alice.createCalendar("Work")
        val token = alice.enablePublicLink(calendar.id.value)

        assertEquals(HttpStatusCode.OK, anonymousPage(token).status)
        assertEquals(HttpStatusCode.OK, jsonClient().get("/rss/$token.xml").status)
        assertEquals(
            HttpStatusCode.OK,
            jsonClient().get("/api/v1/public/calendars/$token").status,
        )

        assertEquals(
            HttpStatusCode.OK,
            alice.post("${Keel.ACTION_PATH}/kalendee.setPublicAccess") {
                contentType(ContentType.Application.Json)
                setBody("""{"mode":"signed_in"}""")
            }.status,
        )

        assertEquals(HttpStatusCode.NotFound, anonymousPage(token).status)
        assertEquals(HttpStatusCode.NotFound, jsonClient().get("/rss/$token.xml").status)
        assertEquals(HttpStatusCode.NotFound, jsonClient().get("/api/v1/public/calendars/$token").status)
        assertEquals(
            HttpStatusCode.NotFound,
            jsonClient().get("/api/v1/public/calendars/$token/events").status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            jsonClient().get("/api/v1/public/calendars/$token/slots?from=$monday&to=$monday").status,
        )

        assertEquals(HttpStatusCode.OK, bobPage(bob, token).status)
        assertEquals(HttpStatusCode.OK, bob.get("/rss/$token.xml").status)
        assertEquals(HttpStatusCode.OK, bob.get("/api/v1/public/calendars/$token").status)
        assertEquals(
            HttpStatusCode.OK,
            bob.get("/api/v1/public/calendars/$token/slots?from=$monday&to=$monday").status,
        )

        alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateAvailabilityBody(
                    requestsEnabled = false,
                    slotMinutes = 60,
                    accessMode = "public",
                    windows = emptyList(),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, anonymousPage(token).status)
        assertEquals(
            HttpStatusCode.OK,
            jsonClient().get("/api/v1/public/calendars/$token/slots?from=$monday&to=$monday").status,
        )

        alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateAvailabilityBody(
                    requestsEnabled = false,
                    slotMinutes = 60,
                    accessMode = "inherit",
                    windows = emptyList(),
                ),
            )
        }
        val userOverride = alice.post("${Keel.ACTION_PATH}/kalendee.setUserPublicAccess") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"public"}""")
        }
        assertEquals(HttpStatusCode.OK, userOverride.status)
        assertTrue(userOverride.bodyAsText().contains("\"publicAccess\":\"public\""))
        assertEquals(HttpStatusCode.OK, anonymousPage(token).status)

        alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateAvailabilityBody(
                    requestsEnabled = false,
                    slotMinutes = 60,
                    accessMode = "signed_in",
                    windows = emptyList(),
                ),
            )
        }
        assertEquals(HttpStatusCode.NotFound, anonymousPage(token).status)
        assertEquals(HttpStatusCode.OK, bobPage(bob, token).status)

        val denied = bob.post("${Keel.ACTION_PATH}/kalendee.setPublicAccess") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"public"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, denied.status)

        alice.put("/api/v1/calendars/${calendar.id.value}/availability") {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateAvailabilityBody(
                    requestsEnabled = false,
                    slotMinutes = 60,
                    accessMode = "inherit",
                    windows = emptyList(),
                ),
            )
        }
        val resetUser = alice.post("${Keel.ACTION_PATH}/kalendee.setUserPublicAccess") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"inherit"}""")
        }
        assertEquals(HttpStatusCode.OK, resetUser.status)

        val roundtrip = alice.post("${Keel.ACTION_PATH}/kalendee.setPublicAccess") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"public"}""")
        }
        assertEquals(HttpStatusCode.OK, roundtrip.status)
        assertTrue(roundtrip.bodyAsText().contains("\"mode\":\"public\""))
        assertEquals(HttpStatusCode.OK, anonymousPage(token).status)

        val adminPage = alice.get("/admin") { header(KeelHeaders.VISIT, "true") }
        assertEquals(HttpStatusCode.OK, adminPage.status)
        assertTrue(adminPage.bodyAsText().contains("\"publicAccess\":\"public\""))

        val invalid = alice.post("${Keel.ACTION_PATH}/kalendee.setPublicAccess") {
            contentType(ContentType.Application.Json)
            setBody("""{"mode":"inherit"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
    }
}

private const val monday = "2030-01-07"

private fun workWeek(enabled: Boolean): UpdateAvailabilityBody = UpdateAvailabilityBody(
    requestsEnabled = enabled,
    slotMinutes = 60,
    accessMode = "inherit",
    windows = (0..4).map { AvailabilityWindowBody(it, 9 * 60, 17 * 60) },
)

private suspend fun HttpClient.createCalendar(name: String): Calendar {
    val response = post("/api/v1/calendars") {
        contentType(ContentType.Application.Json)
        setBody(CreateCalendar(displayName = name))
    }
    check(response.status == HttpStatusCode.Created) { "create calendar failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.createEvent(
    calendarId: String,
    title: String,
    start: Instant,
    end: Instant,
    allDay: Boolean = false,
): Event {
    val response = post("/api/v1/calendars/$calendarId/events") {
        contentType(ContentType.Application.Json)
        setBody(CreateEvent(title = title, start = start, end = end, allDay = allDay))
    }
    check(response.status == HttpStatusCode.Created) { "create event failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.enablePublicLink(calendarId: String): String {
    val response = put("/api/v1/calendars/$calendarId/public") {
        contentType(ContentType.Application.Json)
        setBody(PublicLinkBody(enabled = true))
    }
    check(response.status == HttpStatusCode.OK) { "enable public link failed: ${response.status}" }
    return response.body<CalendarSharingOut>().publicLinkToken ?: error("no public token")
}

private suspend fun HttpClient.registerWithEmail(username: String, email: String) {
    val response = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterUser(username = username, password = "password12", email = email))
    }
    check(response.status == HttpStatusCode.Created) { "register failed: ${response.status}" }
}

private suspend fun HttpClient.slots(calendarId: String, date: String): CalendarSlotsOut {
    val response = get("/api/v1/calendars/$calendarId/slots?from=$date&to=$date")
    check(response.status == HttpStatusCode.OK) { "slots failed: ${response.status}" }
    return response.body()
}

private suspend fun HttpClient.publicRequestSlot(
    token: String,
    name: String,
    email: String?,
    start: String,
    end: String,
    message: String? = null,
): HttpResponse = post("/api/v1/public/calendars/$token/slot-requests") {
    contentType(ContentType.Application.Json)
    setBody(
        dev.kolektiv.kalendee.api.PublicRequestTimeSlotBody(
            name = name,
            email = email,
            start = Instant.parse(start),
            end = Instant.parse(end),
            message = message,
        ),
    )
}

private suspend fun HttpClient.requestSlot(
    calendarId: String,
    start: String,
    end: String,
): TimeSlotRequestSummary {
    val response = post("/api/v1/calendars/$calendarId/slot-requests") {
        contentType(ContentType.Application.Json)
        setBody(
            dev.kolektiv.kalendee.api.RequestTimeSlotBody(
                start = Instant.parse(start),
                end = Instant.parse(end),
            ),
        )
    }
    check(response.status == HttpStatusCode.Created) { "request slot failed: ${response.status}" }
    return response.body()
}

private suspend fun io.ktor.server.testing.ApplicationTestBuilder.anonymousPage(token: String) =
    jsonClient().get("/c/$token") {
        url { parameters.append("week", monday) }
        header(KeelHeaders.VISIT, "true")
    }

private suspend fun io.ktor.server.testing.ApplicationTestBuilder.bobPage(
    client: HttpClient,
    token: String,
) = client.get("/c/$token") {
    url { parameters.append("week", monday) }
    header(KeelHeaders.VISIT, "true")
}
