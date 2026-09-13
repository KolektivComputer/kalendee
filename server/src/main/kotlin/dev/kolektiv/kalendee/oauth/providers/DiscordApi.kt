package dev.kolektiv.kalendee.oauth.providers

import dev.kord.common.entity.DiscordGuildScheduledEvent as KordScheduledEvent
import dev.kord.common.entity.DiscordGuildScheduledEventException as KordScheduledEventException
import dev.kord.common.entity.DiscordPartialGuild as KordPartialGuild
import dev.kord.common.entity.DiscordRecurrenceRule as KordRecurrenceRule
import dev.kord.common.entity.DiscordUser as KordUser
import dev.kord.common.entity.GuildScheduledEventEntityMetadata
import dev.kord.common.entity.Snowflake
import dev.kord.common.entity.optional.Optional
import dev.kord.common.exception.RequestException
import dev.kord.rest.builder.scheduled_events.ScheduledEventExceptionCreateBuilder
import dev.kord.rest.builder.scheduled_events.ScheduledEventExceptionModifyBuilder
import dev.kord.rest.request.RestRequestException
import dev.kord.rest.service.RestClient
import dev.kord.rest.service.modifyScheduledEvent
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

sealed class DiscordApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class DiscordAuthException(message: String) : DiscordApiException(message)

class DiscordRateLimitedException(
    message: String,
    val retryAfterSeconds: Double,
) : DiscordApiException(message)

class DiscordApiHttpException(
    message: String,
    val statusCode: Int,
) : DiscordApiException(message)

class DiscordBotNotConfiguredException : DiscordApiException(
    "discord bot token is not configured: set KALENDEE_DISCORD_BOT_TOKEN and invite the bot to the guild " +
        "to read server scheduled events",
)

@Serializable
data class DiscordUser(
    val id: String,
    val username: String,
    @SerialName("global_name") val globalName: String? = null,
    val avatar: String? = null,
    val email: String? = null,
)

@Serializable
data class DiscordGuild(
    val id: String,
    val name: String,
    val icon: String? = null,
    val owner: Boolean = false,
    val permissions: String? = null,
    val features: List<String> = emptyList(),
)

@Serializable
data class DiscordEntityMetadata(
    val location: String? = null,
)

@Serializable
data class DiscordRecurrenceRuleNWeekday(
    val n: Int,
    val day: Int,
)

@Serializable
data class DiscordRecurrenceRule(
    val start: String? = null,
    val end: String? = null,
    val frequency: Int? = null,
    val interval: Int? = null,
    @SerialName("by_weekday") val byWeekday: List<Int>? = null,
    @SerialName("by_n_weekday") val byNWeekday: List<DiscordRecurrenceRuleNWeekday>? = null,
    @SerialName("by_month") val byMonth: List<Int>? = null,
    @SerialName("by_month_day") val byMonthDay: List<Int>? = null,
    @SerialName("by_year_day") val byYearDay: List<Int>? = null,
    val count: Int? = null,
)

@Serializable
data class DiscordScheduledEvent(
    val id: String,
    @SerialName("guild_id") val guildId: String,
    @SerialName("channel_id") val channelId: String? = null,
    val name: String,
    val description: String? = null,
    @SerialName("scheduled_start_time") val scheduledStartTime: String,
    @SerialName("scheduled_end_time") val scheduledEndTime: String? = null,
    @SerialName("privacy_level") val privacyLevel: Int,
    val status: Int,
    @SerialName("entity_type") val entityType: Int,
    @SerialName("entity_metadata") val entityMetadata: DiscordEntityMetadata? = null,
    @SerialName("user_count") val userCount: Int? = null,
    @SerialName("recurrence_rule") val recurrenceRule: DiscordRecurrenceRule? = null,
    val creator: DiscordUser? = null,
    @SerialName("creator_id") val creatorId: String? = null,
    val image: String? = null,
    @SerialName("guild_scheduled_event_exceptions")
    val exceptions: List<DiscordScheduledEventException> = emptyList(),
) {
    companion object {
        const val STATUS_SCHEDULED = 1
        const val STATUS_ACTIVE = 2
        const val STATUS_COMPLETED = 3
        const val STATUS_CANCELED = 4

        const val ENTITY_TYPE_STAGE_INSTANCE = 1
        const val ENTITY_TYPE_VOICE = 2
        const val ENTITY_TYPE_EXTERNAL = 3
    }
}

/**
 * One override of a single occurrence of a recurring guild scheduled event.
 * Discord never returns the original occurrence start, so Kalendee can only
 * correlate exceptions that it created itself (by stored exception id).
 */
@Serializable
data class DiscordScheduledEventException(
    @SerialName("event_id") val eventId: String,
    @SerialName("event_exception_id") val exceptionId: String,
    @SerialName("scheduled_start_time") val scheduledStartTime: String? = null,
    @SerialName("scheduled_end_time") val scheduledEndTime: String? = null,
    @SerialName("is_canceled") val isCanceled: Boolean = false,
)

/**
 * Thin Discord REST client built on Kord. The OAuth client uses [user] for
 * identity; later waves use [botGuilds], [scheduledEvents], [scheduledEvent],
 * [modifyScheduledEvent], and the scheduled-event exception calls to read and
 * update guild schedules with the bot token.
 */
class DiscordApi(
    private val http: HttpClient,
    private val botToken: String? = null,
    private val baseUrl: String = DefaultBaseUrl,
) {
    private val restBaseUrl: String = baseUrl.trimEnd('/')

    private val botClient: RestClient by lazy {
        restClient(token = requireBotToken(), tokenPrefix = "Bot")
    }

    suspend fun user(token: String): DiscordUser =
        execute { restClient(token, "Bearer").user.getCurrentUser().toDiscordUser() }

    suspend fun guilds(token: String): List<DiscordGuild> =
        execute {
            restClient(token, "Bearer").user.getCurrentUserGuilds().map { it.toDiscordGuild() }
        }

    suspend fun botGuilds(): List<DiscordGuild> =
        execute { botClient.user.getCurrentUserGuilds().map { it.toDiscordGuild() } }

    suspend fun scheduledEvents(guildId: String): List<DiscordScheduledEvent> =
        execute {
            botClient.guild
                .listScheduledEvents(Snowflake(guildId), withUserCount = true)
                .map { it.toDiscordScheduledEvent() }
        }

    suspend fun scheduledEvent(guildId: String, eventId: String): DiscordScheduledEvent =
        execute {
            botClient.guild
                .getScheduledEvent(Snowflake(guildId), Snowflake(eventId))
                .toDiscordScheduledEvent()
        }

    suspend fun modifyScheduledEvent(
        guildId: String,
        eventId: String,
        start: Instant?,
        end: Instant?,
        location: String? = null,
    ): DiscordScheduledEvent =
        execute {
            botClient.guild.modifyScheduledEvent(Snowflake(guildId), Snowflake(eventId)) {
                start?.let { scheduledStartTime = it }
                end?.let { scheduledEndTime = it }
                location?.let { entityMetadata = GuildScheduledEventEntityMetadata(Optional(it)) }
            }.toDiscordScheduledEvent()
        }

    /**
     * Creates an exception for the occurrence that originally started at
     * [originalStart]. Only the provided times are sent; the response never
     * echoes [originalStart], so callers must keep the returned exception id
     * to modify the same occurrence later.
     */
    suspend fun createScheduledEventException(
        guildId: String,
        eventId: String,
        originalStart: Instant,
        start: Instant?,
        end: Instant?,
        isCanceled: Boolean? = null,
    ): DiscordScheduledEventException =
        execute {
            val request = ScheduledEventExceptionCreateBuilder(originalStart).apply {
                start?.let { scheduledStartTime = it }
                end?.let { scheduledEndTime = it }
                isCanceled?.let { this.isCanceled = it }
            }.toRequest()
            botClient.guild
                .createScheduledEventException(Snowflake(guildId), Snowflake(eventId), request)
                .toDiscordScheduledEventException()
        }

    suspend fun modifyScheduledEventException(
        guildId: String,
        eventId: String,
        exceptionId: String,
        start: Instant?,
        end: Instant?,
        isCanceled: Boolean? = null,
    ): DiscordScheduledEventException =
        execute {
            val request = ScheduledEventExceptionModifyBuilder().apply {
                start?.let { scheduledStartTime = it }
                end?.let { scheduledEndTime = it }
                isCanceled?.let { this.isCanceled = it }
            }.toRequest()
            botClient.guild
                .modifyScheduledEventException(
                    Snowflake(guildId),
                    Snowflake(eventId),
                    Snowflake(exceptionId),
                    request,
                )
                .toDiscordScheduledEventException()
        }

    suspend fun deleteScheduledEventException(
        guildId: String,
        eventId: String,
        exceptionId: String,
    ) {
        execute {
            botClient.guild.deleteScheduledEventException(
                Snowflake(guildId),
                Snowflake(eventId),
                Snowflake(exceptionId),
            )
        }
    }

    private fun restClient(token: String, tokenPrefix: String): RestClient =
        RestClient(token = token, client = http, baseUrl = restBaseUrl, tokenPrefix = tokenPrefix)

    private fun requireBotToken(): String = botToken?.takeIf { it.isNotBlank() }
        ?: throw DiscordBotNotConfiguredException()

    private suspend fun <T> execute(block: suspend () -> T): T =
        try {
            block()
        } catch (cause: RestRequestException) {
            throw cause.toDiscordApiException()
        } catch (cause: RequestException) {
            throw DiscordApiHttpException(
                message = cause.message ?: "discord api request failed",
                statusCode = 0,
            )
        }

    private fun RestRequestException.toDiscordApiException(): DiscordApiException = when (status.code) {
        HttpStatusCode.Unauthorized.value ->
            DiscordAuthException("discord api rejected the access token (HTTP 401)")

        HttpStatusCode.TooManyRequests.value ->
            DiscordRateLimitedException(
                message = "discord api rate limited after retry",
                retryAfterSeconds = 0.0,
            )

        else -> DiscordApiHttpException(
            message = "discord api request failed (HTTP ${status.code})",
            statusCode = status.code,
        )
    }

    private fun KordUser.toDiscordUser(): DiscordUser = DiscordUser(
        id = id.toString(),
        username = username,
        globalName = globalName.value,
        avatar = avatar,
        email = email.value,
    )

    private fun KordPartialGuild.toDiscordGuild(): DiscordGuild = DiscordGuild(
        id = id.toString(),
        name = name,
        icon = icon,
        owner = owner.orElse(false),
        permissions = permissions.value?.code?.value,
        features = features.map { it.value },
    )

    private fun KordScheduledEvent.toDiscordScheduledEvent(): DiscordScheduledEvent = DiscordScheduledEvent(
        id = id.toString(),
        guildId = guildId.toString(),
        channelId = channelId?.toString(),
        name = name,
        description = description.value,
        scheduledStartTime = scheduledStartTime.toString(),
        scheduledEndTime = scheduledEndTime?.toString(),
        privacyLevel = privacyLevel.value,
        status = status.value,
        entityType = entityType.value,
        entityMetadata = entityMetadata?.let { DiscordEntityMetadata(location = it.location.value) },
        userCount = userCount.asNullable,
        recurrenceRule = recurrenceRule?.toDiscordRecurrenceRule(),
        creator = creator.value?.toDiscordUser(),
        creatorId = creatorId?.value?.toString(),
        image = image.value,
        exceptions = guildScheduledEventExceptions.map { it.toDiscordScheduledEventException() },
    )

    private fun KordScheduledEventException.toDiscordScheduledEventException(): DiscordScheduledEventException =
        DiscordScheduledEventException(
            eventId = eventId.toString(),
            exceptionId = eventExceptionId.toString(),
            scheduledStartTime = scheduledStartTime?.toString(),
            scheduledEndTime = scheduledEndTime?.toString(),
            isCanceled = isCanceled,
        )

    private fun KordRecurrenceRule.toDiscordRecurrenceRule(): DiscordRecurrenceRule = DiscordRecurrenceRule(
        start = start?.toString(),
        end = end?.toString(),
        frequency = frequency.value,
        interval = interval,
        byWeekday = byWeekday?.map { it.value },
        byNWeekday = byNWeekday?.map { DiscordRecurrenceRuleNWeekday(n = it.n, day = it.day) },
        byMonth = byMonth?.map { it.value },
        byMonthDay = byMonthDay,
        byYearDay = byYearDay,
        count = count,
    )

    companion object {
        const val DefaultBaseUrl: String = "https://discord.com/api/v10"
        const val UserAgent: String = "KalendeeDiscord/0.1 (+https://github.com/kolektivdev/kalendee)"
    }
}
