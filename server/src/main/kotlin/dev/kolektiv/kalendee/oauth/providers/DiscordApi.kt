package dev.kolektiv.kalendee.oauth.providers

import dev.kolektiv.kalendee.oauth.OAuthJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
 * Thin Discord REST client. The OAuth client uses [user] for identity; later
 * waves use [botGuilds], [scheduledEvents], and [scheduledEvent] to read guild
 * schedules with the bot token.
 */
class DiscordApi(
    private val http: HttpClient,
    private val botToken: String? = null,
    private val baseUrl: String = DefaultBaseUrl,
) {
    suspend fun user(token: String): DiscordUser =
        getJson("/users/@me", authorization = "Bearer $token")

    suspend fun guilds(token: String): List<DiscordGuild> =
        getJson("/users/@me/guilds", authorization = "Bearer $token")

    suspend fun botGuilds(): List<DiscordGuild> =
        getJson("/users/@me/guilds", authorization = "Bot ${requireBotToken()}")

    suspend fun scheduledEvents(guildId: String): List<DiscordScheduledEvent> =
        getJson(
            path = "/guilds/$guildId/scheduled-events",
            authorization = "Bot ${requireBotToken()}",
            withUserCount = true,
        )

    suspend fun scheduledEvent(guildId: String, eventId: String): DiscordScheduledEvent =
        getJson(
            path = "/guilds/$guildId/scheduled-events/$eventId",
            authorization = "Bot ${requireBotToken()}",
        )

    private fun requireBotToken(): String = botToken?.takeIf { it.isNotBlank() }
        ?: throw DiscordBotNotConfiguredException()

    private suspend inline fun <reified T> getJson(
        path: String,
        authorization: String,
        withUserCount: Boolean = false,
    ): T {
        var attempt = 0
        while (true) {
            val response = http.get("$baseUrl$path") {
                header(HttpHeaders.Authorization, authorization)
                header(HttpHeaders.UserAgent, UserAgent)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                if (withUserCount) parameter("with_user_count", "true")
            }
            when {
                response.status == HttpStatusCode.TooManyRequests -> {
                    val body = response.bodyAsText()
                    val retryAfter = parseRetryAfter(response.headers[HttpHeaders.RetryAfter], body)
                    if (attempt == 0) {
                        attempt++
                        delay(retryAfter)
                        continue
                    }
                    throw DiscordRateLimitedException(
                        message = "discord api rate limited after retry",
                        retryAfterSeconds = retryAfter.inWholeMilliseconds / 1000.0,
                    )
                }
                response.status == HttpStatusCode.Unauthorized ->
                    throw DiscordAuthException("discord api rejected the access token (HTTP 401)")
                else -> {
                    val body = response.bodyAsText()
                    if (!response.status.isSuccess()) {
                        throw DiscordApiHttpException(
                            message = "discord api request failed (HTTP ${response.status.value})",
                            statusCode = response.status.value,
                        )
                    }
                    return OAuthJson.decodeFromString(body)
                }
            }
        }
    }

    private fun parseRetryAfter(header: String?, body: String): Duration {
        val seconds = header?.trim()?.toDoubleOrNull()
            ?: runCatching {
                OAuthJson.parseToJsonElement(body).jsonObject["retry_after"]?.jsonPrimitive?.doubleOrNull
            }.getOrNull()
            ?: 1.0
        return seconds.coerceAtLeast(0.0).seconds
    }

    companion object {
        const val DefaultBaseUrl: String = "https://discord.com/api/v10"
        const val UserAgent: String = "KalendeeDiscord/0.1 (+https://github.com/kolektivdev/kalendee)"
    }
}
