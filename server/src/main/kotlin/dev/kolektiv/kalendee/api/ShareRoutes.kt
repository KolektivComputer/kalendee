package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.web.ShareActions
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.shareRoutes(actions: ShareActions) {
    route("/calendars/{id}") {
        get("/shares") {
            call.respond(actions.sharing(call.user(), call.calendarId()))
        }
        post("/shares") {
            val body = call.receive<ShareCalendarBody>()
            val result = actions.share(call.user(), call.calendarId(), body.username, body.permission)
            call.respond(HttpStatusCode.Created, result)
        }
        patch("/shares/{userId}") {
            val body = call.receive<UpdateShareBody>()
            call.respond(
                actions.setSharePermission(call.user(), call.calendarId(), call.userId(), body.permission),
            )
        }
        delete("/shares/{userId}") {
            call.respond(actions.removeShare(call.user(), call.calendarId(), call.userId()))
        }
        put("/public") {
            val body = call.receive<PublicLinkBody>()
            call.respond(actions.setPublic(call.user(), call.calendarId(), body.enabled))
        }
        post("/public/rotate") {
            call.respond(actions.rotatePublic(call.user(), call.calendarId()))
        }
    }
}
