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
import dev.kolektiv.kalendee.friends.FriendshipService
import dev.kolektiv.kalendee.groups.GroupService
import dev.kolektiv.kalendee.mail.LoggingMailer
import dev.kolektiv.kalendee.mail.MailService
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
import dev.kolektiv.kalendee.oauth.providers.GoogleProvider
import dev.kolektiv.kalendee.oauth.providers.MicrosoftProvider
import dev.kolektiv.kalendee.organizations.OrganizationService
import dev.kolektiv.kalendee.reminders.ReminderService
import dev.kolektiv.kalendee.storage.AvatarStorage
import dev.kolektiv.kalendee.storage.LocalAvatarStorage
import dev.kolektiv.kalendee.storage.S3AvatarStorage
import dev.kolektiv.kalendee.storage.StorageSettings
import dev.kolektiv.kalendee.store.PostgresCalendarStore
import dev.kolektiv.kalendee.web.AdminActions
import dev.kolektiv.kalendee.web.AuthActions
import dev.kolektiv.kalendee.web.AvailabilityActions
import dev.kolektiv.kalendee.web.CalendarActions
import dev.kolektiv.kalendee.web.ConnectionActions
import dev.kolektiv.kalendee.web.EventActions
import dev.kolektiv.kalendee.web.EventInviteActions
import dev.kolektiv.kalendee.web.FriendshipActions
import dev.kolektiv.kalendee.web.HolidayActions
import dev.kolektiv.kalendee.web.NotificationActions
import dev.kolektiv.kalendee.web.OrganizationActions
import dev.kolektiv.kalendee.web.ReminderActions
import dev.kolektiv.kalendee.web.ShareActions
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.ApplicationEnvironment
import kotlin.time.Clock
import org.koin.dsl.module
import org.koin.dsl.onClose

fun serverModule(environment: ApplicationEnvironment, developmentMode: Boolean) = module {
    single { DatabaseSettings.from(environment.config) }
    single { AuthSettings.from(environment.config, developmentMode) }
    single { OAuthSettings.from(environment.config) }
    single { AppSettings.from(environment.config, developmentMode) }
    single { MailSettings.from(environment.config) }
    single<Mailer> {
        val mail = get<MailSettings>()
        if (mail.enabled && mail.host.isNotBlank()) SmtpMailer(mail) else LoggingMailer()
    }
    single { MailService(get(), get()) }
    single { StorageSettings.from(environment.config) }
    single<AvatarStorage> {
        val storage = get<StorageSettings>()
        if (storage.s3.enabled && storage.s3.bucket.isNotBlank()) {
            S3AvatarStorage(storage.s3)
        } else {
            LocalAvatarStorage(storage.localDir)
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
    single {
        OrganizationService(
            database = get(),
            auth = get(),
            notifications = get(),
            mail = get(),
            clock = get(),
        )
    }
    single { ReminderService(database = get(), store = get(), clock = get()) }
    single { HttpClient(CIO) { expectSuccess = false } } onClose { it?.close() }
    single<TokenVault> { AesGcmTokenVault.from(get()) }
    single { OAuthStateService(database = get(), vault = get(), clock = get()) }
    single {
        ProviderRegistry(
            providers = listOf(
                GoogleProvider(settings = get<OAuthSettings>().google, http = get()),
                MicrosoftProvider(settings = get<OAuthSettings>().microsoft, http = get()),
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
    single<CalendarStore> { PostgresCalendarStore(database = get(), clock = get()) }
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
    single { AuthActions(auth = get(), settings = get(), verification = get(), loginAlerts = get()) }
    single { CalendarActions(store = get(), auth = get(), settings = get()) }
    single { ConnectionActions(connections = get(), auth = get(), settings = get()) }
    single {
        OrganizationActions(
            orgs = get(),
            auth = get(),
            settings = get(),
            database = get(),
        )
    }
    single { EventActions(store = get(), auth = get(), settings = get()) }
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
