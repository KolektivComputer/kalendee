package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.api.HiddenBody
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.Recurrence
import dev.kolektiv.kalendee.calendar.RecurrenceFrequency
import dev.kolektiv.kalendee.calendar.OptionalField
import dev.kolektiv.kalendee.calendar.UpdateEvent
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.test.assertNotEquals
import kotlin.time.Instant

class EventApiTest {
    @Test
    fun eventCrudAndRangeFilter() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val calendar = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work"))
        }.body<Calendar>()

        val created = client.post("/api/v1/calendars/${calendar.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Standup",
                    start = Instant.parse("2026-03-01T10:00:00Z"),
                    end = Instant.parse("2026-03-01T10:30:00Z"),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val event = created.body<Event>()
        assertEquals("Standup", event.title)
        assertEquals("/api/v1/events/${event.id.value}", created.headers[HttpHeaders.Location])
        assertEquals("\"${event.etag}\"", created.headers[HttpHeaders.ETag])

        val outside = client.post("/api/v1/calendars/${calendar.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Retro",
                    start = Instant.parse("2026-03-02T10:00:00Z"),
                    end = Instant.parse("2026-03-02T11:00:00Z"),
                ),
            )
        }.body<Event>()

        val all = client.get("/api/v1/calendars/${calendar.id.value}/events")
        assertEquals(HttpStatusCode.OK, all.status)
        assertEquals(2, all.body<List<Event>>().size)

        val ranged = client.get(
            "/api/v1/calendars/${calendar.id.value}/events" +
                "?from=2026-03-01T00:00:00Z&to=2026-03-01T23:59:59Z",
        )
        assertEquals(listOf(event.id), ranged.body<List<Event>>().map { it.id })

        val fetched = client.get("/api/v1/events/${event.id.value}")
        assertEquals(event.title, fetched.body<Event>().title)
        assertEquals("\"${event.etag}\"", fetched.headers[HttpHeaders.ETag])

        val patched = client.patch("/api/v1/events/${event.id.value}") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.IfMatch, "\"${event.etag}\"")
            setBody(UpdateEvent(title = "Daily standup"))
        }
        val updated = patched.body<Event>()
        assertEquals("Daily standup", updated.title)
        assertNotEquals(event.etag, updated.etag)
        assertEquals("\"${updated.etag}\"", patched.headers[HttpHeaders.ETag])

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/v1/events/${event.id.value}").status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/events/${event.id.value}").status,
        )
        assertEquals(
            listOf(outside.id),
            client.get("/api/v1/calendars/${calendar.id.value}/events")
                .body<List<Event>>()
                .map { it.id },
        )
    }

    @Test
    fun endBeforeStartIsInvalid() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val calendar = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work"))
        }.body<Calendar>()

        val response = client.post("/api/v1/calendars/${calendar.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Broken",
                    start = Instant.parse("2026-03-01T11:00:00Z"),
                    end = Instant.parse("2026-03-01T10:00:00Z"),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid", response.body<ErrorBody>().error)
    }

    @Test
    fun eventsAreListedByStart() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val calendar = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work"))
        }.body<Calendar>()

        val later = client.post("/api/v1/calendars/${calendar.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Retro",
                    start = Instant.parse("2026-03-02T10:00:00Z"),
                    end = Instant.parse("2026-03-02T11:00:00Z"),
                ),
            )
        }.body<Event>()
        val earlier = client.post("/api/v1/calendars/${calendar.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Standup",
                    start = Instant.parse("2026-03-01T10:00:00Z"),
                    end = Instant.parse("2026-03-01T10:30:00Z"),
                ),
            )
        }.body<Event>()

        val ids = client.get("/api/v1/calendars/${calendar.id.value}/events")
            .body<List<Event>>()
            .map { it.id }
        assertEquals(listOf(earlier.id, later.id), ids)
    }

    @Test
    fun staleIfMatchIsPreconditionFailed() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val calendar = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work"))
        }.body<Calendar>()
        val event = client.post("/api/v1/calendars/${calendar.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Standup",
                    start = Instant.parse("2026-03-01T10:00:00Z"),
                    end = Instant.parse("2026-03-01T10:30:00Z"),
                ),
            )
        }.body<Event>()

        val response = client.patch("/api/v1/events/${event.id.value}") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.IfMatch, "\"stale-etag\"")
            setBody(UpdateEvent(title = "Nope"))
        }
        assertEquals(HttpStatusCode.PreconditionFailed, response.status)
        assertEquals("precondition_failed", response.body<ErrorBody>().error)
        assertEquals("Standup", client.get("/api/v1/events/${event.id.value}").body<Event>().title)
    }

    @Test
    fun patchCanClearNullableEventFields() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val calendar = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work"))
        }.body<Calendar>()
        val event = client.post("/api/v1/calendars/${calendar.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Standup",
                    description = "Daily",
                    location = "Room 1",
                    start = Instant.parse("2026-03-01T10:00:00Z"),
                    end = Instant.parse("2026-03-01T10:30:00Z"),
                    timeZone = "America/New_York",
                ),
            )
        }.body<Event>()

        val patched = client.patch("/api/v1/events/${event.id.value}") {
            contentType(ContentType.Application.Json)
            setBody(
                UpdateEvent(
                    description = OptionalField.Present(null),
                    location = OptionalField.Present(null),
                    timeZone = OptionalField.Present(null),
                ),
            )
        }.body<Event>()
        assertEquals(null, patched.description)
        assertEquals(null, patched.location)
        assertEquals(null, patched.timeZone)
        assertEquals("Standup", patched.title)
    }

    @Test
    fun invalidRangeAndMissingCalendarAreRejected() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val inverted = client.get(
            "/api/v1/calendars/00000000-0000-0000-0000-000000000001/events" +
                "?from=2026-03-02T00:00:00Z&to=2026-03-01T00:00:00Z",
        )
        assertEquals(HttpStatusCode.BadRequest, inverted.status)

        val missing = client.get(
            "/api/v1/calendars/00000000-0000-0000-0000-000000000001/events",
        )
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("not_found", missing.body<ErrorBody>().error)
    }

    @Test
    fun missingEventIsNotFound() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val response = client.get("/api/v1/events/00000000-0000-0000-0000-000000000001")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("not_found", response.body<ErrorBody>().error)
    }

    @Test
    fun ownerRangeListsEventsAcrossCalendars() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val work = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work"))
        }.body<Calendar>()
        val home = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Home"))
        }.body<Calendar>()
        val standup = client.post("/api/v1/calendars/${work.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Standup",
                    start = Instant.parse("2026-09-07T14:00:00Z"),
                    end = Instant.parse("2026-09-07T14:30:00Z"),
                ),
            )
        }.body<Event>()
        client.post("/api/v1/calendars/${home.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Dinner",
                    start = Instant.parse("2026-09-08T23:00:00Z"),
                    end = Instant.parse("2026-09-09T00:00:00Z"),
                ),
            )
        }
        val missing = client.get("/api/v1/events")
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        val ranged = client.get(
            "/api/v1/events?from=2026-09-07T00:00:00Z&to=2026-09-08T00:00:00Z",
        )
        assertEquals(HttpStatusCode.OK, ranged.status)
        assertEquals(listOf(standup.id), ranged.body<List<Event>>().map { it.id })
    }

    @Test
    fun blankTitleIsInvalid() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val calendar = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work"))
        }.body<Calendar>()
        val response = client.post("/api/v1/calendars/${calendar.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = " ",
                    start = Instant.parse("2026-03-01T10:00:00Z"),
                    end = Instant.parse("2026-03-01T11:00:00Z"),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid", response.body<ErrorBody>().error)
    }

    @Test
    fun recurringEventExpandsInRangeAndUrlIsStored() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val calendar = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work"))
        }.body<Calendar>()
        val created = client.post("/api/v1/calendars/${calendar.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Standup",
                    url = "https://meet.example/standup",
                    start = Instant.parse("2026-09-07T14:00:00Z"),
                    end = Instant.parse("2026-09-07T14:30:00Z"),
                    recurrence = Recurrence(frequency = RecurrenceFrequency.DAILY, interval = 1, count = 4),
                ),
            )
        }.body<Event>()
        assertEquals("https://meet.example/standup", created.url)
        assertEquals(RecurrenceFrequency.DAILY, created.recurrence?.frequency)

        val ranged = client.get(
            "/api/v1/calendars/${calendar.id.value}/events" +
                "?from=2026-09-07T00:00:00Z&to=2026-09-10T00:00:00Z",
        ).body<List<Event>>()
        assertEquals(
            listOf(
                Instant.parse("2026-09-07T14:00:00Z"),
                Instant.parse("2026-09-08T14:00:00Z"),
                Instant.parse("2026-09-09T14:00:00Z"),
            ),
            ranged.map { it.start },
        )
        assertEquals(setOf(created.id), ranged.map { it.id }.toSet())
    }

    @Test
    fun hiddenCalendarEventsAreOmittedFromOwnerRange() = testApplication {
        installApi()
        val client = jsonClient()
        client.registerAndLogin()
        val work = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Work"))
        }.body<Calendar>()
        val home = client.post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = "Home"))
        }.body<Calendar>()
        client.post("/api/v1/calendars/${work.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Standup",
                    start = Instant.parse("2026-09-07T14:00:00Z"),
                    end = Instant.parse("2026-09-07T14:30:00Z"),
                ),
            )
        }
        val dinner = client.post("/api/v1/calendars/${home.id.value}/events") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateEvent(
                    title = "Dinner",
                    start = Instant.parse("2026-09-07T22:00:00Z"),
                    end = Instant.parse("2026-09-07T23:00:00Z"),
                ),
            )
        }.body<Event>()
        client.put("/api/v1/calendars/${work.id.value}/hidden") {
            contentType(ContentType.Application.Json)
            setBody(HiddenBody(hidden = true))
        }
        val ranged = client.get(
            "/api/v1/events?from=2026-09-07T00:00:00Z&to=2026-09-08T00:00:00Z",
        ).body<List<Event>>()
        assertEquals(listOf(dinner.id), ranged.map { it.id })
    }
}
