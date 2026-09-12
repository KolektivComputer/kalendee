package dev.kolektiv.kalendee.web

import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.oauth.OAuthReauthRequiredException
import dev.kolektiv.kalendee.oauth.discord.DiscordBotNotInGuildException
import dev.kolektiv.kalendee.oauth.discord.DiscordGuildSummary as DiscordGuild
import dev.kolektiv.kalendee.oauth.discord.DiscordImportException
import dev.kolektiv.kalendee.oauth.discord.DiscordImportService
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
    iconUrl = icon?.let { "https://cdn.discordapp.com/icons/$id/$it.png" },
    owner = owner,
    botPresent = botPresent,
    inviteUrl = inviteUrl,
    imported = imported,
    externalCalendarId = externalCalendarId,
    calendarId = calendarId,
    enabled = enabled,
    lastSyncAt = lastSyncAt,
    lastError = lastError,
)

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
