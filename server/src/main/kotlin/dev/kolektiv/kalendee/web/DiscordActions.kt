package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.oauth.OAuthReauthRequiredException
import dev.kolektiv.kalendee.oauth.discord.DiscordBotNotInGuildException
import dev.kolektiv.kalendee.oauth.discord.DiscordGuildSummary as DiscordGuild
import dev.kolektiv.kalendee.oauth.discord.DiscordImportException
import dev.kolektiv.kalendee.oauth.discord.DiscordImportService
import dev.kolektiv.kalendee.oauth.discord.DiscordRouteAssignment
import dev.kolektiv.kalendee.oauth.discord.DiscordSyncSetup as ServiceSyncSetup
import dev.kolektiv.kalendee.oauth.providers.DiscordApiException
import dev.kolektiv.kalendee.oauth.providers.DiscordBotNotConfiguredException
import dev.kolektiv.keel.KeelAction
import dev.kolektiv.keel.ktor.PageValidationException

class DiscordActions(
    private val imports: DiscordImportService,
    private val auth: AuthService,
    private val settings: AuthSettings,
) {
    @KeelAction("kalendee.discordGuilds")
    suspend fun discordGuilds(input: DiscordGuildsIn): DiscordGuildsOut = mapDiscordErrors("connectionId") {
        val user = requireSessionUser(auth, settings)
        DiscordGuildsOut(guilds = imports.guilds(user.id, input.connectionId).map { it.toDto() })
    }

    @KeelAction("kalendee.discordSyncSetup")
    suspend fun discordSyncSetup(input: DiscordSyncSetupIn): DiscordSyncSetupOut =
        mapDiscordErrors("connectionId") {
            val user = requireSessionUser(auth, settings)
            imports.syncSetup(user.id, input.connectionId, input.guildId).toDto()
        }

    @KeelAction("kalendee.saveDiscordSync")
    suspend fun saveDiscordSync(input: SaveDiscordSyncIn): DiscordGuildSummary =
        mapDiscordErrors("calendarId") {
            val user = requireSessionUser(auth, settings)
            imports.saveSync(
                userId = user.id,
                connectionId = input.connectionId,
                guildId = input.guildId,
                defaultCalendarId = input.defaultCalendarId,
                routes = input.routes.map {
                    DiscordRouteAssignment(
                        eventId = it.eventId,
                        calendarId = it.calendarId,
                        skipped = it.skipped,
                    )
                },
                enabled = input.enabled,
            ).toDto()
        }

    @KeelAction("kalendee.importDiscordGuild")
    suspend fun importDiscordGuild(input: ImportDiscordGuildIn): DiscordGuildSummary =
        mapDiscordErrors("guildId") {
            val user = requireSessionUser(auth, settings)
            imports.importGuild(user.id, input.connectionId, input.guildId).toDto()
        }

    @KeelAction("kalendee.syncDiscordImport")
    suspend fun syncDiscordImport(input: SyncDiscordImportIn): DiscordGuildSummary =
        mapDiscordErrors("externalCalendarId") {
            val user = requireSessionUser(auth, settings)
            imports.syncNow(user.id, input.externalCalendarId)
            imports.guildSummary(user.id, input.externalCalendarId).toDto()
        }

    @KeelAction("kalendee.setDiscordImportEnabled")
    suspend fun setDiscordImportEnabled(input: SetDiscordImportEnabledIn): DiscordGuildSummary =
        mapDiscordErrors("externalCalendarId") {
            val user = requireSessionUser(auth, settings)
            imports.setEnabled(user.id, input.externalCalendarId, input.enabled)
            imports.guildSummary(user.id, input.externalCalendarId).toDto()
        }

    @KeelAction("kalendee.removeDiscordImport")
    suspend fun removeDiscordImport(input: RemoveDiscordImportIn): DeletedOut =
        mapDiscordErrors("externalCalendarId") {
            val user = requireSessionUser(auth, settings)
            imports.removeImport(user.id, input.externalCalendarId)
            DeletedOut()
        }
}

private fun DiscordGuild.toDto(): DiscordGuildSummary = DiscordGuildSummary(
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

private fun ServiceSyncSetup.toDto(): DiscordSyncSetupOut = DiscordSyncSetupOut(
    defaultCalendarId = defaultCalendarId,
    calendars = calendars.map {
        CalendarOptionSummary(id = it.id.value, displayName = it.displayName, color = it.color)
    },
    events = events.map { event ->
        DiscordEventRouteSummary(
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

private suspend fun <T> mapDiscordErrors(field: String, block: suspend () -> T): T = try {
    mapDomainErrors(field, block)
} catch (cause: DiscordBotNotInGuildException) {
    val message = buildString {
        append("the Kalendee bot is not in this Discord server yet")
        cause.inviteUrl?.let { append("; add it first: $it") }
    }
    throw PageValidationException(mapOf(field to listOf(message)), Unit)
} catch (cause: DiscordBotNotConfiguredException) {
    throw PageValidationException(
        mapOf(field to listOf(cause.message ?: "the Discord bot token is not configured")),
        Unit,
    )
} catch (cause: DiscordImportException) {
    throw PageValidationException(mapOf(field to listOf(cause.message ?: "Discord sync failed")), Unit)
} catch (cause: DiscordApiException) {
    throw PageValidationException(
        mapOf(field to listOf(cause.message ?: "the Discord request failed")),
        Unit,
    )
} catch (cause: OAuthReauthRequiredException) {
    throw PageValidationException(
        mapOf(field to listOf("reconnect this Discord account to continue")),
        Unit,
    )
}
