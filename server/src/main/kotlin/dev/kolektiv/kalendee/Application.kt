package dev.kolektiv.kalendee

import dev.kolektiv.kalendee.admin.AdminCalendarService
import dev.kolektiv.kalendee.admin.AdminUserService
import dev.kolektiv.kalendee.api.DiscoveryResponse
import dev.kolektiv.kalendee.api.adminRoutes
import dev.kolektiv.kalendee.api.availabilityRoutes
import dev.kolektiv.kalendee.api.authPublicRoutes
import dev.kolektiv.kalendee.api.authSessionRoutes
import dev.kolektiv.kalendee.api.avatarRoutes
import dev.kolektiv.kalendee.api.calendarRoutes
import dev.kolektiv.kalendee.api.eventInviteRoutes
import dev.kolektiv.kalendee.api.eventRoutes
import dev.kolektiv.kalendee.api.faviconRoutes
import dev.kolektiv.kalendee.api.healthRoutes
import dev.kolektiv.kalendee.api.holidayRoutes
import dev.kolektiv.kalendee.api.notificationRoutes
import dev.kolektiv.kalendee.api.publicRoutes
import dev.kolektiv.kalendee.api.reminderRoutes
import dev.kolektiv.kalendee.api.rssRoutes
import dev.kolektiv.kalendee.api.shareRoutes
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.AuthSettings
import dev.kolektiv.kalendee.auth.EmailVerificationService
import dev.kolektiv.kalendee.auth.LoginAlertService
import dev.kolektiv.kalendee.availability.AvailabilityService
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.config.AppSettings
import dev.kolektiv.kalendee.demo.DemoSeeder
import dev.kolektiv.kalendee.events.EventInviteService
import dev.kolektiv.kalendee.groups.GroupService
import dev.kolektiv.kalendee.mail.MailService
import dev.kolektiv.kalendee.notifications.NotificationService
import dev.kolektiv.kalendee.organizations.OrganizationTeamService
import dev.kolektiv.kalendee.reminders.ReminderService
import dev.kolektiv.kalendee.web.ShareActions
import dev.kolektiv.kalendee.plugins.configureKeel
import dev.kolektiv.kalendee.plugins.configureKoin
import dev.kolektiv.kalendee.plugins.configureSerialization
import dev.kolektiv.kalendee.plugins.configureSessionAuth
import dev.kolektiv.kalendee.plugins.configureStatusPages
import dev.kolektiv.kalendee.storage.AvatarStorage
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.kolektiv.kalendee.Application")

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureKoin()
    configureApplication()
}

internal fun Application.configureApplication() {
    configureSerialization()
    val appSettings by inject<AppSettings>()
    configureStatusPages(appSettings.development)

    val store by inject<CalendarStore>()
    val authService by inject<AuthService>()
    val authSettings by inject<AuthSettings>()
    val groups by inject<GroupService>()
    val organizationTeams by inject<OrganizationTeamService>()
    val adminUsers by inject<AdminUserService>()
    val adminCalendars by inject<AdminCalendarService>()
    val verification by inject<EmailVerificationService>()
    val loginAlerts by inject<LoginAlertService>()
    val notifications by inject<NotificationService>()
    val reminders by inject<ReminderService>()
    val availability by inject<AvailabilityService>()
    val eventInvites by inject<EventInviteService>()
    val avatarStorage by inject<AvatarStorage>()
    val shareActions by inject<ShareActions>()
    val mail by inject<MailService>()
    val clock by inject<Clock>()
    val demoSeeder by inject<DemoSeeder>()
    configureSessionAuth(authService, authSettings)
    runBlocking {
        authService.seedAdmin()
        groups.seedSystemGroups()
        val backfilledTeams = organizationTeams.ensureDefaults()
        if (backfilledTeams > 0) {
            log.info("backfilled default organization teams for {} organizations", backfilledTeams)
        }
        if (appSettings.seedDemo) {
            if (!appSettings.development) {
                log.warn(
                    "KALENDEE_SEED_DEMO is enabled outside development: seeding demo data " +
                        "(demo/sam/alex with weak passwords) into this database",
                )
            }
            demoSeeder.seed()
        }
    }
    configureKeel()
    routing {
        faviconRoutes()
        rssRoutes(store, mail, clock, authService)
        route("/api/v1") {
            get {
                call.respond(DiscoveryResponse(service = "kalendee", api = "/api/v1"))
            }
            healthRoutes(store)
            authPublicRoutes(authService, authSettings, verification, loginAlerts)
            authSessionRoutes(authService, authSettings)
            notificationRoutes(notifications)
            adminRoutes(authService, groups, adminUsers, adminCalendars)
            avatarRoutes(authService, groups, avatarStorage)
            calendarRoutes(store)
            eventRoutes(store)
            eventInviteRoutes(eventInvites)
            reminderRoutes(reminders)
            holidayRoutes(store)
            availabilityRoutes(availability)
            publicRoutes(store, shareActions, authService)
            shareRoutes(shareActions)
        }
    }
}
