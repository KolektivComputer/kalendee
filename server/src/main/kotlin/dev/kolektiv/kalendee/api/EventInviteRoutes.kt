package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.events.EventInviteService
import dev.kolektiv.kalendee.plugins.currentUser
import dev.kolektiv.kalendee.web.RsvpOut
import dev.kolektiv.kalendee.web.toOut
import dev.kolektiv.kalendee.web.toSummary
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.eventInviteRoutes(service: EventInviteService) {
    route("/events/{id}") {
        get("/attendees") {
            call.respond(service.attendees(call.eventId(), call.user().id).toOut())
        }
        post("/attendees") {
            val body = call.receive<InviteEventBody>()
            val attendees = service.invite(
                eventId = call.eventId(),
                inviterId = call.user().id,
                usernameOrEmail = body.username,
                name = body.name,
            )
            call.respond(HttpStatusCode.Created, attendees.toOut())
        }
        delete("/attendees/{attendeeId}") {
            val attendeeId = call.parameters["attendeeId"].orEmpty()
            call.respond(service.removeAttendee(call.eventId(), call.user().id, attendeeId).toOut())
        }
        post("/rsvp") {
            val body = call.receive<RespondEventBody>()
            call.respond(RsvpOut(service.respond(call.eventId(), call.user().id, body.status).status))
        }
        put("/open-rsvp") {
            val body = call.receive<SetOpenRsvpBody>()
            call.respond(service.setOpenRsvp(call.eventId(), call.user().id, body.enabled).toSummary())
        }
    }
    route("/public/events") {
        post("/rsvp") {
            val body = call.receive<RsvpByTokenBody>()
            call.respond(RsvpOut(service.respondByToken(body.token, body.status).status))
        }
        post("/{id}/rsvp") {
            val body = call.receive<PublicRsvpBody>()
            val result = service.publicRsvp(
                eventId = call.eventId(),
                name = body.name,
                email = body.email,
                status = body.status,
                viewerId = call.currentUser()?.id,
            )
            call.respond(RsvpOut(result.status))
        }
    }
}
