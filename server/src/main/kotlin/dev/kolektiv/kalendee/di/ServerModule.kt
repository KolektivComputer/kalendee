package dev.kolektiv.kalendee.di

import dev.kolektiv.kalendee.admin.AdminCalendarService
import dev.kolektiv.kalendee.admin.AdminUserService
import dev.kolektiv.kalendee.auth.Argon2PasswordHasher
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.EmailVerificationService
import dev.kolektiv.kalendee.auth.LoginAlertService
import dev.kolektiv.kalendee.auth.PasswordHasher
import dev.kolektiv.kalendee.availability.AvailabilityService
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.config.AppSettings
import dev.kolektiv.kalendee.db.DatabaseProvider
import dev.kolektiv.kalendee.db.DatabaseSettings
import dev.kolektiv.kalendee.demo.DemoSeeder
import dev.kolektiv.kalendee.events.EventInviteService
import dev.kolektiv.kalendee.events.EventUpdateService
import dev.kolektiv.kalendee.external.store.ExternalEventRouteStore
import dev.kolektiv.kalendee.external.store.ExternalEventStore
import dev.kolektiv.kalendee.external.store.PostgresExternalEventRouteStore
import dev.kolektiv.kalendee.external.store.PostgresExternalEventStore
import dev.kolektiv.kalendee.friends.FriendshipService
import dev.kolektiv.kalendee.groups.GroupService
import dev.kolektiv.kalendee.mail.CloudflareMailer
import dev.kolektiv.kalendee.mail.LoggingMailer
import dev.kolektiv.kalendee.mail.MailProvider
import dev.kolektiv.kalendee.mail.MailService
import dev.kolektiv.kalendee.mail.MailgunMailer
import dev.kolektiv.kalendee.mail.MailSettings
import dev.kolektiv.kalendee.mail.Mailer
import dev.kolektiv.kalendee.mail.SmtpMailer
import dev.kolektiv.kalendee.notifications.NotificationService
import dev.kolektiv.kalendee.oauth.AesGcmTokenVault
import dev.kolektiv.kalendee.oauth.ConnectionService
import dev.kolektiv.kalendee.oauth.OAuthSettings
import dev.kolektiv.kalendee.oauth.OAuthStateService
import dev.kolektiv.kalendee.oauth.ProviderRegistry
import dev.kolektiv.kalendee.oauth.TokenVault
import dev.kolektiv.kalendee.oauth.discord.DiscordImportService
import dev.kolektiv.kalendee.oauth.discord.DiscordPushService
import dev.kolektiv.kalendee.oauth.google.GoogleSyncService
import dev.kolektiv.kalendee.oauth.providers.DiscordApi
import dev.kolektiv.kalendee.oauth.providers.DiscordProvider
import dev.kolektiv.kalendee.oauth.providers.GoogleProvider
import dev.kolektiv.kalendee.organizations.OrganizationService
import dev.kolektiv.kalendee.organizations.OrganizationTeamService
import dev.kolektiv.kalendee.reminders.ReminderService
import dev.kolektiv.kalendee.storage.LocalObjectStorage
import dev.kolektiv.kalendee.storage.ObjectStorage
import dev.kolektiv.kalendee.storage.S3ObjectStorage
import dev.kolektiv.kalendee.storage.StorageSettings
import dev.kolektiv.kalendee.store.PostgresCalendarStore
import dev.kolektiv.kalendee.web.AdminActions
import dev.kolektiv.kalendee.web.AuthActions
import dev.kolektiv.kalendee.web.AvailabilityActions
import dev.kolektiv.kalendee.web.CalendarActions
import dev.kolektiv.kalendee.web.CalendarSyncInfoEnricher
import dev.kolektiv.kalendee.web.ConnectionActions
import dev.kolektiv.kalendee.web.DiscordActions
import dev.kolektiv.kalendee.web.EventActions
import dev.kolektiv.kalendee.web.EventInviteActions
import dev.kolektiv.kalendee.web.FriendshipActions
import dev.kolektiv.kalendee.web.HolidayActions
import dev.kolektiv.kalendee.web.NotificationActions
import dev.kolektiv.kalendee.web.OrganizationActions
import dev.kolektiv.kalendee.web.OrganizationTeamActions
import dev.kolektiv.kalendee.web.ReminderActions
import dev.kolektiv.kalendee.web.ShareActions
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.ApplicationEnvironment
import kotlin.time.Clock
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.kolektiv.kalendee.di.ServerModule")

fun serverModule(environment: ApplicationEnvironment, developmentMode: Boolean) = module {
    single { DatabaseSettings.from(environment.config) }
    single { AuthSettings.from(environment.config, developmentMode) }
    single { OAuthSettings.from(environment.config) }
    single { AppSettings.from(environment.config, developmentMode) }
    single { MailSettings.from(environment.config) }
    single<Mailer> {
        val mail = get<MailSettings>()
        when (mail.provider) {
            MailProvider.SMTP -> {
                if (mail.host.isNotBlank()) {
                    SmtpMailer(mail)
                } else {
                    log.warn(
                        "mail.provider is 'smtp' but mail.host is blank; falling back to the logging mailer",
                    )
                    LoggingMailer()
                }
            }
            MailProvider.CLOUDFLARE -> {
                if (mail.cloudflare.isComplete) {
                    CloudflareMailer(mail)
                } else {
                    log.warn(
                        "mail.provider is 'cloudflare' but mail.cloudflare.endpoint and " +
                            "mail.cloudflare.token are not both set; falling back to the logging mailer",
                    )
                    LoggingMailer()
                }
            }
            MailProvider.MAILGUN -> {
                if (mail.mailgun.isComplete) {
                    MailgunMailer(mail)
                } else {
                    log.warn(
                        "mail.provider is 'mailgun' but mail.mailgun.apiKey and " +
                            "mail.mailgun.domain are not both set; falling back to the logging mailer",
                    )
                    LoggingMailer()
                }
            }
            MailProvider.LOG -> LoggingMailer()
        }
    }
    single { MailService(get(), get()) }
    single { StorageSettings.from(environment.config) }
    single<ObjectStorage> {
        val storage = get<StorageSettings>()
        if (storage.s3.enabled && storage.s3.bucket.isNotBlank()) {
            S3ObjectStorage(storage.s3)
        } else {
            LocalObjectStorage(storage.localDir)
        }
    }
    single(createdAtStart = true) { DatabaseProvider.connect(get()) }
    single<Clock> { Clock.System }
    single<PasswordHasher> { Argon2PasswordHasher(get()) }
    single { EmailVerificationService(database = get(), mail = get(), settings = get(), clock = get()) }
    single {
        AuthService(
            database = get(),
            hasher = get(),
            settings = get(),
            appSettings = get(),
            clock = get(),
            verification = get(),
        )
    }
    single { LoginAlertService(auth = get(), mail = get(), clock = get()) }
    single { GroupService(database = get(), settings = get(), clock = get()) }
    single { AdminUserService(database = get(), hasher = get(), verification = get(), groups = get(), clock = get()) }
    single { AdminCalendarService(database = get(), clock = get()) }
    single { NotificationService(database = get(), clock = get()) }
    single { FriendshipService(database = get(), clock = get()) }
    single { OrganizationTeamService(database = get(), clock = get()) }
    single {
        OrganizationService(
            database = get(),
            auth = get(),
            notifications = get(),
            mail = get(),
            teams = get(),
            clock = get(),
        )
    }
    single { ReminderService(database = get(), store = get(), clock = get()) }
    single { HttpClient(CIO) { expectSuccess = false } } onClose { it?.close() }
    single<TokenVault> { AesGcmTokenVault.from(get()) }
    single { OAuthStateService(database = get(), vault = get(), clock = get()) }
    single { DiscordApi(http = get(), botToken = get<OAuthSettings>().discord.botToken) }
    single {
        ProviderRegistry(
            providers = listOf(
                DiscordProvider(settings = get<OAuthSettings>().discord, http = get(), api = get()),
                GoogleProvider(settings = get<OAuthSettings>().google, http = get()),
            ),
        )
    }
    single {
        ConnectionService(
            database = get(),
            vault = get(),
            states = get(),
            registry = get(),
            auth = get(),
            authSettings = get(),
            hasher = get(),
            appSettings = get(),
            clock = get(),
        )
    }
    single<CalendarStore> { PostgresCalendarStore(database = get(), teams = get(), clock = get()) }
    single<ExternalEventStore> { PostgresExternalEventStore(database = get(), clock = get()) }
    single<ExternalEventRouteStore> { PostgresExternalEventRouteStore(database = get(), clock = get()) }
    single {
        DiscordImportService(
            database = get(),
            connections = get(),
            store = get(),
            externalEvents = get(),
            routeStore = get(),
            api = get(),
            settings = get(),
            clock = get(),
        )
    }
    single { DiscordPushService(store = get(), externalEvents = get(), api = get()) }
    single { EventUpdateService(store = get(), discordPush = get()) }
    single {
        GoogleSyncService(
            database = get(),
            connections = get(),
            store = get(),
            externalEvents = get(),
            registry = get(),
            clock = get(),
        )
    }
    single {
        EventInviteService(
            database = get(),
            store = get(),
            notifications = get(),
            mail = get(),
            auth = get(),
            clock = get(),
        )
    }
    single {
        AvailabilityService(
            database = get(),
            store = get(),
            auth = get(),
            clock = get(),
            notifications = get(),
            mail = get(),
        )
    }
    single {
        DemoSeeder(
            database = get(),
            hasher = get(),
            auth = get(),
            groups = get(),
            store = get(),
            invites = get(),
            reminders = get(),
            notifications = get(),
            friendships = get(),
            availability = get(),
            storage = get(),
            settings = get(),
            clock = get(),
        )
    }
    single { loadFrontendBundle(environment) } onClose { it?.close() }
    single { CalendarSyncInfoEnricher(database = get()) }
    single { AuthActions(auth = get(), settings = get(), verification = get(), loginAlerts = get()) }
    single { CalendarActions(store = get(), auth = get(), settings = get(), syncInfo = get()) }
    single { ConnectionActions(connections = get(), googleSync = get(), auth = get(), settings = get()) }
    single { DiscordActions(imports = get(), auth = get(), settings = get()) }
    single {
        OrganizationActions(
            orgs = get(),
            auth = get(),
            settings = get(),
            database = get(),
        )
    }
    single {
        OrganizationTeamActions(
            teams = get(),
            orgs = get(),
            store = get(),
            auth = get(),
            settings = get(),
        )
    }
    single { EventActions(store = get(), auth = get(), settings = get(), eventUpdates = get()) }
    single { EventInviteActions(service = get(), auth = get(), settings = get()) }
    single { HolidayActions(store = get(), auth = get(), settings = get()) }
    single {
        AdminActions(
            auth = get(),
            settings = get(),
            groups = get(),
            adminUsers = get(),
            adminCalendars = get(),
        )
    }
    single { AvailabilityActions(service = get(), auth = get(), settings = get()) }
    single { NotificationActions(notifications = get(), auth = get(), settings = get()) }
    single { FriendshipActions(friendships = get(), auth = get(), settings = get(), notifications = get(), mail = get()) }
    single { ReminderActions(reminders = get(), auth = get(), settings = get()) }
    single {
        ShareActions(
            store = get(),
            auth = get(),
            settings = get(),
            notifications = get(),
            mail = get(),
            friendships = get(),
        )
    }
}
