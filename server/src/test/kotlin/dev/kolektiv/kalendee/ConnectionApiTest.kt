package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.ConnectionsResponse
import dev.kolektiv.kalendee.api.ErrorBody
import dev.kolektiv.kalendee.api.OAuthProvidersResponse
import dev.kolektiv.kalendee.api.SyncConnectionOut
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.db.CalendarConnectionsTable
import dev.kolektiv.kalendee.oauth.TokenVault
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.koin.ktor.ext.get

class ConnectionApiTest {
    @Test
    fun providersArePublicAndRegisterRedirects() = testApplication {
        installApi(extraConfig = oauthConfig())
        val anonymous = jsonClient(followRedirects = false)

        val providers = anonymous.get("/api/v1/oauth/providers")
        assertEquals(HttpStatusCode.OK, providers.status, providers.bodyAsText())
        val listed = providers.body<OAuthProvidersResponse>().providers
        val discord = listed.single { it.id == "discord" }
        assertTrue(discord.enabled)
        assertEquals("Discord", discord.displayName)
        assertEquals("/api/v1/oauth/discord/start", discord.connectUrl)
        val registerUrl = discord.registerUrl ?: error("missing register url")
        assertTrue(registerUrl.startsWith("https://discord.com/oauth2/authorize"), registerUrl)
        val google = listed.single { it.id == "google" }
        assertFalse(google.enabled)
        assertNull(google.registerUrl)

        val redirect = anonymous.get("/api/v1/oauth/discord/register?return_to=/settings")
        assertEquals(HttpStatusCode.Found, redirect.status)
        val location = Url(redirect.headers[HttpHeaders.Location] ?: error("missing redirect"))
        assertEquals("discord.com", location.host)
        assertEquals("/oauth2/authorize", location.encodedPath)
        assertEquals("discord-client", location.parameters["client_id"])
        assertNotNull(location.parameters["state"])

        val json = anonymous.get("/api/v1/oauth/discord/register?format=json&return_to=/settings")
        assertEquals(HttpStatusCode.OK, json.status, json.bodyAsText())
        val body = json.bodyAsText()
        val url = Url(
            Json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.content
                ?: error("missing url in $body"),
        )
        assertEquals("discord.com", url.host)
    }

    @Test
    fun anonymousRegistrationCreatesSessionAndConnection() = testApplication {
        installApi(httpClient = HttpClient(mockDiscordEngine()), extraConfig = oauthConfig())
        val anonymous = jsonClient(followRedirects = false)

        val register = anonymous.get("/api/v1/oauth/discord/register?format=json")
        assertEquals(HttpStatusCode.OK, register.status, register.bodyAsText())
        val registerBody = register.bodyAsText()
        val authorizationUrl = Url(
            Json.parseToJsonElement(registerBody).jsonObject["url"]?.jsonPrimitive?.content
                ?: error("missing register url in $registerBody"),
        )
        val state = authorizationUrl.parameters["state"] ?: error("missing state")

        val callback = anonymous.get("/api/v1/oauth/discord/callback?code=mock-code&state=$state")
        assertEquals(HttpStatusCode.Found, callback.status, callback.bodyAsText())
        assertEquals("/settings?tab=connections&oauth=ok", callback.headers[HttpHeaders.Location])
        assertNotNull(callback.headers[HttpHeaders.SetCookie])

        val me = anonymous.get("/api/v1/auth/me")
        assertEquals(HttpStatusCode.OK, me.status, me.bodyAsText())

        val connections = anonymous.get("/api/v1/connections")
        assertEquals(HttpStatusCode.OK, connections.status, connections.bodyAsText())
        val connection = connections.body<ConnectionsResponse>().connections.single()
        assertEquals("discord", connection.provider)
        assertEquals("Discord", connection.providerName)
        assertEquals("OAuth User", connection.displayName)
        assertEquals("active", connection.status)
        assertNull(connection.lastError)

        val sessionProviders = anonymous.get("/api/v1/connections/providers")
        assertEquals(HttpStatusCode.OK, sessionProviders.status)
        assertTrue(
            sessionProviders.body<OAuthProvidersResponse>().providers
                .single { it.id == "discord" }
                .enabled,
        )
    }

    @Test
    fun providersListingStaysPublicWhenRegistrationIsClosed() = testApplication {
        installApi(extraConfig = oauthConfig(oauthRegistration = false))
        val anonymous = jsonClient(followRedirects = false)

        val providers = anonymous.get("/api/v1/oauth/providers")
        assertEquals(HttpStatusCode.OK, providers.status)
        assertNull(providers.body<OAuthProvidersResponse>().providers.single { it.id == "discord" }.registerUrl)

        assertEquals(
            HttpStatusCode.Unauthorized,
            anonymous.get("/api/v1/oauth/discord/register").status,
        )
    }

    @Test
    fun connectionRoutesRequireSession() = testApplication {
        installApi(extraConfig = oauthConfig())
        val anonymous = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/v1/connections").status)
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/v1/connections/providers").status)
        assertEquals(HttpStatusCode.Unauthorized, anonymous.delete("/api/v1/connections/${Uuid.random()}").status)
        assertEquals(HttpStatusCode.Unauthorized, anonymous.post("/api/v1/connections/${Uuid.random()}/sync").status)
    }

    @Test
    fun disconnectRequiresOwnConnection() = testApplication {
        lateinit var database: Database
        installApi(extraConfig = oauthConfig(), configure = { database = get() })
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val bob = jsonClient()
        bob.registerAndLogin("bob")
        val connectionId = seedConnection(database, aliceUser.id, provider = "discord")

        assertEquals(
            HttpStatusCode.NotFound,
            bob.delete("/api/v1/connections/$connectionId").status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            alice.delete("/api/v1/connections/$connectionId").status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            alice.delete("/api/v1/connections/$connectionId").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            alice.delete("/api/v1/connections/not-a-uuid").status,
        )
    }

    @Test
    fun syncRejectsDiscordAndUnknownProviders() = testApplication {
        lateinit var database: Database
        installApi(extraConfig = oauthConfig(), configure = { database = get() })
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val discordId = seedConnection(database, aliceUser.id, provider = "discord")
        val otherId = seedConnection(database, aliceUser.id, provider = "acme")

        val discord = alice.post("/api/v1/connections/$discordId/sync")
        assertEquals(HttpStatusCode.UnprocessableEntity, discord.status)
        assertEquals("use Discord server sync for this connection", discord.body<ErrorBody>().message)

        val unsupported = alice.post("/api/v1/connections/$otherId/sync")
        assertEquals(HttpStatusCode.UnprocessableEntity, unsupported.status)
        assertEquals("sync is not supported for this provider", unsupported.body<ErrorBody>().message)

        val missing = alice.post("/api/v1/connections/${Uuid.random()}/sync")
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun googleSyncHappyPathAndErrorMapping() = testApplication {
        val google = GoogleMock()
        lateinit var database: Database
        lateinit var vault: TokenVault
        installApi(
            httpClient = HttpClient(mockGoogleEngine(google)),
            extraConfig = oauthConfig(),
            configure = { database = get(); vault = get() },
        )
        val alice = jsonClient()
        val aliceUser = alice.registerAndLogin("alice")
        val id = seedGoogleConnection(database, vault, aliceUser.id)

        val ok = alice.post("/api/v1/connections/$id/sync")
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val synced = ok.body<SyncConnectionOut>()
        assertTrue(synced.ok)
        assertNotNull(synced.lastSyncAt)
        assertNull(synced.lastError)

        google.status = HttpStatusCode.Unauthorized
        val reauth = alice.post("/api/v1/connections/$id/sync")
        assertEquals(HttpStatusCode.Conflict, reauth.status, reauth.bodyAsText())
        val reauthBody = reauth.body<ErrorBody>()
        assertEquals("conflict", reauthBody.error)
        assertTrue(reauthBody.message.isNotBlank())

        google.failHard = true
        val failure = alice.post("/api/v1/connections/$id/sync")
        assertEquals(HttpStatusCode.BadGateway, failure.status, failure.bodyAsText())
        val failureBody = failure.body<ErrorBody>()
        assertEquals("bad_gateway", failureBody.error)
        assertTrue(failureBody.message.isNotBlank())
    }
}

private class GoogleMock {
    var status: HttpStatusCode = HttpStatusCode.OK
    var body: String = """{"items":[]}"""
    var failHard: Boolean = false
}

private fun mockGoogleEngine(mock: GoogleMock): MockEngine = MockEngine { request ->
    val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    when {
        request.url.host != "www.googleapis.com" ->
            respond("unexpected request to ${request.url}", HttpStatusCode.NotFound)
        mock.failHard -> error("google mock exploded")
        else -> respond(mock.body, mock.status, json)
    }
}

private fun mockDiscordEngine(): MockEngine = MockEngine { request ->
    val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    val path = request.url.encodedPath
    when {
        request.url.host != "discord.com" ->
            respond("unexpected request to ${request.url}", HttpStatusCode.NotFound)
        request.method == HttpMethod.Post && path.endsWith("/oauth2/token") -> respond(
            content = """{"access_token":"access-302","refresh_token":"refresh-302","expires_in":604800,""" +
                """"scope":"identify guilds","token_type":"Bearer"}""",
            status = HttpStatusCode.OK,
            headers = json,
        )
        request.method == HttpMethod.Get && path.endsWith("/users/@me") -> respond(
            content = """{"id":"302","username":"oauthuser","global_name":"OAuth User","avatar":null}""",
            status = HttpStatusCode.OK,
            headers = json,
        )
        else -> respond("unexpected request to ${request.url}", HttpStatusCode.NotFound)
    }
}

private suspend fun seedConnection(
    database: Database,
    userId: UserId,
    provider: String,
): String {
    val id = Uuid.random()
    val now = Clock.System.now()
    withContext(Dispatchers.IO) {
        suspendTransaction(database) {
            CalendarConnectionsTable.insert {
                it[CalendarConnectionsTable.id] = id
                it[CalendarConnectionsTable.userId] = Uuid.parse(userId.value)
                it[CalendarConnectionsTable.provider] = provider
                it[externalAccountId] = "$provider-account"
                it[accessTokenCiphertext] = "sealed"
                it[accessTokenNonce] = "nonce"
                it[tokenKeyVersion] = 1
                it[status] = "active"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }
    return id.toString()
}

private suspend fun seedGoogleConnection(
    database: Database,
    vault: TokenVault,
    userId: UserId,
): String {
    val id = Uuid.random()
    val externalAccountId = "google-account"
    val aad = "calendar-connection:$userId:google:$externalAccountId".toByteArray(Charsets.UTF_8)
    val sealedToken = vault.seal("google-access".toByteArray(Charsets.UTF_8), aad)
    val now = Clock.System.now()
    withContext(Dispatchers.IO) {
        suspendTransaction(database) {
            CalendarConnectionsTable.insert {
                it[CalendarConnectionsTable.id] = id
                it[CalendarConnectionsTable.userId] = Uuid.parse(userId.value)
                it[CalendarConnectionsTable.provider] = "google"
                it[CalendarConnectionsTable.externalAccountId] = externalAccountId
                it[accessTokenCiphertext] = sealedToken.ciphertext
                it[accessTokenNonce] = sealedToken.nonce
                it[tokenKeyVersion] = sealedToken.keyVersion
                it[status] = "active"
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }
    return id.toString()
}

private val connectionTestSecretKey: String =
    Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

private fun oauthConfig(oauthRegistration: Boolean = true): Map<String, String> = mapOf(
    "oauth.discord.clientId" to "discord-client",
    "oauth.discord.clientSecret" to "discord-secret",
    "oauth.discord.botToken" to "",
    "oauth.secretKey" to connectionTestSecretKey,
    "auth.oauthRegistration" to oauthRegistration.toString(),
)
