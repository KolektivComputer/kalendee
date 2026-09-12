package dev.kolektiv.kalendee

import com.typesafe.config.ConfigFactory
import dev.kolektiv.kalendee.api.VerifyEmailBody
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.LoginUser
import dev.kolektiv.kalendee.auth.RegisterUser
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
    fun oauthSettingsParseProvidersAndDisableBlankClientIds() {
        val settings = OAuthSettings.from(
            MapApplicationConfig(
                "oauth.google.clientId" to "google-client",
                "oauth.google.clientSecret" to "google-secret",
                "oauth.microsoft.clientId" to "  ",
                "oauth.secretKey" to oauthTestSecretKey,
            ),
        )
        assertTrue(settings.google.enabled)
        assertEquals("google-secret", settings.google.clientSecret)
        assertFalse(settings.microsoft.enabled)
        assertEquals(oauthTestSecretKey, settings.secretKey)
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
                    provider = "google",
                    pkce = pkce,
                    redirectUri = "https://kalendee.test/api/v1/oauth/google/callback",
                    returnTo = "/settings?tab=connections",
                )
                val mismatch = assertFailsWith<CalendarException.Invalid> { states.consume(state, "microsoft") }
                assertEquals("oauth state provider mismatch", mismatch.message)

                val record = states.consume(state, "google")
                assertEquals(pkce.verifier, record.codeVerifier)
                assertEquals("/settings?tab=connections", record.returnTo)
                assertEquals("google", record.provider)

                assertFailsWith<CalendarException.Invalid> { states.consume(state, "google") }
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
                    provider = "google",
                    pkce = Pkce.generate(),
                    redirectUri = "https://kalendee.test/api/v1/oauth/google/callback",
                    returnTo = null,
                )
                assertFailsWith<CalendarException.Invalid> { states.consume("missing-state", "google") }
                val future = object : Clock {
                    override fun now() = Clock.System.now() + 11.minutes
                }
                val expired = OAuthStateService(database = get(), vault = get(), clock = future)
                assertFailsWith<CalendarException.Invalid> { expired.consume(state, "google") }
            }
        }
    }

    @Test
    fun sessionLinksProviderAccount() = testApplication {
        installApi(httpClient = HttpClient(mockGoogleEngine()), extraConfig = oauthTestConfig())
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")

        val start = client.startGoogle()
        assertEquals("offline", start.authorizationUrl.parameters["access_type"])
        assertEquals("consent", start.authorizationUrl.parameters["prompt"])
        assertEquals("true", start.authorizationUrl.parameters["include_granted_scopes"])
        assertEquals("S256", start.authorizationUrl.parameters["code_challenge_method"])
        assertEquals(
            "https://kalendee.test/api/v1/oauth/google/callback",
            start.authorizationUrl.parameters["redirect_uri"],
        )
        val scope = start.authorizationUrl.parameters["scope"].orEmpty()
        assertTrue("https://www.googleapis.com/auth/calendar" in scope)

        val callback = client.callbackGoogle(start.state)
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("/settings?tab=connections&oauth=ok", callback.headers[HttpHeaders.Location])
        assertNull(callback.headers[HttpHeaders.SetCookie], "an existing session must be kept")

        val page = client.settingsPage()
        assertEquals(1, page.connections.size)
        assertEquals("google", page.connections.single().provider)
        assertEquals("Google Calendar", page.connections.single().providerName)
        assertEquals("user@example.com", page.connections.single().accountEmail)
        assertEquals("OAuth User", page.connections.single().displayName)
        assertEquals("active", page.connections.single().status)
        assertTrue(page.providers.single { it.id == "google" }.enabled)
        assertEquals("/api/v1/oauth/google/start", page.providers.single { it.id == "google" }.connectUrl)
        assertFalse(page.providers.single { it.id == "microsoft" }.enabled)

        val asJson = client.get("/api/v1/oauth/google/start?format=json")
        assertEquals(HttpStatusCode.OK, asJson.status)
        assertTrue(asJson.bodyAsText().contains("accounts.google.com"), asJson.bodyAsText())

        val action = client.post("${Keel.ACTION_PATH}/kalendee.connectProvider") {
            contentType(ContentType.Application.Json)
            setBody("""{"providerId":"google","returnTo":"/settings"}""")
        }
        assertEquals(HttpStatusCode.OK, action.status, action.bodyAsText())
        assertTrue(action.bodyAsText().contains("accounts.google.com"), action.bodyAsText())

        val disconnected = client.post("${Keel.ACTION_PATH}/kalendee.disconnectAccount") {
            contentType(ContentType.Application.Json)
            setBody("""{"connectionId":"${page.connections.single().id}"}""")
        }
        assertEquals(HttpStatusCode.OK, disconnected.status, disconnected.bodyAsText())
        assertTrue(client.settingsPage().connections.isEmpty())
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
    fun verifiedEmailMatchSignsInAndLinks() = testApplication {
        val mail = RecordingMailer()
        installApi(mailer = mail, httpClient = HttpClient(mockGoogleEngine()), extraConfig = oauthTestConfig())
        val state = installAnonymousState(returnTo = "/settings?tab=connections")

        val local = jsonClient(followRedirects = false)
        val registered = local.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterUser(username = "mey", password = "password12", email = "User@Example.com"))
        }
        assertEquals(HttpStatusCode.Created, registered.status, registered.bodyAsText())
        val verified = local.post("/api/v1/auth/verify-email") {
            contentType(ContentType.Application.Json)
            setBody(VerifyEmailBody(mail.lastVerificationToken()))
        }
        assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
        local.post("/api/v1/auth/logout")

        val anon = jsonClient(followRedirects = false)
        val callback = anon.callbackGoogle(state)
        assertEquals(HttpStatusCode.Found, callback.status, callback.bodyAsText())
        assertEquals("/settings?tab=connections", callback.headers[HttpHeaders.Location])
        assertNotNull(callback.headers[HttpHeaders.SetCookie], "sign-in must mint a session cookie")

        val me = anon.get("/api/v1/auth/me")
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText().contains("\"username\":\"mey\""))
        assertTrue(me.bodyAsText().contains("\"emailVerified\":true"))

        val page = anon.settingsPage()
        val connection = page.connections.single()
        assertEquals("user@example.com", connection.accountEmail)
    }

    @Test
    fun unknownEmailIsRejectedWhenOAuthRegistrationIsOff() = testApplication {
        installApi(
            httpClient = HttpClient(mockGoogleEngine(email = "stranger@example.com")),
            extraConfig = oauthTestConfig(oauthRegistration = false),
        )
        val state = installAnonymousState()
        val anon = jsonClient(followRedirects = false)
        val callback = anon.callbackGoogle(state)
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("/login?oauth=registration_closed", callback.headers[HttpHeaders.Location])
        assertNull(callback.headers[HttpHeaders.SetCookie])
        val me = anon.get("/api/v1/auth/me")
        assertEquals(HttpStatusCode.Unauthorized, me.status)
    }

    @Test
    fun oauthRegistrationCreatesVerifiedUserWhenOpen() = testApplication {
        installApi(httpClient = HttpClient(mockGoogleEngine()), extraConfig = oauthTestConfig(oauthRegistration = true))
        val state = installAnonymousState()
        val anon = jsonClient(followRedirects = false)
        val callback = anon.callbackGoogle(state)
        assertEquals(HttpStatusCode.Found, callback.status, callback.bodyAsText())
        assertEquals("/settings?tab=connections&oauth=ok", callback.headers[HttpHeaders.Location])
        assertNotNull(callback.headers[HttpHeaders.SetCookie])

        val me = anon.get("/api/v1/auth/me")
        assertEquals(HttpStatusCode.OK, me.status)
        val body = me.bodyAsText()
        assertTrue(body.contains("\"username\":\"user\""), body)
        assertTrue(body.contains("\"email\":\"user@example.com\""), body)
        assertTrue(body.contains("\"emailVerified\":true"), body)
        assertTrue(body.contains("\"admin\":true"), "the first user is an admin")
    }

    @Test
    fun oauthRegistrationStillRespectsClosedRegistration() = testApplication {
        installApi(
            registration = "closed",
            httpClient = HttpClient(mockGoogleEngine()),
            extraConfig = oauthTestConfig(oauthRegistration = true),
        )
        val state = installAnonymousState()
        val anon = jsonClient(followRedirects = false)
        val callback = anon.callbackGoogle(state)
        assertEquals(HttpStatusCode.Found, callback.status)
        assertEquals("/login?oauth=registration_closed", callback.headers[HttpHeaders.Location])
        assertNull(callback.headers[HttpHeaders.SetCookie])
    }

    @Test
    fun duplicateProviderIdentityUpdatesTheExistingConnection() = testApplication {
        installApi(httpClient = HttpClient(mockGoogleEngine()), extraConfig = oauthTestConfig())
        val client = jsonClient(followRedirects = false)
        client.registerAndLogin(username = "mey")

        val first = client.startGoogle()
        assertEquals(HttpStatusCode.Found, client.callbackGoogle(first.state).status)
        val firstConnection = client.settingsPage().connections.single()

        val second = client.startGoogle()
        assertEquals(HttpStatusCode.Found, client.callbackGoogle(second.state).status)
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
            assertTrue("KALENDEE_SECRET_KEY" in text, "missing secret key in $file")
            assertTrue("KALENDEE_AUTH_OAUTH_REGISTRATION" in text, "missing oauth registration env in $file")
            assertFalse(text.contains("oauthRegistration = true"), "oauth registration must default to off in $file")
            val config = ConfigFactory.parseFile(file.toFile()).resolve()
            assertTrue(config.hasPath("auth.oauthRegistration"), "auth.oauthRegistration must resolve in $file")
        }
        val envFiles = listOf(Path.of("../.env.example"), Path.of("../.env.dev.example"))
        for (file in envFiles) {
            assertTrue(Files.exists(file), "missing $file")
            val text = Files.readString(file)
            assertTrue("KALENDEE_SECRET_KEY" in text, "missing secret key placeholder in $file")
            assertTrue("KALENDEE_GOOGLE_CLIENT_ID" in text, "missing google client placeholder in $file")
            assertTrue("KALENDEE_MICROSOFT_CLIENT_ID" in text, "missing microsoft client placeholder in $file")
        }
    }

    private suspend fun ApplicationTestBuilder.installAnonymousState(returnTo: String? = null): String {
        var state: String? = null
        application {
            runBlocking {
                state = get<OAuthStateService>().create(
                    userId = null,
                    provider = "google",
                    pkce = Pkce.generate(),
                    redirectUri = "https://kalendee.test/api/v1/oauth/google/callback",
                    returnTo = returnTo,
                )
            }
        }
        startApplication()
        return state ?: error("oauth state was not created")
    }

    private suspend fun HttpClient.startGoogle(): GoogleStart {
        val start = get("/api/v1/oauth/google/start")
        assertEquals(HttpStatusCode.Found, start.status, start.bodyAsText())
        val url = Url(start.headers[HttpHeaders.Location] ?: error("missing authorization redirect"))
        return GoogleStart(
            authorizationUrl = url,
            state = url.parameters["state"] ?: error("missing oauth state"),
        )
    }

    private suspend fun HttpClient.callbackGoogle(state: String): HttpResponse =
        get("/api/v1/oauth/google/callback?code=mock-code&state=$state")

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

    private data class GoogleStart(val authorizationUrl: Url, val state: String)
}

private val oauthTestSecretKey: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

private fun oauthTestConfig(
    oauthRegistration: Boolean = true,
    googleClientId: String = "google-client",
    microsoftClientId: String = "",
): Map<String, String> = mapOf(
    "oauth.google.clientId" to googleClientId,
    "oauth.google.clientSecret" to "google-secret",
    "oauth.microsoft.clientId" to microsoftClientId,
    "oauth.microsoft.clientSecret" to "microsoft-secret",
    "oauth.secretKey" to oauthTestSecretKey,
    "auth.oauthRegistration" to oauthRegistration.toString(),
)

private fun mockGoogleEngine(
    email: String = "user@example.com",
    externalId: String = "google-1",
): MockEngine = MockEngine { request ->
    val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    when (request.url.host) {
        "oauth2.googleapis.com" -> respond(
            content = """{"access_token":"access-$externalId","refresh_token":"refresh-$externalId",""" +
                """"expires_in":3600,"scope":"openid email profile","token_type":"Bearer"}""",
            status = HttpStatusCode.OK,
            headers = json,
        )
        "openidconnect.googleapis.com" -> respond(
            content = """{"sub":"$externalId","email":"$email","name":"OAuth User"}""",
            status = HttpStatusCode.OK,
            headers = json,
        )
        else -> respond("unexpected request to ${request.url}", HttpStatusCode.NotFound)
    }
}
