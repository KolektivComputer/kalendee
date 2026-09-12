package dev.kolektiv.kalendee.plugins

import dev.kolektiv.kalendee.admin.AdminCalendarService
import dev.kolektiv.kalendee.auth.AuthService
import dev.kolektiv.kalendee.auth.PublicAccessMode
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.CalendarException
import dev.kolektiv.kalendee.calendar.CalendarPermission
import dev.kolektiv.kalendee.calendar.CalendarStore
import dev.kolektiv.kalendee.calendar.EventId
import dev.kolektiv.kalendee.calendar.ViewWindow
import dev.kolektiv.kalendee.events.EventInviteService
import dev.kolektiv.kalendee.friends.FriendshipService
import dev.kolektiv.kalendee.groups.GroupService
import dev.kolektiv.kalendee.notifications.NotificationService
import dev.kolektiv.kalendee.organizations.OrganizationService
import dev.kolektiv.kalendee.web.AdminActions
import dev.kolektiv.kalendee.web.AdminPage
import dev.kolektiv.kalendee.web.AvailabilityActions
import dev.kolektiv.kalendee.web.AdminUserSummary
import dev.kolektiv.kalendee.web.AuthActions
import dev.kolektiv.kalendee.web.CalendarActions
import dev.kolektiv.kalendee.web.ConnectionActions
import dev.kolektiv.kalendee.web.EventActions
import dev.kolektiv.kalendee.web.EventInviteActions
import dev.kolektiv.kalendee.web.FriendshipActions
import dev.kolektiv.kalendee.web.HolidayActions
import dev.kolektiv.kalendee.web.HomePage
import dev.kolektiv.kalendee.web.LoginPage
import dev.kolektiv.kalendee.web.NotFoundPage
import dev.kolektiv.kalendee.web.NotificationActions
import dev.kolektiv.kalendee.web.NotificationsPage
import dev.kolektiv.kalendee.web.OrganizationActions
import dev.kolektiv.kalendee.web.OrganizationProfilePage
import dev.kolektiv.kalendee.web.OrganizationSettingsPage
import dev.kolektiv.kalendee.web.PublicCalendarPage
import dev.kolektiv.kalendee.web.PublicDirectoryPage
import dev.kolektiv.kalendee.web.PublicProfilePage
import dev.kolektiv.kalendee.web.RegisterPage
import dev.kolektiv.kalendee.web.ReminderActions
import dev.kolektiv.kalendee.web.RsvpPage
import dev.kolektiv.kalendee.web.SettingsPage
import dev.kolektiv.kalendee.web.ShareActions
import dev.kolektiv.kalendee.web.VerifyEmailPage
import dev.kolektiv.kalendee.web.emptyHolidayState
import dev.kolektiv.kalendee.web.holidayCatalog
import dev.kolektiv.kalendee.web.toEventSummary
import dev.kolektiv.kalendee.web.toFriendRequestSummary
import dev.kolektiv.kalendee.web.toFriendSummary
import dev.kolektiv.kalendee.web.toState
import dev.kolektiv.kalendee.web.toSummary
import dev.kolektiv.kalendee.web.toViewer
import dev.kolektiv.kalendee.calendar.occurrences
import dev.kolektiv.keel.bundle.FrontendBundle
import dev.kolektiv.keel.ktor.PageMissingException
import dev.kolektiv.keel.ktor.PageRedirectException
import dev.kolektiv.keel.ktor.SharedProvider
import dev.kolektiv.keel.ktor.keel
import io.ktor.server.application.Application
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.koin.ktor.ext.inject

fun Application.configureKeel() {
    val bundle by inject<FrontendBundle>()
    val authActions by inject<AuthActions>()
    val calendarActions by inject<CalendarActions>()
    val connectionActions by inject<ConnectionActions>()
    val eventActions by inject<EventActions>()
    val eventInviteActions by inject<EventInviteActions>()
    val holidayActions by inject<HolidayActions>()
    val adminActions by inject<AdminActions>()
    val notificationActions by inject<NotificationActions>()
    val shareActions by inject<ShareActions>()
    val reminderActions by inject<ReminderActions>()
    val friendshipActions by inject<FriendshipActions>()
    val availabilityActions by inject<AvailabilityActions>()
    val organizationActions by inject<OrganizationActions>()
    val authService by inject<AuthService>()
    val store by inject<CalendarStore>()
    val eventInviteService by inject<EventInviteService>()
    val notificationService by inject<NotificationService>()
    val friendshipService by inject<FriendshipService>()
    val groupService by inject<GroupService>()
    val organizationService by inject<OrganizationService>()
    val adminCalendarService by inject<AdminCalendarService>()
    val clock by inject<Clock>()
    keel {
        this.bundle = bundle
        title = "Kalendee"
        notFoundPageId = "kalendee.notFound"
        shared = SharedProvider { call, _, _, _ ->
            buildJsonObject {
                put("service", "Kalendee")
                put("emailVerificationPolicy", runBlocking { authService.emailVerificationPolicy().wire })
                put("publicAccess", runBlocking { authService.publicAccess() })
                put("oauthRegistration", runBlocking { authService.isOAuthRegistrationOpen() })
                call.currentUser()?.let { user ->
                    putJsonObject("viewer") {
                        val viewer = user.toViewer()
                        put("id", viewer.id)
                        put("username", viewer.username)
                        put("displayName", viewer.displayName)
                        put("timeZone", viewer.timeZone)
                        put("accent", viewer.accent)
                        put("admin", viewer.admin)
                        put("email", viewer.email)
                        put("emailVerified", viewer.emailVerified)
                        put("avatarUrl", viewer.avatarUrl)
                        put("publicAccess", viewer.publicAccess)
                    }
                    put("unreadNotifications", runBlocking { notificationService.unreadCount(user.id) })
                    putJsonArray("organizations") {
                        runBlocking { organizationService.listFor(user.id) }.forEach { membership ->
                            addJsonObject {
                                put("id", membership.organization.id.value)
                                put("slug", membership.organization.slug)
                                put("displayName", membership.organization.displayName)
                                put("role", membership.role.wire)
                            }
                        }
                    }
                } ?: put("unreadNotifications", 0)
            }
        }
        pages {
            page<HomePage>("kalendee.home", "/") {
                val sessionUser = call.currentUser()
                val now = clock.now()
                val subject = sessionUser?.let { user ->
                    val asId = query["as"]
                    if (user.admin && !asId.isNullOrBlank()) {
                        runCatching { authService.userById(UserId.parse(asId)) }.getOrNull() ?: user
                    } else {
                        user
                    }
                }
                val readOnly = sessionUser != null && subject != null && sessionUser.id != subject.id
                val requestedTz = query["tz"]
                val defaultTz = requestedTz ?: subject?.timeZone ?: sessionUser?.timeZone ?: "UTC"
                val window = try {
                    ViewWindow.of(query["view"], query["date"], query["week"], defaultTz, now)
                } catch (_: CalendarException.Invalid) {
                    ViewWindow.of(null, null, null, "UTC", now)
                }
                val calendars = subject?.let { store.listCalendars(it.id) }.orEmpty()
                val ownerNames = calendars
                    .filter { it.permission != CalendarPermission.OWNER }
                    .map { it.ownerId }
                    .distinct()
                    .associateWith { authService.userById(it)?.displayName.orEmpty() }
                val calendarOrganizations = organizationActions.summariesById(
                    calendars.mapNotNull { it.organizationId }.distinct(),
                    subject?.id,
                )
                val events = subject?.let { store.listEvents(it.id, window.range) }.orEmpty()
                val holidayPrefs = subject?.let { store.holidayPrefs(it.id) }
                val holidayState = holidayPrefs?.toState() ?: emptyHolidayState()
                val holidayEvents = if (holidayPrefs != null && holidayPrefs.showHolidays) {
                    holidayPrefs.occurrences(window.gridStart, window.gridEnd)
                        .map { it.toEventSummary(window.timeZone) }
                } else {
                    emptyList()
                }
                val today = now.toLocalDateTime(window.timeZone).date
                val title = if (sessionUser == null) {
                    "Kalendee"
                } else {
                    "${window.label} — Kalendee"
                }
                val friends = sessionUser?.let { friendshipService.friends(it.id) }.orEmpty()
                val friendRequests = sessionUser?.let { friendshipService.incomingRequests(it.id) }.orEmpty()
                head(
                    title,
                    description = "Self-hosted calendars. Sign in to manage yours, or propose a time.",
                )
                HomePage(
                    viewer = sessionUser?.toViewer(),
                    viewingUser = subject?.takeIf { readOnly }?.toViewer(),
                    readOnly = readOnly,
                    registrationOpen = authService.isRegistrationOpen(),
                    timeZone = window.timeZone.id,
                    view = window.view.name.lowercase(),
                    date = window.date.toString(),
                    previousDate = window.previous.toString(),
                    nextDate = window.next.toString(),
                    weekStart = window.weekStart.toString(),
                    gridStart = window.gridStart.toString(),
                    gridEnd = window.gridEnd.toString(),
                    label = window.label,
                    today = today.toString(),
                    now = now.toString(),
                    calendars = calendars.map {
                        it.toSummary(
                            authService,
                            ownerName = ownerNames[it.ownerId].orEmpty(),
                            organization = it.organizationId?.let(calendarOrganizations::get),
                        )
                    },
                    events = events.map { it.toSummary() } + holidayEvents,
                    showHolidays = holidayState.showHolidays,
                    holidayCatalog = if (sessionUser == null) emptyList() else holidayCatalog(),
                    subscribedHolidayIds = holidayState.subscribedIds,
                    customHolidays = holidayState.customHolidays,
                    friends = friends.map { it.toFriendSummary() },
                    friendRequests = friendRequests.map { it.toFriendRequestSummary() },
                )
            }
            page<PublicCalendarPage>("kalendee.publicCalendar", "/c/{token}") {
                val token = params["token"].orEmpty()
                val calendar = store.publicCalendar(token)
                    ?: throw PageMissingException(path)
                val viewer = call.currentUser()
                if (authService.effectivePublicAccess(calendar) == PublicAccessMode.SIGNED_IN &&
                    viewer == null
                ) {
                    throw PageMissingException(path)
                }
                if (!authService.canViewPublic(calendar, viewer?.id)) {
                    throw PageMissingException(path)
                }
                val now = clock.now()
                val defaultTz = query["tz"] ?: calendar.timeZone
                val window = try {
                    ViewWindow.of(query["view"], query["date"], query["week"], defaultTz, now)
                } catch (_: CalendarException.Invalid) {
                    ViewWindow.of(null, null, null, "UTC", now)
                }
                val viewerCalendar = viewer?.let { store.getCalendar(calendar.id, it.id) }
                val ownerName = authService.userById(calendar.ownerId)?.displayName.orEmpty()
                val organization = calendar.organizationId?.let { organizationActions.summaryById(it, viewer?.id) }
                val events = store.listPublicEvents(calendar.id, window.range)
                val today = now.toLocalDateTime(window.timeZone).date
                head(
                    "${calendar.displayName} — Kalendee",
                    description = calendar.description ?: "A public calendar on Kalendee.",
                )
                PublicCalendarPage(
                    token = token,
                    calendar = (viewerCalendar ?: calendar).toSummary(
                        authService,
                        ownerName = ownerName,
                        organization = organization,
                    ),
                    events = events.map { it.toSummary() },
                    viewer = viewer?.toViewer(),
                    following = viewerCalendar?.permission == CalendarPermission.FOLLOW,
                    view = window.view.name.lowercase(),
                    date = window.date.toString(),
                    previousDate = window.previous.toString(),
                    nextDate = window.next.toString(),
                    weekStart = window.weekStart.toString(),
                    gridStart = window.gridStart.toString(),
                    gridEnd = window.gridEnd.toString(),
                    label = window.label,
                    today = today.toString(),
                    now = now.toString(),
                    timeZone = window.timeZone.id,
                    rssPath = "/rss/$token.xml",
                )
            }
            page<PublicProfilePage>("kalendee.profile", "/u/{username}") {
                val page = organizationActions.publicProfilePage(
                    params["username"].orEmpty(),
                    call.currentUser(),
                )
                head(
                    "${page.displayName} (@${page.username}) — Kalendee",
                    description = "Public profile for ${page.displayName} on Kalendee.",
                )
                page
            }
            page<OrganizationProfilePage>("kalendee.org", "/o/{slug}") {
                val page = organizationActions.organizationProfilePage(
                    params["slug"].orEmpty(),
                    call.currentUser(),
                    query["inviteToken"],
                )
                head(
                    "${page.org.displayName} — Kalendee",
                    description = page.org.description ?: "An organization on Kalendee.",
                )
                page
            }
            page<OrganizationSettingsPage>("kalendee.orgSettings", "/o/{slug}/settings") {
                val user = call.currentUser() ?: throw PageRedirectException("/login")
                val page = organizationActions.organizationSettingsPage(params["slug"].orEmpty(), user)
                head(
                    "${page.org.displayName} settings — Kalendee",
                    description = "Manage organization members on Kalendee.",
                )
                page
            }
            page<PublicDirectoryPage>("kalendee.directory", "/directory") {
                val page = organizationActions.directoryPage(call.currentUser())
                head(
                    "Directory — Kalendee",
                    description = "Browse public organizations, people, and calendars.",
                )
                page
            }
            page<AdminPage>("kalendee.admin", "/admin") {
                val user = call.currentUser()
                if (user == null) throw PageRedirectException("/login")
                if (!user.admin) throw PageRedirectException("/")
                val holidayPrefs = store.holidayPrefs(user.id)
                val listedUsers = authService.listUsers()
                val displayNames = listedUsers.associate { it.id to it.displayName }
                val listedCalendars = listedUsers.associateWith { store.listCalendars(it.id) }
                val calendarOrganizations = organizationActions.summariesById(
                    listedCalendars.values.flatten().mapNotNull { it.organizationId }.distinct(),
                    user.id,
                )
                val users = listedUsers.map { listed ->
                    AdminUserSummary(
                        id = listed.id.value,
                        username = listed.username,
                        displayName = listed.displayName,
                        timeZone = listed.timeZone,
                        admin = listed.admin,
                        calendars = listedCalendars.getValue(listed).map {
                            it.toSummary(
                                authService,
                                ownerName = displayNames[it.ownerId].orEmpty(),
                                organization = it.organizationId?.let(calendarOrganizations::get),
                            )
                        },
                        email = listed.email,
                        emailVerified = listed.emailVerified,
                        superadmin = groupService.isSuperadmin(listed.id),
                        storageBytes = groupService.usage(listed.id),
                        quotaBytes = groupService.effectiveQuota(listed.id),
                        groups = groupService.groupsFor(listed.id),
                    )
                }
                head("Admin — Kalendee", description = "Manage users and registration.")
                AdminPage(
                    viewer = user.toViewer(),
                    registrationOpen = authService.isRegistrationOpen(),
                    emailVerificationPolicy = authService.emailVerificationPolicy().wire,
                    publicAccess = authService.publicAccess(),
                    users = users,
                    showHolidays = holidayPrefs.showHolidays,
                    holidayCatalog = holidayCatalog(),
                    subscribedHolidayIds = holidayPrefs.subscribedIds,
                    customHolidays = holidayPrefs.custom.map { it.toSummary() },
                    groups = groupService.listGroups().map { it.toSummary() },
                    calendars = adminCalendarService.listCalendars().map { it.toSummary() },
                    oauthRegistration = authService.isOAuthRegistrationOpen(),
                )
            }
            page<SettingsPage>("kalendee.settings", "/settings") {
                val user = call.currentUser()
                if (user == null) throw PageRedirectException("/login")
                val holidayPrefs = store.holidayPrefs(user.id)
                val tab = when (query["tab"]) {
                    "appearance" -> "appearance"
                    "holidays" -> "holidays"
                    "notifications" -> "notifications"
                    else -> "account"
                }
                head("Settings — Kalendee", description = "Account, appearance, and holiday settings.")
                SettingsPage(
                    viewer = user.toViewer(),
                    tab = tab,
                    showHolidays = holidayPrefs.showHolidays,
                    holidayCatalog = holidayCatalog(),
                    subscribedHolidayIds = holidayPrefs.subscribedIds,
                    customHolidays = holidayPrefs.custom.map { it.toSummary() },
                    providers = connectionActions.providerSummaries(),
                    connections = connectionActions.connectionSummaries(user.id),
                )
            }
            page<LoginPage>("kalendee.login", "/login") {
                if (call.currentUser() != null) throw PageRedirectException("/")
                head("Sign in — Kalendee", description = "Sign in to Kalendee.")
                LoginPage(viewer = null)
            }
            page<RegisterPage>("kalendee.register", "/register") {
                if (call.currentUser() != null) throw PageRedirectException("/")
                val open = authService.isRegistrationOpen()
                head("Create account — Kalendee", description = "Create a Kalendee account.")
                RegisterPage(
                    viewer = null,
                    registrationOpen = open,
                    emailVerificationPolicy = authService.emailVerificationPolicy().wire,
                )
            }
            page<NotificationsPage>("kalendee.notifications", "/notifications") {
                val user = call.currentUser() ?: throw PageRedirectException("/login")
                val notifications = notificationService.list(user.id)
                head("Notifications — Kalendee", description = "Your Kalendee notifications.")
                NotificationsPage(
                    viewer = user.toViewer(),
                    notifications = notifications.map { it.toSummary() },
                    unreadCount = notificationService.unreadCount(user.id),
                )
            }
            page<VerifyEmailPage>("kalendee.verifyEmail", "/verify-email") {
                head("Verify email — Kalendee", description = "Verify your Kalendee email address.")
                VerifyEmailPage(
                    viewer = call.currentUser()?.toViewer(),
                    token = query["token"],
                )
            }
            page<RsvpPage>("kalendee.rsvp", "/rsvp/{eventId}") {
                val rawEventId = params["eventId"].orEmpty()
                val token = query["token"]?.takeIf { it.isNotBlank() }
                val viewer = call.currentUser()
                val lookup = if (token != null) {
                    eventInviteService.inviteByToken(token)
                } else {
                    runCatching { EventId.parse(rawEventId) }
                        .getOrNull()
                        ?.let { eventInviteService.openRsvpInvite(it, viewerId = viewer?.id) }
                }
                head("RSVP — Kalendee", description = "Respond to an event invitation.")
                RsvpPage(
                    eventId = lookup?.eventId?.value ?: rawEventId,
                    token = token,
                    valid = lookup != null,
                    title = lookup?.title,
                    whenText = lookup?.whenText,
                    calendarName = lookup?.calendarName,
                    status = lookup?.status,
                    requiresName = token == null && lookup != null && viewer == null,
                    viewer = viewer?.toViewer(),
                )
            }
            page<NotFoundPage>("kalendee.notFound", "/__not-found") {
                head("Not found — Kalendee", description = "No page at $path.")
                NotFoundPage(path = path)
            }
        }
        actions(
            authActions,
            calendarActions,
            connectionActions,
            eventActions,
            eventInviteActions,
            holidayActions,
            adminActions,
            notificationActions,
            shareActions,
            reminderActions,
            friendshipActions,
            availabilityActions,
            organizationActions,
        )
    }
}
