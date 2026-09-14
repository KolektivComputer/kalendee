package dev.kolektiv.kalendee

import com.typesafe.config.ConfigFactory
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.LoginUser
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.oauth.ConnectionService
import dev.kolektiv.kalendee.oauth.OAuthSettings
import dev.kolektiv.kalendee.oauth.OAuthStateService
import dev.kolektiv.kalendee.oauth.Pkce
import dev.kolektiv.kalendee.web.AdminPage
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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.get

class OAuthFoundationTest {
    @Test
    fun oauthSettingsParseDiscordAndDisableBlankClientIds() {
        val settings = OAuthSettings.from(
            MapApplicationConfig(
                "oauth.discord.clientId" to "discord-client",
                "oauth.discord.clientSecret" to "discord-secret",
                "oauth.discord.botToken" to "  ",
                "oauth.secretKey" to oauthTestSecretKey,
            ),
        )
        assertTrue(settings.discord.enabled)
        assertEquals("discord-secret", settings.discord.clientSecret)
        assertNull(settings.discord.botToken, "blank bot tokens must be treated as absent")
        assertEquals(oauthTestSecretKey, settings.secretKey)

        val withGoogle = OAuthSettings.from(
            MapApplicationConfig(
                "oauth.google.clientId" to "google-client",
                "oauth.google.clientSecret" to "google-secret",
            ),
        )
        assertTrue(withGoogle.google.enabled)
        assertEquals("google-secret", withGoogle.google.clientSecret)

        val disabled = OAuthSettings.from(
            MapApplicationConfig(
                "oauth.discord.clientId" to "  ",
                "oauth.discord.clientSecret" to "discord-secret",
                "oauth.discord.botToken" to " bot-token ",
            ),
        )
        assertFalse(disabled.discord.enabled)
        assertEquals("bot-token", disabled.discord.botToken)
    }

    @Test
    fun stateIsProviderBoundAndSingleUse() = testApplication {
        installApi(extraConfig = oauthTestConfig())
        application {
            runBlocking {
                val states = get<OAuthStateService>()
                val pkce = Pkce.generate()
                val state = states.create(
                    userId = null,
                    provider = "discord",
                    pkce = pkce,
                    redirectUri = "https://kalendee.test/api/v1/oauth/discord/callback",
                    returnTo = "/settings?tab=connections",
                )
                val mismatch = assertFailsWith<CalendarException.Invalid> { states.consume(state, "google") }
                assertEquals("oauth state provider mismatch", mismatch.message)

                val record = states.consume(state, "discord")
                assertEquals(pkce.verifier, record.codeVerifier)
                assertEquals("/settings?tab=connections", record.returnTo)
                assertEquals("discord", record.provider)

                assertFailsWith<CalendarException.Invalid> { states.consume(state, "discord") }
            }
        }
    }

    @Test
    fun expiredStateIsRejectedAndUnknownStateIsRejected() = testApplication {
        installApi(extraConfig = oauthTestConfig())
        application {
            runBlocking {
                val states = get<OAuthStateService>()
                val state = states.create(
                    userId = null,
                    provider = "discord",
                    pkce = Pkce.generate(),
                    redirectUri = "https://kalendee.test/api/v1/oauth/discord/callback",
                    returnTo = null,
                )
                assertFailsWith<CalendarException.Invalid> { states.consume("missing-state", "discord") }
                val future = object : Clock {
                    override fun now() = Clock.System.now() + 11.minutes
                }
                val expired = OAuthStateService(database = get(), vault = get(), clock = future)
                assertFailsWith<CalendarException.Invalid> { expired.consume(state, "discord") }
            }
        }
    }

    @Test
    fun sessionLinksProviderAccount() = testApplication {
        val engine = mockDiscordEngine()
        installApi(httpClient = HttpClient(engine), extraConfig = oauthTestConfig())
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")

        val start = client.startDiscord()
        assertEquals("code", start.authorizationUrl.parameters["response_type"])
        assertEquals("discord-client", start.authorizationUrl.parameters["client_id"])
        assertEquals("S256", start.authorizationUrl.parameters["code_challenge_method"])
        assertNotNull(start.authorizationUrl.parameters["code_challenge"])
        assertEquals("identify guilds", start.authorizationUrl.parameters["scope"])
        assertEquals(
            "https://kalendee.test/api/v1/oauth/discord/callback",
            start.authorizationUrl.parameters["redirect_uri"],
        )

        val callback = client.callbackDiscord(start.state)
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("/settings?tab=connections&oauth=ok", callback.headers[HttpHeaders.Location])
        assertNull(callback.headers[HttpHeaders.SetCookie], "an existing session must be kept")

        val page = client.settingsPage()
        assertEquals(1, page.connections.size)
        assertEquals("discord", page.connections.single().provider)
        assertEquals("Discord", page.connections.single().providerName)
        assertNull(page.connections.single().accountEmail)
        assertEquals("OAuth User", page.connections.single().displayName)
        assertEquals("active", page.connections.single().status)
        assertTrue(page.providers.single { it.id == "discord" }.enabled)
        assertEquals("/api/v1/oauth/discord/start", page.providers.single { it.id == "discord" }.connectUrl)

        val asJson = client.get("/api/v1/oauth/discord/start?format=json")
        assertEquals(HttpStatusCode.OK, asJson.status)
        assertTrue(asJson.bodyAsText().contains("discord.com/oauth2/authorize"), asJson.bodyAsText())

        val action = client.post("${Keel.ACTION_PATH}/kalendee.connectProvider") {
            contentType(ContentType.Application.Json)
            setBody("""{"providerId":"discord","returnTo":"/settings"}""")
        }
        assertEquals(HttpStatusCode.OK, action.status, action.bodyAsText())
        assertTrue(action.bodyAsText().contains("discord.com/oauth2/authorize"), action.bodyAsText())

        val disconnected = client.post("${Keel.ACTION_PATH}/kalendee.disconnectAccount") {
            contentType(ContentType.Application.Json)
            setBody("""{"connectionId":"${page.connections.single().id}"}""")
        }
        assertEquals(HttpStatusCode.OK, disconnected.status, disconnected.bodyAsText())
        assertTrue(client.settingsPage().connections.isEmpty())
        assertTrue(
            engine.requestHistory.any { it.url.encodedPath.endsWith("/oauth2/token/revoke") },
            "disconnect must try to revoke the Discord authorization",
        )
    }

    @Test
    fun callbackStateBoundToAnotherUserIsRejected() = testApplication {
        val engine = mockDiscordEngine()
        installApi(httpClient = HttpClient(engine), extraConfig = oauthTestConfig())
        val alice = jsonClient(followRedirects = false)
        alice.registerAndLogin(username = "alice")
        val start = alice.startDiscord()
        assertEquals(HttpStatusCode.NoContent, alice.post("/api/v1/auth/logout").status)

        val bob = jsonClient(followRedirects = false)
        bob.registerAndLogin(username = "bob")
        val callback = bob.get("/api/v1/oauth/discord/callback?code=mock-code&state=${start.state}")
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("/settings?oauth=error", callback.headers[HttpHeaders.Location])
        assertTrue(bob.settingsPage().connections.isEmpty())
        assertTrue(engine.requestHistory.isEmpty(), "the mismatched state must be rejected before any token exchange")
    }

    @Test
    fun authSettingsParseOauthRegistrationFlag() {
        assertFalse(AuthSettings.from(MapApplicationConfig(), developmentMode = false).oauthRegistration)
        assertTrue(
            AuthSettings.from(
                MapApplicationConfig("auth.oauthRegistration" to "true"),
                developmentMode = false,
            ).oauthRegistration,
        )
    }

    @Test
    fun returnToOnlyAllowsRelativePaths() {
        assertEquals("/settings?tab=connections", ConnectionService.safeReturnTo("/settings?tab=connections"))
        assertNull(ConnectionService.safeReturnTo("https://evil.example/steal"))
        assertNull(ConnectionService.safeReturnTo("//evil.example/steal"))
        assertNull(ConnectionService.safeReturnTo("settings"))
        assertNull(ConnectionService.safeReturnTo("/settings\\evil"))
        assertNull(ConnectionService.safeReturnTo(""))
    }

    @Test
    fun unknownAccountIsRejectedWhenOAuthRegistrationIsOff() = testApplication {
        installApi(
            httpClient = HttpClient(mockDiscordEngine()),
            extraConfig = oauthTestConfig(oauthRegistration = false),
        )
        val state = installAnonymousState()
        val anon = jsonClient(followRedirects = false)
        val callback = anon.callbackDiscord(state)
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("/login?oauth=registration_closed", callback.headers[HttpHeaders.Location])
        assertNull(callback.headers[HttpHeaders.SetCookie])
        val me = anon.get("/api/v1/auth/me")
        assertEquals(HttpStatusCode.Unauthorized, me.status)
    }

    @Test
    fun oauthRegistrationCreatesVerifiedUserWhenOpen() = testApplication {
        installApi(httpClient = HttpClient(mockDiscordEngine()), extraConfig = oauthTestConfig(oauthRegistration = true))
        val state = installAnonymousState()
        val anon = jsonClient(followRedirects = false)
        val callback = anon.callbackDiscord(state)
        assertEquals(HttpStatusCode.Found, callback.status, callback.bodyAsText())
        assertEquals("/settings?tab=connections&oauth=ok", callback.headers[HttpHeaders.Location])
        assertNotNull(callback.headers[HttpHeaders.SetCookie])

        val me = anon.get("/api/v1/auth/me")
        assertEquals(HttpStatusCode.OK, me.status)
        val body = me.bodyAsText()
        assertTrue(body.contains("\"username\":\"oauthuser\""), body)
        assertTrue(body.contains("\"email\":null"), body)
        assertTrue(body.contains("\"emailVerified\":true"), body)
        assertTrue(body.contains("\"admin\":true"), "the first user is an admin")
    }

    @Test
    fun oauthRegistrationStillRespectsClosedRegistration() = testApplication {
        installApi(
            registration = "closed",
            httpClient = HttpClient(mockDiscordEngine()),
            extraConfig = oauthTestConfig(oauthRegistration = true),
        )
        val state = installAnonymousState()
        val anon = jsonClient(followRedirects = false)
        val callback = anon.callbackDiscord(state)
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("/login?oauth=registration_closed", callback.headers[HttpHeaders.Location])
        assertNull(callback.headers[HttpHeaders.SetCookie])
    }

    @Test
    fun duplicateProviderIdentityUpdatesTheExistingConnection() = testApplication {
        installApi(httpClient = HttpClient(mockDiscordEngine()), extraConfig = oauthTestConfig())
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")

        val first = client.startDiscord()
        assertEquals(HttpStatusCode.Found, client.callbackDiscord(first.state).status)
        val firstConnection = client.settingsPage().connections.single()

        val second = client.startDiscord()
        assertEquals(HttpStatusCode.Found, client.callbackDiscord(second.state).status)
        val connections = client.settingsPage().connections
        assertEquals(1, connections.size)
        assertEquals(firstConnection.id, connections.single().id)
    }

    @Test
    fun adminActionRoundTripsOauthRegistration() = testApplication {
        installApi(registration = "first-user", adminPassword = "adminpass1012")
        val client = jsonClient()
        val login = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginUser(username = "admin", password = "adminpass1012"))
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        assertFalse(client.adminPage().oauthRegistration)

        val opened = client.post("${Keel.ACTION_PATH}/kalendee.setOauthRegistration") {
            contentType(ContentType.Application.Json)
            setBody("""{"open":true}""")
        }
        assertEquals(HttpStatusCode.OK, opened.status, opened.bodyAsText())
        assertTrue(opened.bodyAsText().contains("\"oauthRegistrationOpen\":true"))
        assertTrue(client.adminPage().oauthRegistration)

        val closed = client.post("${Keel.ACTION_PATH}/kalendee.setOauthRegistration") {
            contentType(ContentType.Application.Json)
            setBody("""{"open":false}""")
        }
        assertEquals(HttpStatusCode.OK, closed.status)
        assertTrue(closed.bodyAsText().contains("\"oauthRegistrationOpen\":false"))
        assertFalse(client.adminPage().oauthRegistration)
    }

    @Test
    fun configExamplesParseAndCarryOauthPlaceholders() {
        val hoconFiles = listOf(
            Path.of("src/main/resources/application.conf"),
            Path.of("../application.conf.example"),
            Path.of("../application.conf.dev.example"),
        )
        for (file in hoconFiles) {
            assertTrue(Files.exists(file), "missing $file")
            val text = Files.readString(file)
            assertTrue("oauth" in text, "missing oauth block in $file")
            assertTrue("KALENDEE_DISCORD_CLIENT_ID" in text, "missing discord client id in $file")
            assertTrue("KALENDEE_DISCORD_BOT_TOKEN" in text, "missing discord bot token in $file")
            assertTrue("KALENDEE_SECRET_KEY" in text, "missing secret key in $file")
            assertTrue("KALENDEE_AUTH_OAUTH_REGISTRATION" in text, "missing oauth registration env in $file")
            assertFalse(text.contains("oauthRegistration = true"), "oauth registration must default to off in $file")
            assertFalse(text.contains("KALENDEE_GOOGLE_CLIENT_ID"), "google must be removed from $file")
            assertFalse(text.contains("KALENDEE_MICROSOFT_CLIENT_ID"), "microsoft must be removed from $file")
            val config = ConfigFactory.parseFile(file.toFile()).resolve()
            assertTrue(config.hasPath("auth.oauthRegistration"), "auth.oauthRegistration must resolve in $file")
        }
        val envFiles = listOf(Path.of("../.env.example"), Path.of("../.env.dev.example"))
        for (file in envFiles) {
            assertTrue(Files.exists(file), "missing $file")
            val text = Files.readString(file)
            assertTrue("KALENDEE_SECRET_KEY" in text, "missing secret key placeholder in $file")
            assertTrue("KALENDEE_DISCORD_CLIENT_ID" in text, "missing discord client placeholder in $file")
            assertTrue("KALENDEE_DISCORD_CLIENT_SECRET" in text, "missing discord client secret placeholder in $file")
            assertTrue("KALENDEE_DISCORD_BOT_TOKEN" in text, "missing discord bot token placeholder in $file")
            assertFalse(text.contains("KALENDEE_GOOGLE_CLIENT_ID"), "google must be removed from $file")
            assertFalse(text.contains("KALENDEE_MICROSOFT_CLIENT_ID"), "microsoft must be removed from $file")
        }
    }

    private suspend fun ApplicationTestBuilder.installAnonymousState(returnTo: String? = null): String {
        var state: String? = null
        application {
            runBlocking {
                state = get<OAuthStateService>().create(
                    userId = null,
                    provider = "discord",
                    pkce = Pkce.generate(),
                    redirectUri = "https://kalendee.test/api/v1/oauth/discord/callback",
                    returnTo = returnTo,
                )
            }
        }
        startApplication()
        return state ?: error("oauth state was not created")
    }

    private suspend fun HttpClient.startDiscord(): DiscordStart {
        val start = get("/api/v1/oauth/discord/start")
        assertEquals(HttpStatusCode.Found, start.status, start.bodyAsText())
        val url = Url(start.headers[HttpHeaders.Location] ?: error("missing authorization redirect"))
        return DiscordStart(
            authorizationUrl = url,
            state = url.parameters["state"] ?: error("missing oauth state"),
        )
    }

    private suspend fun HttpClient.callbackDiscord(state: String): HttpResponse =
        get("/api/v1/oauth/discord/callback?code=mock-code&state=$state")

    private suspend fun HttpClient.settingsPage(): SettingsPage {
        val response = get("/settings") { header(KeelHeaders.VISIT, "true") }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val seed = KeelJson.codec.decodeFromString(KeelSeed.serializer(), response.bodyAsText())
        assertEquals("kalendee.settings", seed.page)
        return KeelJson.codec.decodeFromJsonElement(SettingsPage.serializer(), seed.data)
    }

    private suspend fun HttpClient.adminPage(): AdminPage {
        val response = get("/admin") { header(KeelHeaders.VISIT, "true") }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val seed = KeelJson.codec.decodeFromString(KeelSeed.serializer(), response.bodyAsText())
        assertEquals("kalendee.admin", seed.page)
        return KeelJson.codec.decodeFromJsonElement(AdminPage.serializer(), seed.data)
    }

    private data class DiscordStart(val authorizationUrl: Url, val state: String)
}

private val oauthTestSecretKey: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

private fun oauthTestConfig(
    oauthRegistration: Boolean = true,
    discordClientId: String = "discord-client",
    discordBotToken: String = "",
): Map<String, String> = mapOf(
    "oauth.discord.clientId" to discordClientId,
    "oauth.discord.clientSecret" to "discord-secret",
    "oauth.discord.botToken" to discordBotToken,
    "oauth.secretKey" to oauthTestSecretKey,
    "auth.oauthRegistration" to oauthRegistration.toString(),
)

private fun mockDiscordEngine(
    externalId: String = "discord-1",
    username: String = "oauthuser",
    globalName: String? = "OAuth User",
): MockEngine = MockEngine { request ->
    val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    val path = request.url.encodedPath
    when {
        request.url.host != "discord.com" ->
            respond("unexpected request to ${request.url}", HttpStatusCode.NotFound)
        request.method == HttpMethod.Post && path.endsWith("/oauth2/token") -> respond(
            content = """{"access_token":"access-$externalId","refresh_token":"refresh-$externalId",""" +
                """"expires_in":604800,"scope":"identify guilds","token_type":"Bearer"}""",
            status = HttpStatusCode.OK,
            headers = json,
        )
        request.method == HttpMethod.Post && path.endsWith("/oauth2/token/revoke") -> respond(
            content = "{}",
            status = HttpStatusCode.OK,
            headers = json,
        )
        request.method == HttpMethod.Get && path.endsWith("/users/@me") -> respond(
            content = """{"id":"$externalId","username":"$username","global_name":${globalName.jsonOrNull()}}""",
            status = HttpStatusCode.OK,
            headers = json,
        )
        else -> respond("unexpected request to ${request.url}", HttpStatusCode.NotFound)
    }
}

private fun String?.jsonOrNull(): String = this?.let { "\"$it\"" } ?: "null"
