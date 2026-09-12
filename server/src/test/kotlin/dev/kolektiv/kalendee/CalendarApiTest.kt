package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.api.HiddenBody
import dev.kolektiv.kalendee.auth.UpdateUser
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarPalette
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.OptionalField
import dev.kolektiv.kalendee.calendar.UpdateCalendar
import kotlin.time.Instant
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarApiTest {
    @Test
    fun calendarCrud() = testApplication {
        installApi()
        val client = jsonClient()
        val user = client.registerAndLogin()

        val created = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work", description = "Office"))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val calendar = created.body<Calendar>()
        assertEquals("/api/v1/calendars/${calendar.id.value}", created.headers[HttpHeaders.Location])
        assertEquals("Work", calendar.displayName)
        assertEquals("Office", calendar.description)
        assertEquals("UTC", calendar.timeZone)
        assertEquals(user.id, calendar.ownerId)

        val listed = client.get("/api/v1/calendars")
        assertEquals(HttpStatusCode.OK, listed.status)
        assertEquals(listOf(calendar.id), listed.body<List<Calendar>>().map { it.id })

        val fetched = client.get("/api/v1/calendars/${calendar.id.value}")
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertEquals(calendar.displayName, fetched.body<Calendar>().displayName)

        val patched = client.patch("/api/v1/calendars/${calendar.id.value}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateCalendar(displayName = "Home"))
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        val updated = patched.body<Calendar>()
        assertEquals("Home", updated.displayName)
        assertEquals("Office", updated.description)

        val deleted = client.delete("/api/v1/calendars/${calendar.id.value}")
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        val missing = client.get("/api/v1/calendars/${calendar.id.value}")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("not_found", missing.body<ErrorBody>().error)
    }

    @Test
    fun blankDisplayNameIsInvalid() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val response = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = " "))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid", response.body<ErrorBody>().error)
    }

    @Test
    fun calendarsAreListedByDisplayName() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work"))
        }
        client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Home"))
        }
        val names = client.get("/api/v1/calendars").body<List<Calendar>>().map { it.displayName }
        assertEquals(listOf("Home", "Work"), names)
    }

    @Test
    fun patchCanClearCalendarDescription() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val calendar = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work", description = "Office"))
        }.body<Calendar>()

        val patched = client.patch("/api/v1/calendars/${calendar.id.value}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateCalendar(description = OptionalField.Present(null)))
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        val updated = patched.body<Calendar>()
        assertEquals("Work", updated.displayName)
        assertEquals(null, updated.description)
    }

    @Test
    fun invalidCalendarIdIsBadRequest() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val response = client.get("/api/v1/calendars/not-a-uuid")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid", response.body<ErrorBody>().error)
    }

    @Test
    fun missingCalendarIsNotFound() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val response = client.get("/api/v1/calendars/00000000-0000-0000-0000-000000000001")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("not_found", response.body<ErrorBody>().error)
    }

    @Test
    fun deleteCalendarRemovesEvents() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val calendar = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work"))
        }.body<Calendar>()

        val eventResponse = client.post("/api/v1/calendars/${calendar.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Standup",
                    start = Instant.parse("2026-03-01T10:00:00Z"),
                    end = Instant.parse("2026-03-01T10:30:00Z"),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, eventResponse.status)
        val eventId = eventResponse.body<Event>().id

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/v1/calendars/${calendar.id.value}").status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/events/${eventId.value}").status,
        )
        assertTrue(client.get("/api/v1/calendars").body<List<Calendar>>().isEmpty())
    }

    @Test
    fun newCalendarUsesUserTimeZoneAndCanBeHidden() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        client.patch("/api/v1/auth/me") {
            contentType(ContentType.Application.Json)
            setBody(UpdateUser(timeZone = "America/New_York"))
        }
        val created = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work"))
        }.body<Calendar>()
        assertEquals("America/New_York", created.timeZone)
        assertEquals(false, created.hidden)
        assertTrue(created.color in CalendarPalette)

        val hidden = client.put("/api/v1/calendars/${created.id.value}/hidden") {
            contentType(ContentType.Application.Json)
            setBody(HiddenBody(hidden = true))
        }.body<Calendar>()
        assertEquals(true, hidden.hidden)
        assertEquals(true, client.get("/api/v1/calendars").body<List<Calendar>>().single().hidden)
    }

    @Test
    fun calendarColorCanBeSetAndPatched() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val created = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work", color = "#3D5A80"))
        }.body<Calendar>()
        assertEquals("#3d5a80", created.color)

        val patched = client.patch("/api/v1/calendars/${created.id.value}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateCalendar(color = "#9b4d6e"))
        }.body<Calendar>()
        assertEquals("#9b4d6e", patched.color)
        assertEquals("Work", patched.displayName)

        val invalid = client.patch("/api/v1/calendars/${created.id.value}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateCalendar(color = "blue"))
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
    }
}
