package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.InstantRange
import dev.kolektiv.kalendee.calendar.UpdateEvent
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlin.time.Instant

fun Route.eventRoutes(store: CalendarStore) {
    get("/events") {
        val fromRaw = call.request.queryParameters["from"]
            ?: throw CalendarException.Invalid("from is required")
        val toRaw = call.request.queryParameters["to"]
            ?: throw CalendarException.Invalid("to is required")
        val from = parseInstant(fromRaw)
        val to = parseInstant(toRaw)
        if (to <= from) {
            throw CalendarException.Invalid("to must be after from")
        }
        call.respond(store.listEvents(call.user().id, InstantRange(start = from, end = to)))
    }
    get("/calendars/{id}/events") {
        val range = call.instantRangeOrNull()
        call.respond(store.listEvents(call.calendarId(), call.user().id, range))
    }
    post("/calendars/{id}/events") {
        val created = store.createEvent(
            call.calendarId(),
            call.user().id,
            call.receive<CreateEvent>(),
        )
        call.respondEvent(created, HttpStatusCode.Created)
    }
    get("/events/{id}") {
        val event = store.getEvent(call.eventId(), call.user().id)
            ?: throw CalendarException.NotFound("event not found")
        call.respondEvent(event)
    }
    patch("/events/{id}") {
        val updated = store.updateEvent(
            call.eventId(),
            call.user().id,
            call.receive<UpdateEvent>(),
            call.ifMatchOrNull(),
        ) ?: throw CalendarException.NotFound("event not found")
        call.respondEvent(updated)
    }
    post("/events/{id}/move") {
        val body = call.receive<MoveEventBody>()
        if (body.scope != "following") {
            throw CalendarException.Invalid("scope must be following")
        }
        val events = store.moveEvent(
            call.eventId(),
            call.user().id,
            CalendarId.parse(body.calendarId),
            occurrenceStart = body.from?.let(::parseFromInstant),
            expectedEtag = body.etag,
        ) ?: throw CalendarException.NotFound("event not found")
        call.respond(MoveEventResponse(events))
    }
    delete("/events/{id}") {
        if (!store.deleteEvent(call.eventId(), call.user().id, call.ifMatchOrNull())) {
            throw CalendarException.NotFound("event not found")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun io.ktor.server.application.ApplicationCall.instantRangeOrNull(): InstantRange? {
    val fromRaw = request.queryParameters["from"]
    val toRaw = request.queryParameters["to"]
    if (fromRaw == null && toRaw == null) return null
    val from = fromRaw?.let(::parseInstant) ?: Instant.DISTANT_PAST
    val to = toRaw?.let(::parseInstant) ?: Instant.DISTANT_FUTURE
    if (to <= from) {
        throw CalendarException.Invalid("to must be after from")
    }
    return InstantRange(start = from, end = to)
}

private fun parseInstant(value: String): Instant = try {
    Instant.parse(value)
} catch (_: IllegalArgumentException) {
    throw CalendarException.Invalid("invalid instant: $value")
}

private fun parseFromInstant(value: String): Instant = try {
    parseInstant(value)
} catch (_: CalendarException.Invalid) {
    throw CalendarException.Invalid("invalid from: $value")
}
