package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.oauth.discord.UntitledEventTitle
import dev.kolektiv.kalendee.oauth.google.GoogleImportedEvent
import dev.kolektiv.kalendee.oauth.google.interpretGoogleEvent
import dev.kolektiv.kalendee.oauth.google.parseGoogleInstant
import dev.kolektiv.kalendee.oauth.providers.GoogleCalendarApi
import dev.kolektiv.kalendee.oauth.providers.GoogleRemoteEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

class GoogleSyncServiceTest {
    @Test
    fun calendarListAndEventsMapToImportedUpserts() = runBlocking {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/users/me/calendarList") -> respond(
                    """{"items":[{"id":"primary","summary":"Mey","primary":true,"timeZone":"America/Chicago"}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                path.contains("/calendars/") -> respond(
                    """{"items":[{"id":"evt-1","summary":"Standup","status":"confirmed","etag":"\"1\"","start":{"dateTime":"2026-09-14T14:00:00Z"},"end":{"dateTime":"2026-09-14T14:30:00Z"}},{"id":"evt-2","summary":"Away","status":"cancelled","start":{"dateTime":"2026-09-14T18:00:00Z"},"end":{"dateTime":"2026-09-14T19:00:00Z"}}],"nextSyncToken":"sync-2"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val api = GoogleCalendarApi(HttpClient(engine))
        val calendars = api.listCalendars("token")
        assertEquals(1, calendars.size)
        assertEquals("Google · Mey", "Google · ${calendars.single().summary}")
        val page = api.listEvents("token", calendars.single().id)
        assertEquals("sync-2", page.nextSyncToken)

        val upserts = mutableListOf<String>()
        val tombstones = mutableListOf<String>()
        page.events.forEach { remote ->
            when (val mapped = interpretGoogleEvent(remote)) {
                is GoogleImportedEvent.Upsert -> upserts += mapped.event.uid
                is GoogleImportedEvent.Tombstone -> tombstones += mapped.uid
                null -> Unit
            }
        }
        assertEquals(listOf("google:primary:evt-1"), upserts)
        assertEquals(listOf("google:primary:evt-2"), tombstones)

        val standup = interpretGoogleEvent(page.events.first())
        val upsert = assertIs<GoogleImportedEvent.Upsert>(standup)
        assertEquals("Standup", upsert.event.title)
        assertEquals(Instant.parse("2026-09-14T14:00:00Z"), upsert.event.start)
        assertEquals(Instant.parse("2026-09-14T14:30:00Z"), upsert.event.end)
        assertEquals(EventStatus.CONFIRMED, upsert.event.status)
        assertEquals(false, upsert.event.allDay)
    }

    @Test
    fun rfc3339OffsetAndDateOnlyAllDayParse() {
        assertEquals(
            Instant.parse("2026-09-14T14:00:00Z"),
            parseGoogleInstant("2026-09-14T09:00:00-05:00"),
        )
        assertEquals(
            Instant.parse("2026-09-14T00:00:00Z"),
            parseGoogleInstant("2026-09-14", allDay = true),
        )
        assertNull(parseGoogleInstant("not-a-time"))
    }

    @Test
    fun allDayEventUsesMidnightUtcAndExclusiveEnd() {
        val mapped = interpretGoogleEvent(
            GoogleRemoteEvent(
                id = "holiday",
                calendarId = "en.usa#holiday@group.v.calendar.google.com",
                summary = "Labor Day",
                description = null,
                location = null,
                start = "2026-09-07",
                end = "2026-09-08",
                allDay = true,
                etag = "\"2\"",
                status = "confirmed",
                updated = null,
            ),
        )
        val upsert = assertIs<GoogleImportedEvent.Upsert>(mapped)
        assertEquals(
            "google:en.usa#holiday@group.v.calendar.google.com:holiday",
            upsert.event.uid,
        )
        assertTrue(upsert.event.allDay)
        assertEquals(Instant.parse("2026-09-07T00:00:00Z"), upsert.event.start)
        assertEquals(Instant.parse("2026-09-08T00:00:00Z"), upsert.event.end)
    }

    @Test
    fun blankSummaryFallsBackAndCancelledIsTombstoned() {
        val blank = interpretGoogleEvent(
            GoogleRemoteEvent(
                id = "e1",
                calendarId = "primary",
                summary = "  ",
                description = null,
                location = null,
                start = "2026-09-14T14:00:00Z",
                end = "2026-09-14T14:00:00Z",
                allDay = false,
                etag = null,
                status = "confirmed",
                updated = null,
            ),
        )
        val upsert = assertIs<GoogleImportedEvent.Upsert>(blank)
        assertEquals(UntitledEventTitle, upsert.event.title)
        assertEquals(Instant.parse("2026-09-14T15:00:00Z"), upsert.event.end)

        val cancelled = interpretGoogleEvent(
            GoogleRemoteEvent(
                id = "e1",
                calendarId = "primary",
                summary = "Gone",
                description = null,
                location = null,
                start = "2026-09-14T14:00:00Z",
                end = "2026-09-14T15:00:00Z",
                allDay = false,
                etag = null,
                status = "cancelled",
                updated = null,
            ),
        )
        assertEquals(GoogleImportedEvent.Tombstone("google:primary:e1"), cancelled)
    }

    @Test
    fun invalidStartIsSkipped() {
        assertNull(
            interpretGoogleEvent(
                GoogleRemoteEvent(
                    id = "bad",
                    calendarId = "primary",
                    summary = "Nope",
                    description = null,
                    location = null,
                    start = "whenever",
                    end = "2026-09-14T15:00:00Z",
                    allDay = false,
                    etag = null,
                    status = "confirmed",
                    updated = null,
                ),
            ),
        )
    }
}
