package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.CreateCalendar
import dev.kolektiv.kalendee.calendar.UpdateCalendar
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.calendarRoutes(store: CalendarStore) {
    route("/calendars") {
        get {
            call.respond(store.listCalendars(call.user().id))
        }
        post {
            val created = store.createCalendar(call.user().id, call.receive<CreateCalendar>())
            call.response.header(HttpHeaders.Location, "/api/v1/calendars/${created.id.value}")
            call.respond(HttpStatusCode.Created, created)
        }
        get("/{id}") {
            val calendar = store.getCalendar(call.calendarId(), call.user().id)
                ?: throw CalendarException.NotFound("calendar not found")
            call.respond(calendar)
        }
        patch("/{id}") {
            val updated = store.updateCalendar(
                call.calendarId(),
                call.user().id,
                call.receive<UpdateCalendar>(),
            ) ?: throw CalendarException.NotFound("calendar not found")
            call.respond(updated)
        }
        put("/{id}/hidden") {
            val updated = store.setCalendarHidden(
                call.calendarId(),
                call.user().id,
                call.receive<HiddenBody>().hidden,
            ) ?: throw CalendarException.NotFound("calendar not found")
            call.respond(updated)
        }
        delete("/{id}") {
            if (!store.deleteCalendar(call.calendarId(), call.user().id)) {
                throw CalendarException.NotFound("calendar not found")
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}