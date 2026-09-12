package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class OwnershipApiTest {
    @Test
    fun usersCannotSeeEachOthersCalendarsOrEvents() = testApplication {
        installApi()
        val alice = jsonClient()
        alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")

        val alicesCalendar = alice.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Alice"))
        }.body<Calendar>()
        val event = alice.post("/api/v1/calendars/${alicesCalendar.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Private",
                    start = Instant.parse("2026-03-01T10:00:00Z"),
                    end = Instant.parse("2026-03-01T11:00:00Z"),
                ),
            )
        }.body<Event>()

        assertEquals(emptyList(), bob.get("/api/v1/calendars").body<List<Calendar>>())
        assertEquals(
            HttpStatusCode.NotFound,
            bob.get("/api/v1/calendars/${alicesCalendar.id.value}").status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            bob.get("/api/v1/calendars/${alicesCalendar.id.value}/events").status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            bob.post("/api/v1/calendars/${alicesCalendar.id.value}/events") {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateEvent(
                        title = "Hijack",
                        start = Instant.parse("2026-03-01T12:00:00Z"),
                        end = Instant.parse("2026-03-01T13:00:00Z"),
                    ),
                )
            }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            bob.get("/api/v1/events/${event.id.value}").status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            bob.delete("/api/v1/calendars/${alicesCalendar.id.value}").status,
        )
        assertEquals("not_found", bob.get("/api/v1/events/${event.id.value}").body<ErrorBody>().error)

        assertEquals(
            listOf(alicesCalendar.id),
            alice.get("/api/v1/calendars").body<List<Calendar>>().map { it.id },
        )
        assertEquals(
            HttpStatusCode.OK,
            alice.get("/api/v1/events/${event.id.value}").status,
        )
    }
}
