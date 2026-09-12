package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.api.AuthResult
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.LoginUser
import dev.kolektiv.kalendee.auth.RegisterUser
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.mail.Mailer
import dev.kolektiv.kalendee.mail.OutboundMail
import dev.kolektiv.kalendee.plugins.configureKoin
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.ClientProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.ext.get

internal fun h2TestUrl(): String {
    val name = "kalendee_${java.util.UUID.randomUUID().toString().replace("-", "")}"
    return "jdbc:h2:mem:$name;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;" +
        "NON_KEYWORDS=MONTH,DAY,YEAR,HOUR,MINUTE,SECOND,VALUE,KEY"
}

internal fun writeH2Migrations(dir: Path): Path {
    dir.createDirectories()
    val loader = Thread.currentThread().contextClassLoader
    val names = listOf(
        "V1__calendars_and_events.sql",
        "V2__users_sessions_ownership.sql",
        "V3__user_settings_recurrence_hidden.sql",
        "V4__calendar_color_and_holidays.sql",
        "V5__admin_and_app_settings.sql",
        "V6__user_accent.sql",
        "V7__user_email_avatar.sql",
        "V8__email_verification_notifications.sql",
        "V9__calendar_sharing.sql",
        "V10__event_reminders.sql",
        "V11__friendships.sql",
        "V12__availability.sql",
        "V13__event_invites.sql",
        "V14__groups_quotas.sql",
        "V15__anonymous_slot_requests.sql",
        "V16__organizations.sql",
        "V18__organization_teams.sql",
    )
    for (name in names) {
        val sql = loader.getResource("db/migration/$name")?.readText()
            ?: error("missing migration $name")
        dir.resolve(name).writeText(h2CompatibleSql(sql))
    }
    return dir
}

internal fun h2CompatibleSql(sql: String): String {
    val withTypes = sql.replace(Regex("(?i)TIMESTAMPTZ"), "TIMESTAMP WITH TIME ZONE")
    val multiAdd = Regex(
        """ALTER TABLE\s+(\w+)\s+(ADD COLUMN\s+[^;]+);""",
        setOf(RegexOption.IGNORE_CASE),
    )
    return multiAdd.replace(withTypes) { match ->
        val table = match.groupValues[1]
        val parts = match.groupValues[2].split(Regex(""",\s*(?=ADD COLUMN)""", RegexOption.IGNORE_CASE))
        if (parts.size == 1) {
            match.value
        } else {
            parts.joinToString("\n") { "ALTER TABLE $table ${it.trim()};" }
        }
    }
}

internal fun postgresTestConfig(
    registration: String = "open",
    packDir: String,
    adminPassword: String? = null,
    superadminUsername: String? = null,
): MapApplicationConfig {
    val entries = mutableListOf(
        "database.url" to h2TestUrl(),
        "database.user" to "sa",
        "database.password" to "",
        "database.migrations" to writeH2Migrations(Files.createTempDirectory("kalendee-h2-migrations")).toString(),
        "auth.registration" to registration,
        "auth.cookieSecure" to "false",
        "auth.adminPassword" to (adminPassword ?: ""),
        "auth.superadminUsername" to (superadminUsername ?: ""),
        "auth.argon2.memoryKib" to "8",
        "auth.argon2.iterations" to "1",
        "auth.argon2.parallelism" to "1",
        "keel.packDir" to packDir,
        "storage.localDir" to Files.createTempDirectory("kalendee-avatars").toString(),
        "app.baseUrl" to "https://kalendee.test",
    )
    return MapApplicationConfig(*entries.toTypedArray())
}

internal fun writeStubPack(pack: Path) {
    val pages = mapOf(
        "kalendee.home" to "pages/kalendee.home.js",
        "kalendee.login" to "pages/kalendee.login.js",
        "kalendee.register" to "pages/kalendee.register.js",
        "kalendee.admin" to "pages/kalendee.admin.js",
        "kalendee.settings" to "pages/kalendee.settings.js",
        "kalendee.notifications" to "pages/kalendee.notifications.js",
        "kalendee.verifyEmail" to "pages/kalendee.verifyEmail.js",
        "kalendee.rsvp" to "pages/kalendee.rsvp.js",
        "kalendee.publicCalendar" to "pages/kalendee.publicCalendar.js",
        "kalendee.profile" to "pages/kalendee.profile.js",
        "kalendee.org" to "pages/kalendee.org.js",
        "kalendee.orgSettings" to "pages/kalendee.orgSettings.js",
        "kalendee.directory" to "pages/kalendee.directory.js",
        "kalendee.notFound" to "pages/kalendee.notFound.js",
    )
    val pageEntries = pages.entries.joinToString(",\n") { (pageId, module) ->
        """"$pageId": { "module": "$module" }"""
    }
    pack.resolve("manifest.json").writeText(
        """
        {
          "format": "keel/1",
          "id": "kalendee",
          "version": "0.1.0",
          "framework": "svelte",
          "host": "#__keel_root",
          "pages": {
            $pageEntries
          },
          "notFound": "pages/kalendee.notFound.js"
        }
        """.trimIndent(),
    )
    pack.resolve("bootstrap.js").writeText("export function bootstrap() {}")
    pack.resolve("pages").createDirectories()
    for (module in pages.values) {
        pack.resolve(module).writeText("export async function mount() {}")
    }
}

internal fun ApplicationTestBuilder.installApi(
    registration: String = "open",
    adminPassword: String? = null,
    mailer: Mailer? = null,
    superadminUsername: String? = null,
    configure: (suspend Application.() -> Unit)? = null,
) {
    val pack = Files.createTempDirectory("kalendee-keel-pack")
    writeStubPack(pack)
    environment {
        config = postgresTestConfig(registration, pack.toString(), adminPassword, superadminUsername)
    }
    application {
        val overrides: Module? = if (mailer == null) {
            null
        } else {
            module {
                mailer?.let { recording -> single<Mailer> { recording } }
            }
        }
        configureKoin(extra = overrides)
        configureApplication()
        runBlocking { get<AuthService>().seedAdmin() }
        val app = this
        configure?.let { hook -> runBlocking { hook(app) } }
    }
}

internal class RecordingMailer : Mailer {
    private val messages = CopyOnWriteArrayList<OutboundMail>()

    val sent: List<OutboundMail> get() = messages.toList()

    override suspend fun send(message: OutboundMail) {
        messages.add(message)
    }

    fun clear() {
        messages.clear()
    }

    fun lastVerificationToken(): String {
        val text = sent.lastOrNull { "verify-email?token=" in it.text }?.text
            ?: error("no verification mail sent")
        val token = VerificationTokenPattern.find(text)?.groupValues?.get(1)
        return token ?: error("no verification token in mail")
    }

    private companion object {
        val VerificationTokenPattern = Regex("""verify-email\?token=([A-Za-z0-9_-]+)""")
    }
}

internal fun ClientProvider.jsonClient() = createClient {
    install(HttpCookies)
    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                explicitNulls = true
                ignoreUnknownKeys = true
            },
        )
    }
}

internal suspend fun HttpClient.registerAndLogin(
    username: String = "mey",
    password: String = "password12",
): User {
    val response = post("/api/v1/auth/register") {
        contentType(ContentType.Application.Json)
        setBody(RegisterUser(username = username, password = password))
    }
    check(response.status == HttpStatusCode.Created) {
        "register failed: ${response.status}"
    }
    val result = response.body<AuthResult>()
    return result.user ?: error("register did not create a session")
}

internal suspend fun HttpClient.login(
    username: String = "mey",
    password: String = "password12",
): AuthResult {
    val response = post("/api/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(LoginUser(username = username, password = password))
    }
    check(response.status == HttpStatusCode.OK) {
        "login failed: ${response.status}"
    }
    return response.body()
}
