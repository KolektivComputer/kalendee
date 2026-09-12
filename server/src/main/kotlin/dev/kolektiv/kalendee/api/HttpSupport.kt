package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarId
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.plugins.CurrentUserKey
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond

internal fun ApplicationCall.user(): User =
    attributes.getOrNull(CurrentUserKey) ?: throw CalendarException.Unauthorized("unauthorized")

internal fun ApplicationCall.calendarId(): CalendarId =
    CalendarId.parse(parameters["id"] ?: throw CalendarException.Invalid("missing calendar id"))

internal fun ApplicationCall.eventId(): EventId =
    EventId.parse(parameters["id"] ?: throw CalendarException.Invalid("missing event id"))

internal fun ApplicationCall.userId(): UserId =
    UserId.parse(parameters["userId"] ?: throw CalendarException.Invalid("missing user id"))

internal fun ApplicationCall.ifMatchOrNull(): String? = parseIfMatch(request.headers[HttpHeaders.IfMatch])

internal fun parseIfMatch(header: String?): String? {
    val value = header?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (value == "*") return "*"
    return value.removePrefix("W/").trim().removeSurrounding("\"")
}

internal fun quotedEtag(etag: String): String = "\"$etag\""

internal suspend fun ApplicationCall.respondEvent(
    event: Event,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    response.header(HttpHeaders.ETag, quotedEtag(event.etag))
    if (status == HttpStatusCode.Created) {
        response.header(HttpHeaders.Location, "/api/v1/events/${event.id.value}")
    }
    respond(status, event)
}
