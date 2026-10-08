package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.events.EventInviteService
import dev.kolektiv.kalendee.plugins.currentUser
import dev.kolektiv.kalendee.web.RsvpOut
import dev.kolektiv.kalendee.web.Viewer
import dev.kolektiv.kalendee.web.toOut
import dev.kolektiv.kalendee.web.toSummary
import dev.kolektiv.kalendee.web.toViewer
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * Mirrors the web `RsvpPage` payload for the JSON API. `valid` is always
 * `true` on success; invalid or expired lookups respond with 404 instead.
 */
@Serializable
data class PublicRsvpOut(
    val eventId: String,
    val token: String? = null,
    val valid: Boolean = false,
    val title: String? = null,
    val whenText: String? = null,
    val calendarName: String? = null,
    val status: String? = null,
    val requiresName: Boolean = false,
    val viewer: Viewer? = null,
)

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
        // Static segment; must win over GET /{id} for "rsvp" (covered by tests).
        get("/rsvp") {
            val token = call.request.queryParameters["token"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw CalendarException.Invalid("missing token")
            val lookup = service.inviteByToken(token)
                ?: throw CalendarException.NotFound("invite not found")
            call.respond(
                PublicRsvpOut(
                    eventId = lookup.eventId.value,
                    token = token,
                    valid = true,
                    title = lookup.title,
                    whenText = lookup.whenText,
                    calendarName = lookup.calendarName,
                    status = lookup.status,
                ),
            )
        }
        get("/{id}") {
            val viewer = call.currentUser()
            val eventId = EventId.parse(call.parameters["id"].orEmpty())
            val lookup = service.openRsvpInvite(eventId, viewerId = viewer?.id)
                ?: throw CalendarException.NotFound("event not found")
            call.respond(
                PublicRsvpOut(
                    eventId = lookup.eventId.value,
                    valid = true,
                    title = lookup.title,
                    whenText = lookup.whenText,
                    calendarName = lookup.calendarName,
                    status = lookup.status,
                    requiresName = viewer == null,
                    viewer = viewer?.toViewer(),
                ),
            )
        }
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
