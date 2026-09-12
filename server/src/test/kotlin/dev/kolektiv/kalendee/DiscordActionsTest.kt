package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.web.CalendarSummary
import dev.kolektiv.kalendee.web.DeletedOut
import dev.kolektiv.kalendee.web.DiscordGuildsOut
import dev.kolektiv.kalendee.web.DiscordGuildSummary
import dev.kolektiv.kalendee.web.DiscordSyncSetupOut
import dev.kolektiv.kalendee.web.SettingsPage
import dev.kolektiv.keel.Keel
import dev.kolektiv.keel.KeelJson
import dev.kolektiv.keel.seed.KeelSeed
import dev.kolektiv.keel.visit.KeelHeaders
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DiscordActionsTest {
    @Test
    fun connectionsTabListsAndManagesDiscordImports() = testApplication {
        installApi(httpClient = HttpClient(discordEngine()), extraConfig = discordActionsConfig())
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")

        val returnTo = client.connectDiscord()
        assertEquals("/settings?tab=connections&oauth=ok", returnTo)

        val settings = client.settingsPage(tab = "connections")
        assertEquals("connections", settings.tab)
        assertEquals(1, settings.connections.size)
        val connection = settings.connections.single()
        assertEquals("discord", connection.provider)
        assertEquals("active", connection.status)
        assertTrue(settings.providers.single { it.id == "discord" }.enabled)

        val listed = client.action(
            "kalendee.discordGuilds",
            """{"connectionId":"${connection.id}"}""",
            DiscordGuildsOut.serializer(),
        )
        assertEquals(listOf("guild-1", "guild-2", "guild-3"), listed.guilds.map { it.id }.sorted())
        val guild = listed.guilds.first { it.id == "guild-1" }
        assertEquals("Kolektiv", guild.name)
        assertTrue(guild.owner)
        assertTrue(guild.botPresent)
        assertTrue(guild.manageable)
        assertFalse(guild.imported)
        assertEquals("https://cdn.discordapp.com/icons/guild-1/icon-hash.png?size=128", guild.iconUrl)
        assertNull(guild.inviteUrl)
        val botMissing = listed.guilds.first { it.id == "guild-2" }
        assertFalse(botMissing.botPresent)
        assertTrue(botMissing.manageable)
        assertEquals(
            "https://cdn.discordapp.com/icons/guild-2/a_animated-hash.webp?animated=true&size=128",
            botMissing.iconUrl,
        )
        val invite = assertNotNull(botMissing.inviteUrl)
        assertEquals("discord-client", Url(invite).parameters["client_id"])
        val botOnly = listed.guilds.first { it.id == "guild-3" }
        assertTrue(botOnly.botPresent)
        assertFalse(botOnly.manageable)
        assertNull(botOnly.inviteUrl)
        assertNull(botOnly.iconUrl)
        assertNull(listed.guilds.firstOrNull { it.id == "guild-4" })

        val rejected = client.actionErrors(
            "kalendee.importDiscordGuild",
            """{"connectionId":"${connection.id}","guildId":"guild-2"}""",
        )
        val guildIdErrors = assertNotNull(rejected["guildId"]).joinToString(" ")
        assertTrue("oauth2/authorize" in guildIdErrors, guildIdErrors)

        val imported = client.action(
            "kalendee.importDiscordGuild",
            """{"connectionId":"${connection.id}","guildId":"guild-1"}""",
            DiscordGuildSummary.serializer(),
        )
        assertTrue(imported.imported)
        assertTrue(imported.enabled)
        assertTrue(imported.manageable)
        assertNotNull(imported.externalCalendarId)
        assertNotNull(imported.calendarId)
        assertNotNull(imported.lastSyncAt)
        assertNull(imported.lastError)

        val externalCalendarId = assertNotNull(imported.externalCalendarId)
        val synced = client.action(
            "kalendee.syncDiscordImport",
            """{"externalCalendarId":"$externalCalendarId"}""",
            DiscordGuildSummary.serializer(),
        )
        assertEquals(externalCalendarId, synced.externalCalendarId)
        assertTrue(synced.enabled)
        assertNotNull(synced.lastSyncAt)

        val disabled = client.action(
            "kalendee.setDiscordImportEnabled",
            """{"externalCalendarId":"$externalCalendarId","enabled":false}""",
            DiscordGuildSummary.serializer(),
        )
        assertFalse(disabled.enabled)

        val removed = client.action(
            "kalendee.removeDiscordImport",
            """{"externalCalendarId":"$externalCalendarId"}""",
            DeletedOut.serializer(),
        )
        assertTrue(removed.ok)

        val after = client.action(
            "kalendee.discordGuilds",
            """{"connectionId":"${connection.id}"}""",
            DiscordGuildsOut.serializer(),
        )
        val removedGuild = after.guilds.first { it.id == "guild-1" }
        assertFalse(removedGuild.imported)
        assertTrue(removedGuild.manageable)
        assertNull(removedGuild.externalCalendarId)
    }

    @Test
    fun syncSetupListsEventsAndCalendarsWithoutMapping() = testApplication {
        installApi(httpClient = HttpClient(discordEngine()), extraConfig = discordActionsConfig())
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")
        client.connectDiscord()
        val connection = client.settingsPage(tab = "connections").connections.single()
        val anime = client.action(
            "kalendee.createCalendar",
            """{"displayName":"Anime"}""",
            CalendarSummary.serializer(),
        )

        val setup = client.action(
            "kalendee.discordSyncSetup",
            """{"connectionId":"${connection.id}","guildId":"guild-1"}""",
            DiscordSyncSetupOut.serializer(),
        )

        assertFalse(setup.imported)
        assertFalse(setup.enabled)
        assertNull(setup.defaultCalendarId)
        assertNull(setup.lastSyncAt)
        assertNull(setup.lastError)
        assertTrue(setup.calendars.any { it.id == anime.id })
        val event = setup.events.single()
        assertEquals("event-1", event.id)
        assertEquals("Community call", event.name)
        assertEquals("2026-09-20T14:00:00Z", event.start)
        assertFalse(event.recurring)
        assertFalse(event.skipped)
        assertNull(event.calendarId)
    }

    @Test
    fun saveDiscordSyncBootstrapsMappingAndPersistsRoutes() = testApplication {
        installApi(httpClient = HttpClient(discordEngine()), extraConfig = discordActionsConfig())
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")
        client.connectDiscord()
        val connection = client.settingsPage(tab = "connections").connections.single()
        val anime = client.action(
            "kalendee.createCalendar",
            """{"displayName":"Anime"}""",
            CalendarSummary.serializer(),
        )
        val gaming = client.action(
            "kalendee.createCalendar",
            """{"displayName":"Gaming"}""",
            CalendarSummary.serializer(),
        )

        val saved = client.action(
            "kalendee.saveDiscordSync",
            """{"connectionId":"${connection.id}","guildId":"guild-1","defaultCalendarId":"${anime.id}",""" +
                """"routes":[{"eventId":"event-1","calendarId":"${gaming.id}"}],"enabled":true}""",
            DiscordGuildSummary.serializer(),
        )

        assertTrue(saved.imported)
        assertTrue(saved.enabled)
        assertNotNull(saved.externalCalendarId)
        assertEquals(anime.id, saved.calendarId)
        assertNotNull(saved.lastSyncAt)
        assertNull(saved.lastError)

        val routed = client.action(
            "kalendee.discordSyncSetup",
            """{"connectionId":"${connection.id}","guildId":"guild-1"}""",
            DiscordSyncSetupOut.serializer(),
        )
        assertTrue(routed.imported)
        assertEquals(anime.id, routed.defaultCalendarId)
        assertTrue(routed.calendars.any { it.id == anime.id })
        assertTrue(routed.calendars.any { it.id == gaming.id })
        val routedEvent = routed.events.single()
        assertEquals(gaming.id, routedEvent.calendarId)
        assertFalse(routedEvent.skipped)

        val skipped = client.action(
            "kalendee.saveDiscordSync",
            """{"connectionId":"${connection.id}","guildId":"guild-1",""" +
                """"routes":[{"eventId":"event-1","skipped":true}],"enabled":true}""",
            DiscordGuildSummary.serializer(),
        )
        assertEquals(saved.externalCalendarId, skipped.externalCalendarId)

        val after = client.action(
            "kalendee.discordSyncSetup",
            """{"connectionId":"${connection.id}","guildId":"guild-1"}""",
            DiscordSyncSetupOut.serializer(),
        )
        assertTrue(after.events.single().skipped)
        assertNull(after.events.single().calendarId)
    }

    @Test
    fun saveDiscordSyncRejectsCalendarTheUserCannotWrite() = testApplication {
        installApi(httpClient = HttpClient(discordEngine()), extraConfig = discordActionsConfig())
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")
        client.connectDiscord()
        val connection = client.settingsPage(tab = "connections").connections.single()

        val other = jsonClient(followRedirects = false)
        other.registerAndLogin(username = "bob")
        val otherCalendar = other.action(
            "kalendee.createCalendar",
            """{"displayName":"Bob"}""",
            CalendarSummary.serializer(),
        )

        val errors = client.actionErrors(
            "kalendee.saveDiscordSync",
            """{"connectionId":"${connection.id}","guildId":"guild-1",""" +
                """"defaultCalendarId":"${otherCalendar.id}","enabled":true}""",
        )

        val calendarErrors = assertNotNull(errors["calendarId"]).joinToString(" ")
        assertTrue("calendar" in calendarErrors, calendarErrors)
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

    private suspend fun HttpClient.settingsPage(tab: String? = null): SettingsPage {
        val response = get("/settings") {
            if (tab != null) {
                url { parameters.append("tab", tab) }
            }
            header(KeelHeaders.VISIT, "true")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val seed = KeelJson.codec.decodeFromString(KeelSeed.serializer(), response.bodyAsText())
        assertEquals("kalendee.settings", seed.page)
        return KeelJson.codec.decodeFromJsonElement(SettingsPage.serializer(), seed.data)
    }

    private suspend fun <T> HttpClient.action(id: String, body: String, serializer: KSerializer<T>): T {
        val response = post("${Keel.ACTION_PATH}/$id") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val data = KeelJson.codec.parseToJsonElement(response.bodyAsText())
            .jsonObject.getValue("data")
        return KeelJson.codec.decodeFromJsonElement(serializer, data)
    }

    private suspend fun HttpClient.actionErrors(id: String, body: String): Map<String, List<String>> {
        val response = post("${Keel.ACTION_PATH}/$id") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        val errors = KeelJson.codec.parseToJsonElement(response.bodyAsText())
            .jsonObject.getValue("errors")
            .jsonObject
        return errors.mapValues { (_, messages) ->
            messages.jsonArray.map { it.jsonPrimitive.content }
        }
    }

    private fun discordEngine(): MockEngine = MockEngine { request ->
        val authorization = request.headers[HttpHeaders.Authorization].orEmpty()
        val path = request.url.encodedPath
        val response = when {
            request.method == HttpMethod.Post && path.endsWith("/oauth2/token") -> ok(TokenJson)
            request.method == HttpMethod.Post && path.endsWith("/oauth2/token/revoke") -> ok("{}")
            path.endsWith("/users/@me") && authorization.startsWith("Bearer") -> ok(IdentityJson)
            path.endsWith("/users/@me/guilds") && authorization.startsWith("Bearer") -> ok(UserGuildsJson)
            path.endsWith("/users/@me/guilds") && authorization.startsWith("Bot") -> ok(BotGuildsJson)
            path.endsWith("/scheduled-events") -> ok(ScheduledEventsJson)
            else -> HttpStatusCode.NotFound to "{}"
        }
        respond(
            content = response.second,
            status = response.first,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun ok(body: String): Pair<HttpStatusCode, String> = HttpStatusCode.OK to body

    private fun discordActionsConfig(): Map<String, String> = mapOf(
        "oauth.discord.clientId" to "discord-client",
        "oauth.discord.clientSecret" to "discord-secret",
        "oauth.discord.botToken" to "bot-token",
        "oauth.secretKey" to discordActionsSecretKey,
    )

    private companion object {
        val UserGuildsJson =
            """[{"id":"guild-1","name":"Kolektiv","icon":"icon-hash","owner":true},""" +
                """{"id":"guild-2","name":"Another server","icon":"a_animated-hash","owner":false,""" +
                """"permissions":"32"},""" +
                """{"id":"guild-3","name":"Bot only","icon":null,"owner":false,"permissions":"1024"},""" +
                """{"id":"guild-4","name":"Unmanageable","icon":null,"owner":false,"permissions":"1024"}]"""
        val BotGuildsJson =
            """[{"id":"guild-1","name":"Kolektiv","icon":"icon-hash"},""" +
                """{"id":"guild-3","name":"Bot only","icon":null}]"""
        val IdentityJson = """{"id":"discord-1","username":"mey","global_name":"Mey"}"""
        val TokenJson =
            """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":604800,""" +
                """"scope":"identify guilds","token_type":"Bearer"}"""
        val ScheduledEventsJson =
            """[{"id":"event-1","guild_id":"guild-1","channel_id":null,"name":"Community call",""" +
                """"description":"Hello","scheduled_start_time":"2026-09-20T14:00:00Z",""" +
                """"scheduled_end_time":"2026-09-20T15:00:00Z","privacy_level":2,"status":1,""" +
                """"entity_type":3,"entity_metadata":{"location":"Lounge"},"user_count":3}]"""
        val discordActionsSecretKey: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    }
}
