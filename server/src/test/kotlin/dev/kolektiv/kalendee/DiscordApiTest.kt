package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.oauth.providers.DiscordApi
import dev.kolektiv.kalendee.oauth.providers.DiscordAuthException
import dev.kolektiv.kalendee.oauth.providers.DiscordBotNotConfiguredException
import dev.kolektiv.kalendee.oauth.providers.DiscordScheduledEvent
import dev.kord.common.KordConstants
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

class DiscordApiTest {
    @Test
    fun userRequestUsesBearerAndParsesGlobalName() = runBlocking {
        val engine = jsonEngine(
            """{"id":"301","username":"mey","global_name":"Elizabeth","avatar":"abc"}""",
        )
        val user = api(engine).user("user-token")

        assertEquals("301", user.id)
        assertEquals("mey", user.username)
        assertEquals("Elizabeth", user.globalName)
        val request = engine.requestHistory.single()
        assertEquals("/api/v10/users/@me", request.url.encodedPath)
        assertEquals("Bearer user-token", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun guildsRequestParsesGuildFields() = runBlocking {
        val engine = jsonEngine(
            """[{"id":"101","name":"Kolektiv","icon":"icon-hash","owner":true,""" +
                """"permissions":"4398046511103","features":["COMMUNITY","NEWS"]}]""",
        )
        val guilds = api(engine).guilds("user-token")

        val guild = guilds.single()
        assertEquals("101", guild.id)
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
        val events = api(engine).scheduledEvents("101")

        val event = events.single()
        assertEquals("201", event.id)
        assertEquals("101", event.guildId)
        assertNull(event.channelId)
        assertEquals("Weekly standup", event.name)
        assertEquals("Sync", event.description)
        assertEquals("2026-09-12T15:00:00Z", event.scheduledStartTime)
        assertNull(event.scheduledEndTime, "external events may have no end time")
        assertEquals(2, event.privacyLevel)
        assertEquals(DiscordScheduledEvent.STATUS_SCHEDULED, event.status)
        assertEquals(DiscordScheduledEvent.ENTITY_TYPE_EXTERNAL, event.entityType)
        assertEquals("Voice Lounge", event.entityMetadata?.location)
        assertEquals(7, event.userCount)
        assertEquals(listOf(1, 3), event.recurrenceRule?.byWeekday)
        assertEquals("301", event.creatorId)

        val request = engine.requestHistory.single()
        assertEquals("/api/v10/guilds/101/scheduled-events", request.url.encodedPath)
        assertEquals("true", request.url.parameters["with_user_count"])
        assertEquals("Bot bot-secret", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun scheduledEventParsesSingleEvent() = runBlocking {
        val engine = jsonEngine(
            """{"id":"201","guild_id":"101","channel_id":"401","name":"Voice hangout",""" +
                """"scheduled_start_time":"2026-09-12T15:00:00+00:00","scheduled_end_time":"2026-09-12T16:00:00+00:00",""" +
                """"privacy_level":2,"status":2,"entity_type":2,"entity_id":null,"entity_metadata":null,"user_count":3}""",
        )
        val event = api(engine).scheduledEvent("101", "201")

        assertEquals("201", event.id)
        assertEquals(DiscordScheduledEvent.STATUS_ACTIVE, event.status)
        assertEquals(DiscordScheduledEvent.ENTITY_TYPE_VOICE, event.entityType)
        assertEquals("401", event.channelId)
        assertEquals("2026-09-12T16:00:00Z", event.scheduledEndTime)
        assertEquals("/api/v10/guilds/101/scheduled-events/201", engine.requestHistory.single().url.encodedPath)
    }

    @Test
    fun modifyScheduledEventPatchesOnlyProvidedFields() = runBlocking {
        lateinit var captured: HttpRequestData
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = """{"id":"201","guild_id":"101","channel_id":null,"name":"Weekly standup",""" +
                    """"scheduled_start_time":"2026-09-13T15:00:00Z","scheduled_end_time":null,""" +
                    """"privacy_level":2,"status":1,"entity_type":3,"entity_id":null,""" +
                    """"entity_metadata":{"location":"Lounge"},"user_count":7}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders(),
            )
        }
        val event = api(engine).modifyScheduledEvent(
            guildId = "101",
            eventId = "201",
            start = Instant.parse("2026-09-13T15:00:00Z"),
            end = null,
            location = "Lounge",
        )

        assertEquals("201", event.id)
        assertEquals("2026-09-13T15:00:00Z", event.scheduledStartTime)
        assertNull(event.scheduledEndTime)
        assertEquals("Lounge", event.entityMetadata?.location)
        assertEquals(HttpMethod.Patch, captured.method)
        assertEquals("/api/v10/guilds/101/scheduled-events/201", captured.url.encodedPath)
        assertEquals("Bot bot-secret", captured.headers[HttpHeaders.Authorization])
        val body = (captured.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
        assertTrue("\"scheduled_start_time\":\"2026-09-13T15:00:00Z\"" in body, body)
        assertTrue("\"entity_metadata\":{\"location\":\"Lounge\"}" in body, body)
        assertTrue("\"scheduled_end_time\"" !in body, body)
        assertTrue("\"entity_type\"" !in body, body)
    }

    @Test
    fun unauthorizedResponseRaisesAuthException(): Unit = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.Unauthorized) }
        assertFailsWith<DiscordAuthException> { api(engine).user("expired-token") }
    }

    @Test
    fun rateLimitedResponseIsRetriedAutomatically() = runBlocking {
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
                    content = """[{"id":"101","name":"Kolektiv","icon":null,"features":[]}]""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders(),
                )
            }
        }
        val guilds = api(engine).botGuilds()

        assertEquals("101", guilds.single().id)
        assertEquals(2, calls, "a 429 must be retried automatically")
    }

    @Test
    fun rateLimitRetriesAreNotBoundedToOneAttempt() = runBlocking {
        // Kord's rate limiter keeps retrying 429 responses until a request
        // succeeds, so DiscordRateLimitedException is only a compatibility
        // mapping for any rate-limit failure Kord cannot absorb.
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls <= 2) {
                respond(
                    content = """{"message":"You are being rate limited."}""",
                    status = HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.RetryAfter, "0"),
                )
            } else {
                respond(
                    content = """[{"id":"101","name":"Kolektiv","icon":null,"features":[]}]""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders(),
                )
            }
        }
        val guilds = api(engine).botGuilds()

        assertEquals("101", guilds.single().id)
        assertEquals(3, calls, "Kord retries 429 responses automatically")
    }

    @Test
    fun botMethodsFailClearlyWithoutBotToken() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK) }
        val api = DiscordApi(http = HttpClient(engine), botToken = "  ")

        assertFailsWith<DiscordBotNotConfiguredException> { api.botGuilds() }
        assertFailsWith<DiscordBotNotConfiguredException> { api.scheduledEvents("101") }
        assertFailsWith<DiscordBotNotConfiguredException> { api.scheduledEvent("101", "201") }
        assertFailsWith<DiscordBotNotConfiguredException> {
            api.modifyScheduledEvent("101", "201", start = null, end = null)
        }
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun discordRequestsCarryKordUserAgent() = runBlocking {
        // Kord owns the User-Agent header now; DiscordApi.UserAgent is kept for
        // the OAuth token/revoke calls that still use this client directly.
        val engine = jsonEngine("""[]""")
        api(engine).guilds("user-token")
        assertEquals(
            KordConstants.USER_AGENT,
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
            "id": "201",
            "guild_id": "101",
            "channel_id": null,
            "name": "Weekly standup",
            "description": "Sync",
            "scheduled_start_time": "2026-09-12T15:00:00+00:00",
            "scheduled_end_time": null,
            "privacy_level": 2,
            "status": 1,
            "entity_type": 3,
            "entity_id": null,
            "entity_metadata": {"location": "Voice Lounge"},
            "user_count": 7,
            "recurrence_rule": {"frequency": 2, "interval": 1, "by_weekday": [1, 3]},
            "creator": {"id": "301", "username": "mey", "global_name": null, "avatar": null},
            "creator_id": "301",
            "image": null
          }
        ]
    """.trimIndent()
}
