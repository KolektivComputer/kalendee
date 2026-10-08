package dev.kolektiv.kalendee.api

import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.oauth.OAuthReauthRequiredException
import dev.kolektiv.kalendee.oauth.discord.DiscordBotNotInGuildException
import dev.kolektiv.kalendee.oauth.discord.DiscordGuildSummary as ServiceGuildSummary
import dev.kolektiv.kalendee.oauth.discord.DiscordImportException
import dev.kolektiv.kalendee.oauth.discord.DiscordImportService
import dev.kolektiv.kalendee.oauth.discord.DiscordRouteAssignment
import dev.kolektiv.kalendee.oauth.discord.DiscordSyncSetup as ServiceSyncSetup
import dev.kolektiv.kalendee.oauth.providers.DiscordApiException
import dev.kolektiv.kalendee.oauth.providers.DiscordBotNotConfiguredException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class DiscordGuildOut(
    val id: String,
    val name: String,
    val iconUrl: String? = null,
    val owner: Boolean = false,
    val botPresent: Boolean = false,
    val manageable: Boolean = false,
    val inviteUrl: String? = null,
    val imported: Boolean = false,
    val externalCalendarId: String? = null,
    val calendarId: String? = null,
    val enabled: Boolean = false,
    val lastSyncAt: String? = null,
    val lastError: String? = null,
)

@Serializable
data class DiscordGuildsResponse(
    val guilds: List<DiscordGuildOut> = emptyList(),
)

@Serializable
data class DiscordEventRouteOut(
    val id: String,
    val name: String,
    val start: String,
    val recurring: Boolean = false,
    val calendarId: String? = null,
    val skipped: Boolean = false,
)

@Serializable
data class DiscordSyncSetupOut(
    val defaultCalendarId: String? = null,
    val calendars: List<CalendarOptionOut> = emptyList(),
    val events: List<DiscordEventRouteOut> = emptyList(),
    val imported: Boolean = false,
    val enabled: Boolean = false,
    val lastSyncAt: String? = null,
    val lastError: String? = null,
)

@Serializable
data class DiscordEventRouteBody(
    val eventId: String,
    val calendarId: String? = null,
    val skipped: Boolean = false,
)

@Serializable
data class SaveDiscordSyncBody(
    val connectionId: String,
    val defaultCalendarId: String? = null,
    val routes: List<DiscordEventRouteBody> = emptyList(),
    val enabled: Boolean = true,
)

@Serializable
data class ImportDiscordGuildBody(
    val connectionId: String,
)

@Serializable
data class SetDiscordImportEnabledBody(
    val enabled: Boolean,
)

fun Route.discordRoutes(imports: DiscordImportService) {
    route("/discord") {
        get("/guilds") {
            val connectionId = call.connectionIdParam()
            val guilds = call.respondDiscordErrors {
                imports.guilds(call.user().id, connectionId).map { it.toOut() }
            } ?: return@get
            call.respond(DiscordGuildsResponse(guilds))
        }
        get("/guilds/{guildId}/sync") {
            val guildId = call.guildIdParam()
            val connectionId = call.connectionIdParam()
            val setup = call.respondDiscordErrors {
                imports.syncSetup(call.user().id, connectionId, guildId)
            } ?: return@get
            call.respond(setup.toOut())
        }
        put("/guilds/{guildId}/sync") {
            val guildId = call.guildIdParam()
            val body = call.receive<SaveDiscordSyncBody>()
            val summary = call.respondDiscordErrors {
                imports.saveSync(
                    userId = call.user().id,
                    connectionId = body.connectionId,
                    guildId = guildId,
                    defaultCalendarId = body.defaultCalendarId,
                    routes = body.routes.map {
                        DiscordRouteAssignment(
                            eventId = it.eventId,
                            calendarId = it.calendarId,
                            skipped = it.skipped,
                        )
                    },
                    enabled = body.enabled,
                )
            } ?: return@put
            call.respond(summary.toOut())
        }
        post("/guilds/{guildId}/import") {
            val guildId = call.guildIdParam()
            val body = call.receive<ImportDiscordGuildBody>()
            val summary = call.respondDiscordErrors {
                imports.importGuild(call.user().id, body.connectionId, guildId)
            } ?: return@post
            call.respond(HttpStatusCode.Created, summary.toOut())
        }
        post("/imports/{calendarId}/sync") {
            val externalCalendarId = call.externalCalendarIdParam()
            val summary = call.respondDiscordErrors {
                imports.syncNow(call.user().id, externalCalendarId)
                imports.guildSummary(call.user().id, externalCalendarId)
            } ?: return@post
            call.respond(summary.toOut())
        }
        put("/imports/{calendarId}/enabled") {
            val externalCalendarId = call.externalCalendarIdParam()
            val body = call.receive<SetDiscordImportEnabledBody>()
            val summary = call.respondDiscordErrors {
                imports.setEnabled(call.user().id, externalCalendarId, body.enabled)
                imports.guildSummary(call.user().id, externalCalendarId)
            } ?: return@put
            call.respond(summary.toOut())
        }
        delete("/imports/{calendarId}") {
            val externalCalendarId = call.externalCalendarIdParam()
            call.respondDiscordErrors {
                imports.removeImport(call.user().id, externalCalendarId)
            } ?: return@delete
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.connectionIdParam(): String =
    request.queryParameters["connectionId"]?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw CalendarException.Invalid("missing connection id")

private fun ApplicationCall.guildIdParam(): String =
    parameters["guildId"]?.takeIf { it.isNotBlank() }
        ?: throw CalendarException.Invalid("missing guild id")

private fun ApplicationCall.externalCalendarIdParam(): String =
    parameters["calendarId"]?.takeIf { it.isNotBlank() }
        ?: throw CalendarException.Invalid("missing external calendar id")

private fun ServiceGuildSummary.toOut(): DiscordGuildOut = DiscordGuildOut(
    id = id,
    name = name,
    iconUrl = discordIconUrl(id, icon),
    owner = owner,
    botPresent = botPresent,
    manageable = manageable,
    inviteUrl = inviteUrl,
    imported = imported,
    externalCalendarId = externalCalendarId,
    calendarId = calendarId,
    enabled = enabled,
    lastSyncAt = lastSyncAt,
    lastError = lastError,
)

private fun ServiceSyncSetup.toOut(): DiscordSyncSetupOut = DiscordSyncSetupOut(
    defaultCalendarId = defaultCalendarId,
    calendars = calendars.map {
        CalendarOptionOut(id = it.id.value, displayName = it.displayName, color = it.color)
    },
    events = events.map { event ->
        DiscordEventRouteOut(
            id = event.id,
            name = event.name,
            start = event.start,
            recurring = event.recurring,
            calendarId = event.calendarId,
            skipped = event.skipped,
        )
    },
    imported = imported,
    enabled = enabled,
    lastSyncAt = lastSyncAt,
    lastError = lastError,
)

private fun discordIconUrl(guildId: String, icon: String?): String? {
    val hash = icon ?: return null
    return if (hash.startsWith("a_")) {
        "https://cdn.discordapp.com/icons/$guildId/$hash.webp?animated=true&size=128"
    } else {
        "https://cdn.discordapp.com/icons/$guildId/$hash.png?size=128"
    }
}

/**
 * Mirrors `mapDiscordErrors` for the JSON API: Discord failures never surface
 * as stack traces and keep the shared [ErrorBody] shape. Domain errors
 * ([CalendarException]) still flow through the application StatusPages.
 */
private suspend fun <T> ApplicationCall.respondDiscordErrors(block: suspend () -> T): T? = try {
    block()
} catch (cause: DiscordBotNotInGuildException) {
    val message = buildString {
        append("the Kalendee bot is not in this Discord server yet")
        cause.inviteUrl?.let { append("; add it first: $it") }
    }
    respondDiscordError(HttpStatusCode.Conflict, "conflict", message)
} catch (cause: DiscordBotNotConfiguredException) {
    respondDiscordError(
        HttpStatusCode.Conflict,
        "conflict",
        cause.message ?: "the Discord bot token is not configured",
    )
} catch (cause: DiscordImportException) {
    respondDiscordError(
        HttpStatusCode.UnprocessableEntity,
        "invalid",
        cause.message ?: "Discord sync failed",
    )
} catch (cause: DiscordApiException) {
    respondDiscordError(
        HttpStatusCode.BadGateway,
        "bad_gateway",
        cause.message ?: "the Discord request failed",
    )
} catch (cause: OAuthReauthRequiredException) {
    respondDiscordError(
        HttpStatusCode.Conflict,
        "conflict",
        "reconnect this Discord account to continue",
    )
}

private suspend fun ApplicationCall.respondDiscordError(
    status: HttpStatusCode,
    error: String,
    message: String,
): Nothing? {
    respond(status, ErrorBody(error = error, message = message))
    return null
}
