package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.db.CalendarConnectionsTable
import dev.kolektiv.kalendee.oauth.ConnectionService
import dev.kolektiv.kalendee.oauth.OAuthReauthRequiredException
import dev.kolektiv.kalendee.oauth.OAuthTokens
import dev.kolektiv.kalendee.oauth.PkceChallenge
import dev.kolektiv.kalendee.oauth.ProviderOAuthSettings
import dev.kolektiv.kalendee.oauth.providers.DiscordApi
import dev.kolektiv.kalendee.oauth.providers.DiscordOAuthClient
import dev.kolektiv.kalendee.oauth.providers.DiscordProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.ktor.ext.get

class DiscordProviderTest {
    private val settings = ProviderOAuthSettings(clientId = "client-id", clientSecret = "client-secret")

    @Test
    fun providerExposesDiscordMetadata() {
        val provider = provider(idleEngine())
        assertEquals("discord", provider.id)
        assertEquals("Discord", provider.displayName)
        assertEquals(listOf("identify", "guilds"), provider.scopes)
        assertTrue(provider.enabled)
    }

    @Test
    fun authorizationUrlCarriesDiscordOAuthParameters() {
        val url = Url(
            client(idleEngine()).authorizationUrl(
                state = "state-1",
                pkce = PkceChallenge(verifier = "verifier-1", challenge = "challenge-1"),
                redirectUri = "https://kalendee.test/api/v1/oauth/discord/callback",
            ),
        )
        assertTrue(url.toString().startsWith("https://discord.com/oauth2/authorize?"), url.toString())
        assertEquals("client-id", url.parameters["client_id"])
        assertEquals("https://kalendee.test/api/v1/oauth/discord/callback", url.parameters["redirect_uri"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals("identify guilds", url.parameters["scope"])
        assertEquals("state-1", url.parameters["state"])
        assertEquals("challenge-1", url.parameters["code_challenge"])
        assertEquals("S256", url.parameters["code_challenge_method"])
    }

    @Test
    fun exchangePostsFormEncodedTokenRequest() = runBlocking {
        val engine = MockEngine {
            respond(tokenResponse(1), HttpStatusCode.OK, jsonHeaders())
        }
        val tokens = client(engine).exchange(
            code = "the-code",
            pkce = PkceChallenge(verifier = "verifier-1", challenge = "challenge-1"),
            redirectUri = "https://kalendee.test/api/v1/oauth/discord/callback",
        )

        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
        assertNotNull(tokens.expiresAt)
        assertEquals(listOf("identify", "guilds"), tokens.scopes)
        val request = engine.requestHistory.single()
        assertEquals("/api/v10/oauth2/token", request.url.encodedPath)
        assertEquals(HttpMethod.Post, request.method)
        val body = request.formBody()
        assertTrue("grant_type=authorization_code" in body, body)
        assertTrue("code=the-code" in body, body)
        assertTrue("client_id=client-id" in body, body)
        assertTrue("client_secret=client-secret" in body, body)
        assertTrue("code_verifier=verifier-1" in body, body)
        assertTrue(
            "redirect_uri=https%3A%2F%2Fkalendee.test%2Fapi%2Fv1%2Foauth%2Fdiscord%2Fcallback" in body,
            body,
        )
    }

    @Test
    fun refreshReturnsRotatedTokens() = runBlocking {
        val engine = MockEngine {
            respond(tokenResponse(2), HttpStatusCode.OK, jsonHeaders())
        }
        val tokens = client(engine).refresh("refresh-1")

        assertEquals("access-2", tokens.accessToken)
        assertEquals("refresh-2", tokens.refreshToken)
        val body = engine.requestHistory.single().formBody()
        assertTrue("grant_type=refresh_token" in body, body)
        assertTrue("refresh_token=refresh-1" in body, body)
        assertTrue("client_id=client-id" in body, body)
        assertTrue("client_secret=client-secret" in body, body)
    }

    @Test
    fun refreshInvalidGrantRequiresReauth(): Unit = runBlocking {
        val engine = MockEngine {
            respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, jsonHeaders())
        }
        assertFailsWith<OAuthReauthRequiredException> { client(engine).refresh("stale-refresh") }
    }

    @Test
    fun revokePostsTheRefreshToken() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders()) }
        client(engine).revoke(OAuthTokens(accessToken = "access-1", refreshToken = "refresh-1"))

        val request = engine.requestHistory.single()
        assertEquals("/api/v10/oauth2/token/revoke", request.url.encodedPath)
        assertTrue("token=refresh-1" in request.formBody())
    }

    @Test
    fun identityPrefersGlobalNameAndOmitsEmail() = runBlocking {
        val engine = MockEngine {
            respond(
                """{"id":"user-1","username":"mey","global_name":"Elizabeth","avatar":"abc"}""",
                HttpStatusCode.OK,
                jsonHeaders(),
            )
        }
        val account = client(engine).accountIdentity(OAuthTokens(accessToken = "access-1"))

        assertEquals("user-1", account.externalId)
        assertEquals("Elizabeth", account.displayName)
        assertNull(account.email)
    }

    @Test
    fun identityFallsBackToUsername() = runBlocking {
        val engine = MockEngine {
            respond("""{"id":"user-1","username":"mey","global_name":"  "}""", HttpStatusCode.OK, jsonHeaders())
        }
        assertEquals("mey", client(engine).accountIdentity(OAuthTokens(accessToken = "access-1")).displayName)
    }

    @Test
    fun refreshPersistsRotatedTokensAndMarksNeedsReauthOnInvalidGrant() = testApplication {
        val engineState = DiscordEngineState()
        val engine = MockEngine { request -> respondDiscord(request, engineState) }
        var connections: ConnectionService? = null
        var database: Database? = null
        installApi(
            httpClient = HttpClient(engine),
            extraConfig = discordTestConfig(),
        )
        application {
            connections = get()
            database = get()
        }
        startApplication()
        val service = connections ?: error("ConnectionService was not configured")
        val db = database ?: error("Database was not configured")

        val client = jsonClient(followRedirects = false)
        val user = client.registerAndLogin(username = "mey")
        val start = client.get("/api/v1/oauth/discord/start")
        assertEquals(HttpStatusCode.Found, start.status, start.bodyAsText())
        val authorization = Url(start.headers[HttpHeaders.Location] ?: error("missing authorization url"))
        val state = authorization.parameters["state"] ?: error("missing oauth state")
        val callback = client.get("/api/v1/oauth/discord/callback?code=mock-code&state=$state")
        assertEquals(HttpStatusCode.Found, callback.status, callback.bodyAsText())
        val connectionId = runBlocking { service.connections(user.id).single().id }

        expireConnection(db, connectionId)
        assertEquals("access-2", runBlocking { service.validAccessToken(user.id, connectionId) })

        expireConnection(db, connectionId)
        assertEquals("access-3", runBlocking { service.validAccessToken(user.id, connectionId) })
        val tokenBodies = engine.requestHistory
            .filter { it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/oauth2/token") }
            .map { it.formBody() }
        assertEquals(3, tokenBodies.size, "exchange plus two refreshes")
        assertTrue("refresh_token=refresh-1" in tokenBodies[1], tokenBodies[1])
        assertTrue("refresh_token=refresh-2" in tokenBodies[2], "the rotated refresh token must be persisted")

        engineState.rejectRefresh = true
        expireConnection(db, connectionId)
        assertFailsWith<OAuthReauthRequiredException> {
            runBlocking { service.validAccessToken(user.id, connectionId) }
        }
        val summary = runBlocking { service.connections(user.id) }.single()
        assertEquals("needs_reauth", summary.status)
        assertNotNull(summary.lastError)
    }

    private fun expireConnection(database: Database, connectionId: String) {
        runBlocking {
            suspendTransaction(db = database) {
                CalendarConnectionsTable.update({ CalendarConnectionsTable.id eq Uuid.parse(connectionId) }) {
                    it[tokenExpiresAt] = Clock.System.now() - 1.minutes
                }
            }
        }
    }

    private fun provider(engine: MockEngine): DiscordProvider {
        val http = HttpClient(engine)
        return DiscordProvider(settings = settings, http = http, api = DiscordApi(http = http))
    }

    private fun client(engine: MockEngine): DiscordOAuthClient {
        val http = HttpClient(engine)
        return DiscordOAuthClient(settings = settings, http = http, api = DiscordApi(http = http))
    }

    private fun idleEngine(): MockEngine = MockEngine { respond("{}", HttpStatusCode.OK) }
}

private class DiscordEngineState {
    var rejectRefresh: Boolean = false
    var tokenCalls: Int = 0
}

private suspend fun MockRequestHandleScope.respondDiscord(
    request: HttpRequestData,
    state: DiscordEngineState,
) = when {
    request.url.host != "discord.com" ->
        respond("unexpected request to ${request.url}", HttpStatusCode.NotFound)
    request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/oauth2/token") -> {
        if ("grant_type=refresh_token" in request.formBody() && state.rejectRefresh) {
            respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, jsonHeaders())
        } else {
            state.tokenCalls++
            respond(tokenResponse(state.tokenCalls), HttpStatusCode.OK, jsonHeaders())
        }
    }
    request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/oauth2/token/revoke") ->
        respond("{}", HttpStatusCode.OK, jsonHeaders())
    request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/users/@me") ->
        respond(
            """{"id":"discord-1","username":"oauthuser","global_name":"OAuth User"}""",
            HttpStatusCode.OK,
            jsonHeaders(),
        )
    else -> respond("unexpected request to ${request.url}", HttpStatusCode.NotFound)
}

private fun HttpRequestData.formBody(): String =
    (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

private fun tokenResponse(call: Int): String =
    """{"access_token":"access-$call","refresh_token":"refresh-$call","expires_in":604800,""" +
        """"scope":"identify guilds","token_type":"Bearer"}"""

private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private fun discordTestConfig(): Map<String, String> = mapOf(
    "oauth.discord.clientId" to "discord-client",
    "oauth.discord.clientSecret" to "discord-secret",
    "oauth.discord.botToken" to "",
    "oauth.secretKey" to Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
    "auth.oauthRegistration" to "true",
)
