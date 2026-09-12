package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.InstantRange
import dev.kolektiv.kalendee.plugins.currentUser
import dev.kolektiv.kalendee.web.ShareActions
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlin.time.Instant

fun Route.publicRoutes(store: CalendarStore, actions: ShareActions, auth: AuthService) {
    route("/public/calendars") {
        get("/{token}") {
            val calendar = call.publicCalendar(store, auth)
            val viewer = call.currentUser()
            val following = viewer != null &&
                store.getCalendar(calendar.id, viewer.id)?.permission == CalendarPermission.FOLLOW
            call.respond(PublicCalendarOut(calendar = calendar, following = following))
        }
        get("/{token}/events") {
            val calendar = call.publicCalendar(store, auth)
            call.respond(store.listPublicEvents(calendar.id, call.publicRange()))
        }
        post("/{token}/follow") {
            call.respond(actions.follow(call.user(), call.publicToken()))
        }
        delete("/{token}/follow") {
            val calendar = call.publicCalendar(store, auth)
            actions.unfollow(call.user(), calendar.id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.publicToken(): String =
    parameters["token"]?.takeIf { it.isNotBlank() }
        ?: throw CalendarException.Invalid("missing token")

private suspend fun ApplicationCall.publicCalendar(store: CalendarStore, auth: AuthService): Calendar {
    val calendar = store.publicCalendar(publicToken())
        ?: throw CalendarException.NotFound("calendar not found")
    val viewer = currentUser()
    if (auth.effectivePublicAccess(calendar) == PublicAccessMode.SIGNED_IN && viewer == null) {
        throw CalendarException.NotFound("calendar not found")
    }
    if (!auth.canViewPublic(calendar, viewer?.id)) {
        throw CalendarException.NotFound("calendar not found")
    }
    return calendar
}

private fun ApplicationCall.publicRange(): InstantRange? {
    val fromRaw = request.queryParameters["start"]
    val toRaw = request.queryParameters["end"]
    if (fromRaw == null && toRaw == null) return null
    val from = fromRaw?.let(::parsePublicInstant) ?: Instant.DISTANT_PAST
    val to = toRaw?.let(::parsePublicInstant) ?: Instant.DISTANT_FUTURE
    if (to <= from) throw CalendarException.Invalid("end must be after start")
    return InstantRange(start = from, end = to)
}

private fun parsePublicInstant(raw: String): Instant = try {
    Instant.parse(raw)
} catch (_: IllegalArgumentException) {
    throw CalendarException.Invalid("invalid instant: $raw")
}
