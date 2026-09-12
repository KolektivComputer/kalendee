package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.availability.AvailabilityService
import dev.kolektiv.kalendee.availability.AvailabilityWindow
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.plugins.currentUser
import dev.kolektiv.kalendee.web.toOut
import dev.kolektiv.kalendee.web.toSummary
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate

fun Route.availabilityRoutes(service: AvailabilityService) {
    route("/calendars/{id}") {
        get("/availability") {
            call.respond(service.settings(call.calendarId(), call.user().id).toOut())
        }
        put("/availability") {
            val body = call.receive<UpdateAvailabilityBody>()
            call.respond(
                service.updateSettings(
                    calendarId = call.calendarId(),
                    userId = call.user().id,
                    requestsEnabled = body.requestsEnabled,
                    slotMinutes = body.slotMinutes,
                    accessMode = PublicAccessMode.parse(body.accessMode),
                    windows = body.windows.map {
                        AvailabilityWindow(it.weekday, it.startMinute, it.endMinute)
                    },
                ).toOut(),
            )
        }
        get("/slots") {
            call.respond(
                service.slots(
                    calendarId = call.calendarId(),
                    userId = call.user().id,
                    fromDate = call.dateParam("from"),
                    toDate = call.dateParam("to"),
                ).toOut(),
            )
        }
        post("/slot-requests") {
            val body = call.receive<RequestTimeSlotBody>()
            val request = service.requestSlot(
                calendarId = call.calendarId(),
                requesterId = call.user().id,
                start = body.start,
                end = body.end,
                message = body.message,
            )
            call.respond(HttpStatusCode.Created, request.toSummary())
        }
        get("/slot-requests") {
            call.respond(service.requests(call.calendarId(), call.user().id).map { it.toSummary() })
        }
    }
    post("/slot-requests/{requestId}/respond") {
        val body = call.receive<RespondTimeSlotBody>()
        val requestId = call.parameters["requestId"].orEmpty()
        call.respond(service.respond(requestId, call.user().id, body.accept, body.message).toSummary())
    }
    get("/public/calendars/{token}/slots") {
        val token = call.parameters["token"]?.takeIf { it.isNotBlank() }
            ?: throw CalendarException.Invalid("missing token")
        call.respond(
            service.publicSlots(
                token = token,
                viewerId = call.currentUser()?.id,
                fromDate = call.dateParam("from"),
                toDate = call.dateParam("to"),
            ).toOut(),
        )
    }
    post("/public/calendars/{token}/slot-requests") {
        val token = call.parameters["token"]?.takeIf { it.isNotBlank() }
            ?: throw CalendarException.Invalid("missing token")
        val body = call.receive<PublicRequestTimeSlotBody>()
        val request = service.publicRequestSlot(
            calendarToken = token,
            viewerId = call.currentUser()?.id,
            name = body.name,
            email = body.email,
            start = body.start,
            end = body.end,
            message = body.message,
        )
        call.respond(HttpStatusCode.Created, request.toSummary())
    }
}

private fun ApplicationCall.dateParam(name: String): LocalDate {
    val raw = request.queryParameters[name]?.takeIf { it.isNotBlank() }
        ?: throw CalendarException.Invalid("missing $name")
    return try {
        LocalDate.parse(raw)
    } catch (_: IllegalArgumentException) {
        throw CalendarException.Invalid("invalid $name date: $raw")
    }
}
