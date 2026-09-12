package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.sessionToken
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.keel.ktor.ActionRequest
import dev.kolektiv.keel.ktor.PageValidationException

internal suspend fun requireSessionUser(auth: AuthService, settings: AuthSettings): User {
    val token = ActionRequest.current().call.sessionToken(settings)
        ?: throw CalendarException.Unauthorized("unauthorized")
    return auth.userFor(token) ?: throw CalendarException.Unauthorized("unauthorized")
}

internal suspend fun requireAdmin(auth: AuthService, settings: AuthSettings): User {
    val user = requireSessionUser(auth, settings)
    if (!user.admin) throw CalendarException.Forbidden("admin only")
    return user
}

internal suspend fun <T> mapDomainErrors(defaultField: String, block: suspend () -> T): T = try {
    block()
} catch (cause: CalendarException.Invalid) {
    throw PageValidationException(
        mapOf(fieldFrom(cause.message, defaultField) to listOf(cause.message ?: "invalid")),
        Unit,
    )
} catch (cause: CalendarException.Unauthorized) {
    throw PageValidationException(
        mapOf(defaultField to listOf(cause.message ?: "unauthorized")),
        Unit,
    )
} catch (cause: CalendarException.NotFound) {
    throw PageValidationException(
        mapOf(defaultField to listOf(cause.message ?: "not found")),
        Unit,
    )
} catch (cause: CalendarException.Conflict) {
    throw PageValidationException(
        mapOf(defaultField to listOf(cause.message ?: "conflict")),
        Unit,
    )
} catch (cause: CalendarException.PreconditionFailed) {
    throw PageValidationException(
        mapOf("etag" to listOf("this event changed; reload and try again")),
        Unit,
    )
} catch (cause: CalendarException.Forbidden) {
    throw PageValidationException(
        mapOf(defaultField to listOf(cause.message ?: "forbidden")),
        Unit,
    )
}

private fun fieldFrom(message: String?, defaultField: String): String {
    val msg = message ?: return defaultField
    return when {
        msg.startsWith("displayName") -> "displayName"
        msg.startsWith("email") -> "email"
        msg.startsWith("password") -> "password"
        msg.startsWith("name") -> "name"
        msg.startsWith("slug") || msg.startsWith("team slug") -> "slug"
        msg.startsWith("team name") -> "name"
        msg.startsWith("description") -> "description"
        msg.startsWith("invalid organization team id") || msg.startsWith("team not found") -> "teamId"
        msg.startsWith("team role") -> "role"
        msg.startsWith("team member") -> "userId"
        msg.startsWith("team and calendar") -> "calendarId"
        msg.startsWith("storageQuotaBytes") -> "storageQuotaBytes"
        msg.startsWith("userIds") -> "userIds"
        msg.startsWith("color") -> "color"
        msg.startsWith("unknown holiday") -> "subscribedIds"
        msg.startsWith("month") -> "month"
        msg.startsWith("day") -> "day"
        msg.startsWith("invalid holiday") -> "id"
        msg.startsWith("title") -> "title"
        msg.startsWith("end") -> "end"
        msg.startsWith("start") || msg.startsWith("invalid instant") -> "start"
        msg.startsWith("timeZone") || msg.startsWith("unknown time zone") -> "timeZone"
        msg.startsWith("accent") -> "accent"
        msg.startsWith("permission") -> "permission"
        msg.startsWith("scope") -> "scope"
        msg.startsWith("slotMinutes") -> "slotMinutes"
        msg.startsWith("access mode") -> "accessMode"
        msg.startsWith("weekday") || msg.startsWith("window") -> "windows"
        msg.startsWith("slot") -> "start"
        msg.startsWith("invalid from") || msg.startsWith("from") -> "from"
        msg.startsWith("invalid to") -> "to"
        msg.startsWith("reminder") -> "offsets"
        msg.startsWith("url") -> "url"
        msg.startsWith("interval") || msg.startsWith("count") || msg.startsWith("until") ||
            msg.startsWith("unknown recurrence") -> "recurrence"
        msg.startsWith("invalid calendar") || msg.startsWith("event is already in that calendar") -> "calendarId"
        msg.startsWith("invalid user id") -> "userId"
        msg.startsWith("invalid event") || msg.startsWith("invalid user") -> "id"
        else -> defaultField
    }
}
