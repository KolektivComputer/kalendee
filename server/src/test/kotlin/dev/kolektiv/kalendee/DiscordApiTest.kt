package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.oauth.providers.DiscordApi
import dev.kolektiv.kalendee.oauth.providers.DiscordAuthException
import dev.kolektiv.kalendee.oauth.providers.DiscordBotNotConfiguredException
import dev.kolektiv.kalendee.oauth.providers.DiscordRateLimitedException
import dev.kolektiv.kalendee.oauth.providers.DiscordScheduledEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DiscordApiTest {
    @Test
    fun userRequestUsesBearerAndParsesGlobalName() = runBlocking {
        val engine = jsonEngine(
            """{"id":"user-1","username":"mey","global_name":"Elizabeth","avatar":"abc"}""",
        )
        val user = api(engine).user("user-token")

        assertEquals("user-1", user.id)
        assertEquals("mey", user.username)
        assertEquals("Elizabeth", user.globalName)
        val request = engine.requestHistory.single()
        assertEquals("/api/v10/users/@me", request.url.encodedPath)
        assertEquals("Bearer user-token", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun guildsRequestParsesGuildFields() = runBlocking {
        val engine = jsonEngine(
            """[{"id":"guild-1","name":"Kolektiv","icon":"icon-hash","owner":true,""" +
                """"permissions":"4398046511103","features":["COMMUNITY","NEWS"]}]""",
        )
        val guilds = api(engine).guilds("user-token")

        val guild = guilds.single()
        assertEquals("guild-1", guild.id)
        assertEquals("Kolektiv", guild.name)
        assertEquals("icon-hash", guild.icon)
        assertTrue(guild.owner)
        assertEquals("4398046511103", guild.permissions)
        assertEquals(listOf("COMMUNITY", "NEWS"), guild.features)
        assertEquals(
            "Bearer user-token",
            engine.requestHistory.single().headers[HttpHeaders.Authorization],
        )
    }

    @Test
    fun botGuildsUseBotAuthorization() = runBlocking {
        val engine = jsonEngine("""[]""")
        assertEquals(emptyList(), api(engine, botToken = "bot-secret").botGuilds())
        assertEquals(
            "Bot bot-secret",
            engine.requestHistory.single().headers[HttpHeaders.Authorization],
        )
    }

    @Test
    fun scheduledEventsParseNullEndTimeAndEnums() = runBlocking {
        val engine = jsonEngine(ScheduledEventsJson)
        val events = api(engine).scheduledEvents("guild-1")

        val event = events.single()
        assertEquals("event-1", event.id)
        assertEquals("guild-1", event.guildId)
        assertNull(event.channelId)
        assertEquals("Weekly standup", event.name)
        assertEquals("Sync", event.description)
        assertEquals("2026-09-12T15:00:00+00:00", event.scheduledStartTime)
        assertNull(event.scheduledEndTime, "external events may have no end time")
        assertEquals(2, event.privacyLevel)
        assertEquals(DiscordScheduledEvent.STATUS_SCHEDULED, event.status)
        assertEquals(DiscordScheduledEvent.ENTITY_TYPE_EXTERNAL, event.entityType)
        assertEquals("Voice Lounge", event.entityMetadata?.location)
        assertEquals(7, event.userCount)
        assertEquals(listOf(1, 3), event.recurrenceRule?.byWeekday)
        assertEquals("user-1", event.creatorId)

        val request = engine.requestHistory.single()
        assertEquals("/api/v10/guilds/guild-1/scheduled-events", request.url.encodedPath)
        assertEquals("true", request.url.parameters["with_user_count"])
        assertEquals("Bot bot-secret", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun scheduledEventParsesSingleEvent() = runBlocking {
        val engine = jsonEngine(
            """{"id":"event-1","guild_id":"guild-1","channel_id":"channel-1","name":"Voice hangout",""" +
                """"scheduled_start_time":"2026-09-12T15:00:00+00:00","scheduled_end_time":"2026-09-12T16:00:00+00:00",""" +
                """"privacy_level":2,"status":2,"entity_type":2,"user_count":3}""",
        )
        val event = api(engine).scheduledEvent("guild-1", "event-1")

        assertEquals("event-1", event.id)
        assertEquals(DiscordScheduledEvent.STATUS_ACTIVE, event.status)
        assertEquals(DiscordScheduledEvent.ENTITY_TYPE_VOICE, event.entityType)
        assertEquals("channel-1", event.channelId)
        assertEquals("2026-09-12T16:00:00+00:00", event.scheduledEndTime)
        assertEquals("/api/v10/guilds/guild-1/scheduled-events/event-1", engine.requestHistory.single().url.encodedPath)
    }

    @Test
    fun unauthorizedResponseRaisesAuthException(): Unit = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.Unauthorized) }
        assertFailsWith<DiscordAuthException> { api(engine).user("expired-token") }
    }

    @Test
    fun rateLimitedResponseRetriesOnceAndHonorsRetryAfter() = runBlocking {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            if (calls == 1) {
                respond(
                    content = """{"message":"You are being rate limited.","retry_after":0.0}""",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.RetryAfter, "0"),
                )
            } else {
                respond(
                    content = """[{"id":"guild-1","name":"Kolektiv"}]""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders(),
                )
            }
        }
        val guilds = api(engine).botGuilds()

        assertEquals("guild-1", guilds.single().id)
        assertEquals(2, calls, "a 429 must be retried exactly once")
    }

    @Test
    fun rateLimitedResponseFailsAfterOneRetry() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"message":"You are being rate limited."}""",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.RetryAfter, "0"),
            )
        }
        val failure = assertFailsWith<DiscordRateLimitedException> { api(engine).botGuilds() }
        assertEquals(0.0, failure.retryAfterSeconds)
        assertEquals(2, engine.requestHistory.size, "retries must be bounded to one attempt")
    }

    @Test
    fun botMethodsFailClearlyWithoutBotToken() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK) }
        val api = DiscordApi(http = HttpClient(engine), botToken = "  ")

        assertFailsWith<DiscordBotNotConfiguredException> { api.botGuilds() }
        assertFailsWith<DiscordBotNotConfiguredException> { api.scheduledEvents("guild-1") }
        assertFailsWith<DiscordBotNotConfiguredException> { api.scheduledEvent("guild-1", "event-1") }
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun discordRequestsCarryUserAgent() = runBlocking {
        val engine = jsonEngine("""[]""")
        api(engine).guilds("user-token")
        assertEquals(
            DiscordApi.UserAgent,
            engine.requestHistory.single().headers[HttpHeaders.UserAgent],
        )
    }

    private fun api(engine: MockEngine, botToken: String? = "bot-secret") =
        DiscordApi(http = HttpClient(engine), botToken = botToken)

    private fun jsonEngine(body: String): MockEngine = MockEngine {
        respond(content = body, status = HttpStatusCode.OK, headers = jsonHeaders())
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val ScheduledEventsJson = """
        [
          {
            "id": "event-1",
            "guild_id": "guild-1",
            "channel_id": null,
            "name": "Weekly standup",
            "description": "Sync",
            "scheduled_start_time": "2026-09-12T15:00:00+00:00",
            "scheduled_end_time": null,
            "privacy_level": 2,
            "status": 1,
            "entity_type": 3,
            "entity_metadata": {"location": "Voice Lounge"},
            "user_count": 7,
            "recurrence_rule": {"frequency": 2, "interval": 1, "by_weekday": [1, 3]},
            "creator": {"id": "user-1", "username": "mey", "global_name": null},
            "creator_id": "user-1",
            "image": null
          }
        ]
    """.trimIndent()
}
