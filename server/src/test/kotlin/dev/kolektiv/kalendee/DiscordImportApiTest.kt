package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.ConnectionsResponse
import dev.kolektiv.kalendee.api.DiscordGuildOut
import dev.kolektiv.kalendee.api.DiscordGuildsResponse
import dev.kolektiv.kalendee.api.DiscordSyncSetupOut
import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CreateCalendar
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordImportApiTest {
    @Test
    fun discordRoutesRequireSession() = testApplication {
        installDiscord(discordEngine())
        val response = jsonClient().get("/api/v1/discord/guilds?connectionId=unknown")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("unauthorized", response.body<ErrorBody>().error)
    }

    @Test
    fun discordImportRoutesMirrorWebShapes() = testApplication {
        installDiscord(discordEngine())
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")
        client.connectDiscord()
        val connection = client.get("/api/v1/connections").body<ConnectionsResponse>().connections.single()
        assertEquals("discord", connection.provider)

        val guilds = client.get("/api/v1/discord/guilds") {
            url { parameters.append("connectionId", connection.id) }
        }
        assertEquals(HttpStatusCode.OK, guilds.status)
        val listed = guilds.body<DiscordGuildsResponse>().guilds
        assertEquals(listOf("101", "102", "103"), listed.map { it.id }.sorted())

        val importedGuild = listed.single { it.id == "101" }
        assertEquals("Kolektiv", importedGuild.name)
        assertTrue(importedGuild.owner)
        assertTrue(importedGuild.botPresent)
        assertTrue(importedGuild.manageable)
        assertFalse(importedGuild.imported)
        assertNull(importedGuild.externalCalendarId)
        assertNull(importedGuild.calendarId)
        assertFalse(importedGuild.enabled)
        assertNull(importedGuild.lastSyncAt)
        assertNull(importedGuild.lastError)
        assertEquals(
            "https://cdn.discordapp.com/icons/101/icon-hash.png?size=128",
            importedGuild.iconUrl,
        )

        val botMissing = listed.single { it.id == "102" }
        assertFalse(botMissing.botPresent)
        assertTrue(botMissing.manageable)
        val invite = assertNotNull(botMissing.inviteUrl)
        assertEquals("discord-client", Url(invite).parameters["client_id"])
        assertEquals(
            "https://cdn.discordapp.com/icons/102/a_animated-hash.webp?animated=true&size=128",
            botMissing.iconUrl,
        )

        val botOnly = listed.single { it.id == "103" }
        assertTrue(botOnly.botPresent)
        assertFalse(botOnly.manageable)
        assertNull(botOnly.iconUrl)
        assertNull(botOnly.inviteUrl)
        assertNull(listed.firstOrNull { it.id == "104" })

        val anime = client.createCalendar("Anime")
        val setup = client.get("/api/v1/discord/guilds/101/sync?connectionId=${connection.id}")
        assertEquals(HttpStatusCode.OK, setup.status)
        val setupBody = setup.body<DiscordSyncSetupOut>()
        assertFalse(setupBody.imported)
        assertFalse(setupBody.enabled)
        assertNull(setupBody.defaultCalendarId)
        assertNull(setupBody.lastSyncAt)
        assertNull(setupBody.lastError)
        assertTrue(setupBody.calendars.any { it.id == anime.id.value })
        val event = setupBody.events.single()
        assertEquals("201", event.id)
        assertEquals("Community call", event.name)
        assertEquals("2026-09-20T14:00:00Z", event.start)
        assertFalse(event.recurring)
        assertNull(event.calendarId)
        assertFalse(event.skipped)

        val importedResponse = client.post("/api/v1/discord/guilds/101/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"connectionId":"${connection.id}"}""")
        }
        assertEquals(HttpStatusCode.Created, importedResponse.status, importedResponse.bodyAsText())
        val imported = importedResponse.body<DiscordGuildOut>()
        assertTrue(imported.imported)
        assertTrue(imported.enabled)
        assertTrue(imported.botPresent)
        assertTrue(imported.manageable)
        val externalCalendarId = assertNotNull(imported.externalCalendarId)
        assertNotNull(imported.calendarId)
        assertNotNull(imported.lastSyncAt)
        assertNull(imported.lastError)

        val gaming = client.createCalendar("Gaming")
        val saved = client.put("/api/v1/discord/guilds/101/sync") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"connectionId":"${connection.id}","defaultCalendarId":"${gaming.id.value}",""" +
                    """"routes":[{"eventId":"201","calendarId":"${anime.id.value}"}],"enabled":true}""",
            )
        }
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        assertEquals(gaming.id.value, saved.body<DiscordGuildOut>().calendarId)

        val routed = client.get("/api/v1/discord/guilds/101/sync?connectionId=${connection.id}")
            .body<DiscordSyncSetupOut>()
        assertTrue(routed.imported)
        assertTrue(routed.enabled)
        assertEquals(gaming.id.value, routed.defaultCalendarId)
        assertEquals(anime.id.value, routed.events.single().calendarId)
        assertFalse(routed.events.single().skipped)

        val synced = client.post("/api/v1/discord/imports/$externalCalendarId/sync")
        assertEquals(HttpStatusCode.OK, synced.status, synced.bodyAsText())
        assertEquals(externalCalendarId, synced.body<DiscordGuildOut>().externalCalendarId)

        val disabled = client.put("/api/v1/discord/imports/$externalCalendarId/enabled") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":false}""")
        }
        assertEquals(HttpStatusCode.OK, disabled.status)
        assertFalse(disabled.body<DiscordGuildOut>().enabled)
        assertEquals(gaming.id.value, disabled.body<DiscordGuildOut>().calendarId)

        val removed = client.delete("/api/v1/discord/imports/$externalCalendarId")
        assertEquals(HttpStatusCode.NoContent, removed.status)

        val afterRemoval = client.get("/api/v1/discord/guilds/101/sync?connectionId=${connection.id}")
            .body<DiscordSyncSetupOut>()
        assertFalse(afterRemoval.imported)

        val missing = client.post("/api/v1/discord/imports/$externalCalendarId/sync")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("not_found", missing.body<ErrorBody>().error)
    }

    @Test
    fun importWhenBotIsMissingReturnsConflictWithInvite() = testApplication {
        installDiscord(discordEngine())
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")
        client.connectDiscord()
        val connection = client.get("/api/v1/connections").body<ConnectionsResponse>().connections.single()

        val response = client.post("/api/v1/discord/guilds/102/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"connectionId":"${connection.id}"}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        val body = response.body<ErrorBody>()
        assertEquals("conflict", body.error)
        assertTrue("oauth2/authorize" in body.message, body.message)
        assertEquals("discord-client", Url(Regex("""https://\S+""").find(body.message)!!.value).parameters["client_id"])
    }

    @Test
    fun importWithoutBotTokenReturnsConflict() = testApplication {
        installDiscord(discordEngine(), botToken = "")
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")
        client.connectDiscord()
        val connection = client.get("/api/v1/connections").body<ConnectionsResponse>().connections.single()

        val response = client.post("/api/v1/discord/guilds/101/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"connectionId":"${connection.id}"}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        val body = response.body<ErrorBody>()
        assertEquals("conflict", body.error)
        assertTrue("bot token" in body.message, body.message)
    }

    @Test
    fun discordSyncFailureReturnsUnprocessable() = testApplication {
        installDiscord(discordEngine(scheduledEvents = { HttpStatusCode.InternalServerError to "{}" }))
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")
        client.connectDiscord()
        val connection = client.get("/api/v1/connections").body<ConnectionsResponse>().connections.single()

        val response = client.post("/api/v1/discord/guilds/101/import") {
            contentType(ContentType.Application.Json)
            setBody("""{"connectionId":"${connection.id}"}""")
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        val body = response.body<ErrorBody>()
        assertEquals("invalid", body.error)
        assertTrue("discord sync failed" in body.message, body.message)
    }

    @Test
    fun discordApiFailureReturnsBadGateway() = testApplication {
        installDiscord(discordEngine(userGuilds = { HttpStatusCode.InternalServerError to "{}" }))
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")
        client.connectDiscord()
        val connection = client.get("/api/v1/connections").body<ConnectionsResponse>().connections.single()

        val response = client.get("/api/v1/discord/guilds") {
            url { parameters.append("connectionId", connection.id) }
        }

        assertEquals(HttpStatusCode.BadGateway, response.status, response.bodyAsText())
        assertEquals("bad_gateway", response.body<ErrorBody>().error)
    }

    @Test
    fun reauthRequiredReturnsConflictAndMarksConnection() = testApplication {
        installDiscord(discordEngine(userGuilds = { HttpStatusCode.Unauthorized to """{"message":"401"}""" }))
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")
        client.connectDiscord()
        val connection = client.get("/api/v1/connections").body<ConnectionsResponse>().connections.single()

        val response = client.get("/api/v1/discord/guilds") {
            url { parameters.append("connectionId", connection.id) }
        }

        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        val body = response.body<ErrorBody>()
        assertEquals("conflict", body.error)
        assertTrue("reconnect" in body.message, body.message)
        val marked = client.get("/api/v1/connections").body<ConnectionsResponse>()
            .connections.single { it.id == connection.id }
        assertEquals("needs_reauth", marked.status)
    }

    @Test
    fun validationAndUnknownImportsMapToDomainErrors() = testApplication {
        installDiscord(discordEngine())
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")
        client.connectDiscord()
        val connection = client.get("/api/v1/connections").body<ConnectionsResponse>().connections.single()

        val badCalendar = client.put("/api/v1/discord/guilds/101/sync") {
            contentType(ContentType.Application.Json)
            setBody("""{"connectionId":"${connection.id}","defaultCalendarId":"not-a-uuid"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badCalendar.status, badCalendar.bodyAsText())
        assertEquals("invalid", badCalendar.body<ErrorBody>().error)

        val malformed = client.post("/api/v1/discord/imports/not-a-uuid/sync")
        assertEquals(HttpStatusCode.BadRequest, malformed.status, malformed.bodyAsText())
        assertEquals("invalid", malformed.body<ErrorBody>().error)

        val unknown = client.post("/api/v1/discord/imports/8f7a44b0-5a3a-4a4a-9d9d-6c7f3f0d1a2b/sync")
        assertEquals(HttpStatusCode.NotFound, unknown.status, unknown.bodyAsText())
        assertEquals("not_found", unknown.body<ErrorBody>().error)

        val unknownEnabled = client.put("/api/v1/discord/imports/8f7a44b0-5a3a-4a4a-9d9d-6c7f3f0d1a2b/enabled") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":true}""")
        }
        assertEquals(HttpStatusCode.NotFound, unknownEnabled.status, unknownEnabled.bodyAsText())

        val unknownRemove = client.delete("/api/v1/discord/imports/8f7a44b0-5a3a-4a4a-9d9d-6c7f3f0d1a2b")
        assertEquals(HttpStatusCode.NotFound, unknownRemove.status, unknownRemove.bodyAsText())
    }

    private fun ApplicationTestBuilder.installDiscord(engine: MockEngine, botToken: String? = "bot-token") {
        installApi(httpClient = HttpClient(engine), extraConfig = discordConfig(botToken))
    }

    private suspend fun HttpClient.connectDiscord(): String {
        val start = get("/api/v1/oauth/discord/start")
        assertEquals(HttpStatusCode.Found, start.status, start.bodyAsText())
        val authorization = Url(start.headers[HttpHeaders.Location] ?: error("missing discord redirect"))
        val state = authorization.parameters["state"] ?: error("missing oauth state")
        val callback = get("/api/v1/oauth/discord/callback?code=mock-code&state=$state")
        assertEquals(HttpStatusCode.Found, callback.status, callback.bodyAsText())
        return callback.headers[HttpHeaders.Location] ?: error("missing callback redirect")
    }

    private suspend fun HttpClient.createCalendar(name: String): Calendar {
        val response = post("/api/v1/calendars") {
            contentType(ContentType.Application.Json)
            setBody(CreateCalendar(displayName = name))
        }
        check(response.status == HttpStatusCode.Created) { "create calendar failed: ${response.status}" }
        return response.body()
    }
}

private fun discordEngine(
    userGuilds: () -> Pair<HttpStatusCode, String> = { discordOk(UserGuildsJson) },
    botGuilds: () -> Pair<HttpStatusCode, String> = { discordOk(BotGuildsJson) },
    scheduledEvents: () -> Pair<HttpStatusCode, String> = { discordOk(ScheduledEventsJson) },
): MockEngine = MockEngine { request ->
    val authorization = request.headers[HttpHeaders.Authorization].orEmpty()
    val path = request.url.encodedPath
    val response = when {
        request.method == HttpMethod.Post && path.endsWith("/oauth2/token") -> discordOk(TokenJson)
        request.method == HttpMethod.Post && path.endsWith("/oauth2/token/revoke") -> discordOk("{}")
        path.endsWith("/users/@me") && authorization.startsWith("Bearer") -> discordOk(IdentityJson)
        path.endsWith("/users/@me/guilds") && authorization.startsWith("Bearer") -> userGuilds()
        path.endsWith("/users/@me/guilds") && authorization.startsWith("Bot") -> botGuilds()
        path.endsWith("/scheduled-events") -> scheduledEvents()
        else -> HttpStatusCode.NotFound to "{}"
    }
    respond(
        content = response.second,
        status = response.first,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}

private fun discordConfig(botToken: String?): Map<String, String> = mapOf(
    "oauth.discord.clientId" to "discord-client",
    "oauth.discord.clientSecret" to "discord-secret",
    "oauth.discord.botToken" to (botToken ?: ""),
    "oauth.secretKey" to discordApiSecretKey,
)

private fun discordOk(body: String): Pair<HttpStatusCode, String> = HttpStatusCode.OK to body

private val UserGuildsJson =
    """[{"id":"101","name":"Kolektiv","icon":"icon-hash","owner":true,"features":[]},""" +
        """{"id":"102","name":"Another server","icon":"a_animated-hash","owner":false,""" +
        """"permissions":"32","features":[]},""" +
        """{"id":"103","name":"Bot only","icon":null,"owner":false,"permissions":"1024","features":[]},""" +
        """{"id":"104","name":"Unmanageable","icon":null,"owner":false,"permissions":"1024","features":[]}]"""

private val BotGuildsJson =
    """[{"id":"101","name":"Kolektiv","icon":"icon-hash","features":[]},""" +
        """{"id":"103","name":"Bot only","icon":null,"features":[]}]"""

private val IdentityJson = """{"id":"302","username":"mey","global_name":"Mey","avatar":null}"""

private val TokenJson =
    """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":604800,""" +
        """"scope":"identify guilds","token_type":"Bearer"}"""

private val ScheduledEventsJson =
    """[{"id":"201","guild_id":"101","channel_id":null,"name":"Community call",""" +
        """"description":"Hello","scheduled_start_time":"2026-09-20T14:00:00Z",""" +
        """"scheduled_end_time":"2026-09-20T15:00:00Z","privacy_level":2,"status":1,""" +
        """"entity_type":3,"entity_id":null,"entity_metadata":{"location":"Lounge"},"user_count":3}]"""

private val discordApiSecretKey: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
