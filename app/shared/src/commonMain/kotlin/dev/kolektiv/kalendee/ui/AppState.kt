package dev.kolektiv.kalendee.ui

import dev.kolektiv.kalendee.auth.Accent
import dev.kolektiv.kalendee.auth.User
import dev.kolektiv.kalendee.auth.UserId
import dev.kolektiv.kalendee.calendar.Calendar
import dev.kolektiv.kalendee.calendar.CalendarView
import dev.kolektiv.kalendee.calendar.CreateEvent
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.UpdateEvent
import dev.kolektiv.kalendee.calendar.WeekWindow
import dev.kolektiv.kalendee.calendar.colorFor
import dev.kolektiv.kalendee.client.AuthResult
import dev.kolektiv.kalendee.client.FriendsResponse
import dev.kolektiv.kalendee.client.KalendeeApi
import dev.kolektiv.kalendee.client.KalendeeApiException
import dev.kolektiv.kalendee.client.KeyValueStore
import dev.kolektiv.kalendee.client.NotificationOut
import dev.kolektiv.kalendee.client.OrganizationSummaryOut
import dev.kolektiv.kalendee.client.ServerAccount
import dev.kolektiv.kalendee.client.ServerProfile
import dev.kolektiv.kalendee.client.ServerRegistry
import dev.kolektiv.kalendee.client.TaggedEvent
import dev.kolektiv.kalendee.client.normalizeBaseUrl
import dev.kolektiv.kalendee.client.randomId
import dev.kolektiv.kalendee.notify.platformNotificationScheduler
import dev.kolektiv.kalendee.ui.nav.Navigator
import dev.kolektiv.kalendee.ui.nav.Route
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone

enum class ThemeMode {
    System,
    Light,
    Dark,
    ;

    companion object {
        fun parse(raw: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: System
    }
}

/** One configured server plus the last known connection/session state. */
data class ServerUi(
    val account: ServerAccount,
    val online: Boolean? = null,
    val username: String? = null,
    val calendars: List<CalendarUi> = emptyList(),
    val error: String? = null,
) {
    val signedIn: Boolean get() = account.token != null
}

data class CalendarUi(
    val serverId: String,
    val calendar: Calendar,
    val visible: Boolean = !calendar.hidden,
)

/** An event merged from one of the configured servers. */
data class AggregatedEvent(
    val serverId: String,
    val serverName: String,
    val calendar: Calendar,
    val event: Event,
    val etag: String? = null,
)

/** A reminder merged from one of the configured servers (Wave 3 Reminders screen). */
data class AggregatedReminder(
    val id: String,
    val serverId: String,
    val serverName: String,
    val calendarId: String,
    val calendarName: String,
    val calendarColor: String,
    val title: String,
    val body: String,
    val at: Instant,
    val eventId: String,
    val offsetSeconds: Int,
)

data class AggregatedNotification(
    val serverId: String,
    val serverName: String,
    val notification: NotificationOut,
)

data class AppUiState(
    val servers: List<ServerUi> = emptyList(),
    val events: List<AggregatedEvent> = emptyList(),
    val currentRange: Pair<Instant, Instant> = defaultCalendarRange(),
    val loading: Boolean = false,
    val notice: String? = null,
    val themeMode: ThemeMode = ThemeMode.System,
    val defaultView: CalendarView = CalendarView.Week,
    val accent: String = Accent.Default,
    val upcoming: List<AggregatedReminder> = emptyList(),
    val notifications: List<AggregatedNotification> = emptyList(),
    /** Organizations per server id, from the last successful [AppState.refreshSocial]. */
    val organizationsByServer: Map<String, List<OrganizationSummaryOut>> = emptyMap(),
    /** Friends and incoming requests per server id. */
    val friendsByServer: Map<String, FriendsResponse> = emptyMap(),
    /** True while [AppState.refreshSocial] is fetching; kept separate from [loading]. */
    val socialLoading: Boolean = false,
    /** Per-server social fetch failures, keyed by server id. */
    val socialErrors: Map<String, String> = emptyMap(),
)

/** Monday 00:00 through next Monday 00:00 in [zone], the default calendar range. */
fun defaultCalendarRange(
    now: Instant = Clock.System.now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Pair<Instant, Instant> {
    val week = WeekWindow.of(week = null, timeZoneId = zone.id, now = now)
    return week.range.start to week.range.end
}

/** Best-effort display name for a server URL (`https://cal.example/x` -> `cal.example`). */
fun serverNameFromUrl(baseUrl: String): String {
    val host = baseUrl.substringAfter("://", baseUrl).substringBefore('/').substringBefore('?')
    return host.ifBlank { baseUrl }
}

private const val ThemeModeKey = "kalendee.prefs.themeMode"
private const val DefaultViewKey = "kalendee.prefs.defaultView"
private const val AccentKey = "kalendee.prefs.accent"

private fun defaultDispatcher(): CoroutineDispatcher =
    runCatching { Dispatchers.Main.immediate }.getOrElse { Dispatchers.Default }

/**
 * Single source of truth for the client app: server registry, aggregated calendar
 * data, reminders/notifications, persisted preferences and navigation.
 *
 * Networking happens in the caller's coroutine context; all state mutations go
 * through atomic [MutableStateFlow.update] calls (multi-step mutations are guarded
 * by [mutex]) so the UI never sees partially applied state.
 */
class AppState(
    private val store: KeyValueStore,
    apiFactory: ((ServerAccount) -> KalendeeApi)? = null,
    dispatcher: CoroutineDispatcher? = null,
) {
    val registry: ServerRegistry = ServerRegistry(store)

    val navigator: Navigator = Navigator()

    /** App-lifetime scope for fire-and-forget work; not used for synchronous state edits. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + (dispatcher ?: defaultDispatcher()))

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val socialMutex = Mutex()
    private val clients = mutableMapOf<String, KalendeeApi>()
    private val users = mutableMapOf<String, User>()
    private var loaded = false
    private var remindersScheduled = false

    private val createApi: (ServerAccount) -> KalendeeApi = apiFactory ?: { account ->
        KalendeeApi(
            baseUrl = normalizeBaseUrl(account.profile.baseUrl),
            tokenProvider = { registry.account(account.profile.id)?.token },
        )
    }

    // region reads

    fun serverUi(serverId: String): ServerUi? =
        _state.value.servers.firstOrNull { it.account.profile.id == serverId }

    fun isSignedIn(serverId: String): Boolean = serverUi(serverId)?.signedIn == true

    // endregion

    // region persisted state

    /** Loads the server registry and preferences once; safe to call repeatedly. */
    suspend fun loadPersisted() {
        mutex.withLock {
            if (loaded) return@withLock
            loaded = true
            val accounts = registry.accounts()
            accounts.forEach { ensureClient(it) }
            val themeMode = ThemeMode.parse(store.getString(ThemeModeKey))
            val defaultView = CalendarView.parse(store.getString(DefaultViewKey))
            _state.update { current ->
                current.copy(
                    servers = accounts.map(::serverUi),
                    themeMode = themeMode,
                    defaultView = defaultView,
                )
            }
            if (accounts.any { it.profile.enabled && it.token != null }) {
                navigator.replaceRoot(Route.Calendar)
            }
        }
    }

    // endregion

    // region server management

    /**
     * Validates [rawUrl] against the server discovery endpoint and persists a new
     * server profile. Returns the generated server id. Throws on invalid input or
     * when the target does not look like a Kalendee server.
     */
    suspend fun addServer(rawUrl: String, name: String? = null): String {
        val normalized = normalizeBaseUrl(rawUrl)
        val displayName = name?.trim().takeUnless { it.isNullOrEmpty() } ?: serverNameFromUrl(normalized)
        val probe = ServerAccount(
            profile = ServerProfile(id = "probe", name = displayName, baseUrl = normalized),
        )
        val discovery = createApi(probe).discovery()
        if (!discovery.service.contains("kalendee", ignoreCase = true)) {
            throw KalendeeApiException(
                status = 200,
                code = "not_kalendee",
                message = "Not a Kalendee server: ${discovery.service}",
            )
        }
        val account = ServerAccount(
            profile = ServerProfile(id = randomId(), name = displayName, baseUrl = normalized),
        )
        mutex.withLock {
            registry.upsert(account)
            ensureClient(account)
            _state.update { it.copy(servers = it.servers + serverUi(account)) }
        }
        return account.profile.id
    }

    suspend fun login(serverId: String, username: String, password: String) = withLoading {
        val api = clientFor(serverId)
        val session = api.login(username, password)
        val token = session.token
            ?: throw KalendeeApiException(200, "no_session", "The server did not return a session cookie")
        val resolvedUsername = session.user?.username ?: username
        mutex.withLock {
            registry.setSession(serverId, resolvedUsername, token)
            session.user?.let { users[serverId] = it }
            updateServer(serverId) {
                it.copy(
                    account = registry.account(serverId) ?: it.account,
                    online = true,
                    username = resolvedUsername,
                    error = null,
                )
            }
            _state.update { it.copy(accent = accentFromUsers()) }
        }
        refreshServer(serverId, _state.value.currentRange)
        runCatching { refreshSocial() }
    }

    /**
     * Registers an account. When the server returns a session immediately the app
     * signs in; otherwise (email verification, admin approval) the notice explains
     * the next step and no token is stored.
     */
    suspend fun register(
        serverId: String,
        username: String,
        password: String,
        email: String? = null,
    ): AuthResult = withLoading {
        val api = clientFor(serverId)
        val result = api.register(username, password, email)
        val user = result.user
        if (user != null) {
            users[serverId] = user
            val session = runCatching { api.login(username, password) }.getOrNull()
            if (session?.token != null) {
                val resolvedUsername = session.user?.username ?: username
                mutex.withLock {
                    registry.setSession(serverId, resolvedUsername, session.token)
                    updateServer(serverId) {
                        it.copy(
                            account = registry.account(serverId) ?: it.account,
                            online = true,
                            username = resolvedUsername,
                            error = null,
                        )
                    }
                    _state.update { it.copy(accent = accentFromUsers()) }
                }
                refreshServer(serverId, _state.value.currentRange)
                runCatching { refreshSocial() }
            } else {
                _state.update { it.copy(notice = "Account created. Sign in to continue.") }
            }
        } else {
            val notice = if (result.verificationRequired) {
                "Check your email to verify your account, then sign in."
            } else {
                "Registration submitted. Sign in once the server confirms your account."
            }
            _state.update { it.copy(notice = notice) }
        }
        result
    }

    /** Signs out and clears the stored token; local data for the server is dropped. */
    suspend fun logout(serverId: String) {
        clients[serverId]?.let { api -> runCatching { api.logout() } }
        mutex.withLock {
            users.remove(serverId)
            registry.setSession(serverId, null, null)
            updateServer(serverId) {
                it.copy(
                    account = registry.account(serverId) ?: it.account,
                    username = null,
                    calendars = emptyList(),
                    error = null,
                )
            }
            _state.update { current ->
                current.copy(
                    events = current.events.filterNot { it.serverId == serverId },
                    upcoming = current.upcoming.filterNot { it.serverId == serverId },
                    organizationsByServer = current.organizationsByServer - serverId,
                    friendsByServer = current.friendsByServer - serverId,
                    socialErrors = current.socialErrors - serverId,
                    accent = accentFromUsers(),
                )
            }
        }
    }

    suspend fun removeServer(serverId: String) {
        mutex.withLock {
            clients.remove(serverId)
            users.remove(serverId)
            registry.remove(serverId)
            _state.update { current ->
                current.copy(
                    servers = current.servers.filterNot { it.account.profile.id == serverId },
                    events = current.events.filterNot { it.serverId == serverId },
                    upcoming = current.upcoming.filterNot { it.serverId == serverId },
                    notifications = current.notifications.filterNot { it.serverId == serverId },
                    organizationsByServer = current.organizationsByServer - serverId,
                    friendsByServer = current.friendsByServer - serverId,
                    socialErrors = current.socialErrors - serverId,
                    accent = accentFromUsers(),
                )
            }
        }
    }

    /** Renames a configured server locally; only the device profile is touched. */
    suspend fun renameServer(serverId: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Server name must not be blank" }
        mutex.withLock {
            val account = registry.account(serverId) ?: return@withLock
            val updated = account.copy(profile = account.profile.copy(name = trimmed))
            registry.upsert(updated)
            updateServer(serverId) { it.copy(account = updated) }
            _state.update { current ->
                current.copy(
                    events = current.events.map { event ->
                        if (event.serverId == serverId) event.copy(serverName = trimmed) else event
                    },
                )
            }
        }
    }

    suspend fun setServerEnabled(serverId: String, enabled: Boolean) {
        mutex.withLock {
            registry.setEnabled(serverId, enabled)
            updateServer(serverId) { it.copy(account = registry.account(serverId) ?: it.account) }
        }
        if (enabled) {
            refreshServer(serverId, _state.value.currentRange)
        }
    }

    // endregion

    // region calendars and events

    /** Refreshes every enabled server; failures are recorded per server, never thrown. */
    suspend fun refreshAll(range: Pair<Instant, Instant>? = null) = withLoading {
        val target = range ?: _state.value.currentRange
        _state.update { it.copy(currentRange = target) }
        registry.accounts()
            .filter { it.profile.enabled }
            .forEach { account -> refreshServer(account.profile.id, target) }
    }

    suspend fun setCalendarVisible(serverId: String, calendarId: String, visible: Boolean) = withLoading {
        val updated = clientFor(serverId).setCalendarHidden(calendarId, hidden = !visible)
        updateServer(serverId) { server ->
            server.copy(
                calendars = server.calendars.map { ui ->
                    if (ui.calendar.id.value == calendarId) {
                        ui.copy(calendar = updated, visible = visible)
                    } else {
                        ui
                    }
                },
            )
        }
        refreshServer(serverId, _state.value.currentRange)
    }

    suspend fun createEvent(serverId: String, calendarId: String, command: CreateEvent): Event = withLoading {
        val tagged = clientFor(serverId).createEvent(calendarId, command)
        refreshServer(serverId, _state.value.currentRange)
        tagged.event
    }

    suspend fun updateEvent(
        serverId: String,
        eventId: String,
        update: UpdateEvent,
        etag: String?,
    ): Event = withLoading {
        val tagged = clientFor(serverId).updateEvent(eventId, update, etag)
        refreshServer(serverId, _state.value.currentRange)
        tagged.event
    }

    suspend fun deleteEvent(serverId: String, eventId: String, etag: String?) = withLoading {
        clientFor(serverId).deleteEvent(eventId, etag)
        refreshServer(serverId, _state.value.currentRange)
    }

    suspend fun eventDetail(serverId: String, eventId: String): TaggedEvent =
        clientFor(serverId).event(eventId)

    // endregion

    // region reminders and notifications

    /**
     * Fetches upcoming reminders (168h) for every enabled, signed-in server and keeps
     * the 60 earliest. The OS scheduler is only invoked on the first call per app
     * start unless [forceReschedule] is set (the Settings "Reschedule" action).
     */
    suspend fun refreshReminders(forceReschedule: Boolean = false) {
        val accounts = registry.accounts().filter { it.profile.enabled && it.token != null }
        val planned = mutableListOf<AggregatedReminder>()
        for (account in accounts) {
            val api = clients[account.profile.id] ?: ensureClient(account)
            val reminders = runCatching { api.upcomingReminders(168) }.getOrNull() ?: continue
            planned += planReminders(account.profile.id, account.profile.name, reminders)
        }
        val upcoming = capReminders(planned)
        _state.update { it.copy(upcoming = upcoming) }
        if (forceReschedule || !remindersScheduled) {
            remindersScheduled = true
            runCatching {
                platformNotificationScheduler().reschedule(upcoming.map { it.toPendingReminder() })
            }
        }
    }

    suspend fun refreshNotifications() {
        val accounts = registry.accounts().filter { it.profile.enabled && it.token != null }
        val aggregated = mutableListOf<AggregatedNotification>()
        for (account in accounts) {
            val api = clients[account.profile.id] ?: ensureClient(account)
            val notifications = runCatching { api.notifications() }.getOrNull() ?: continue
            aggregated += notifications.map {
                AggregatedNotification(account.profile.id, account.profile.name, it)
            }
        }
        _state.update { current ->
            current.copy(notifications = aggregated.sortedByDescending { it.notification.createdAt })
        }
    }

    suspend fun markNotificationRead(id: String) {
        val entry = _state.value.notifications.firstOrNull { it.notification.id == id } ?: return
        clientFor(entry.serverId).markNotificationRead(id)
        _state.update { current ->
            current.copy(
                notifications = current.notifications.map { item ->
                    if (item.serverId == entry.serverId && item.notification.id == id) {
                        item.copy(notification = item.notification.copy(read = true))
                    } else {
                        item
                    }
                },
            )
        }
    }

    suspend fun markAllNotificationsRead() {
        val serverIds = _state.value.notifications.map { it.serverId }.distinct()
        for (serverId in serverIds) {
            runCatching { clientFor(serverId).markAllNotificationsRead() }
        }
        _state.update { current ->
            current.copy(
                notifications = current.notifications.map {
                    it.copy(notification = it.notification.copy(read = true))
                },
            )
        }
    }

    // endregion

    // region social

    /**
     * Fetches organizations and friends for every enabled, signed-in server.
     *
     * Failures are recorded per server in [AppUiState.socialErrors] and never
     * thrown. Concurrent calls are coalesced: a second caller returns while the
     * first fetch is still running, so the sidebar cannot stack spinners.
     */
    suspend fun refreshSocial() {
        if (!socialMutex.tryLock()) return
        try {
            val accounts = registry.accounts().filter { it.profile.enabled && it.token != null }
            _state.update { it.copy(socialLoading = true) }
            val organizations = mutableMapOf<String, List<OrganizationSummaryOut>>()
            val friends = mutableMapOf<String, FriendsResponse>()
            val errors = mutableMapOf<String, String>()
            for (account in accounts) {
                val serverId = account.profile.id
                val api = clients[serverId] ?: ensureClient(account)
                runCatching { api.organizations() }
                    .onSuccess { organizations[serverId] = it }
                    .onFailure { errors[serverId] = it.message ?: "Failed to load organizations" }
                runCatching { api.friends() }
                    .onSuccess { friends[serverId] = it }
                    .onFailure { errors[serverId] = it.message ?: "Failed to load friends" }
            }
            val activeIds = accounts.map { it.profile.id }.toSet()
            mutex.withLock {
                _state.update { current ->
                    current.copy(
                        organizationsByServer =
                            current.organizationsByServer.filterKeys { it in activeIds } + organizations,
                        friendsByServer = current.friendsByServer.filterKeys { it in activeIds } + friends,
                        socialErrors = errors,
                    )
                }
            }
        } finally {
            _state.update { it.copy(socialLoading = false) }
        }
    }

    // endregion

    // region preferences

    fun setThemeMode(mode: ThemeMode) {
        store.putString(ThemeModeKey, mode.name)
        _state.update { it.copy(themeMode = mode) }
    }

    /** Persists a local accent override; only ids in [Accent.ids] are accepted. */
    fun setAccent(accent: String) {
        val value = accent.trim().lowercase()
        if (value !in Accent.ids) return
        store.putString(AccentKey, value)
        _state.update { it.copy(accent = value) }
    }

    fun setDefaultView(view: CalendarView) {
        store.putString(DefaultViewKey, view.name)
        _state.update { it.copy(defaultView = view) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    // endregion

    // region internals

    private suspend fun <T> withLoading(block: suspend () -> T): T {
        _state.update { it.copy(loading = true) }
        try {
            return block()
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private fun serverUi(account: ServerAccount): ServerUi = ServerUi(
        account = account,
        username = account.username,
    )

    private fun updateServer(serverId: String, transform: (ServerUi) -> ServerUi) {
        _state.update { current ->
            current.copy(
                servers = current.servers.map { server ->
                    if (server.account.profile.id == serverId) transform(server) else server
                },
            )
        }
    }

    private fun accentFromUsers(): String {
        // A local Appearance choice wins over the signed-in user's server accent.
        store.getString(AccentKey)?.trim()?.lowercase()?.takeIf { it in Accent.ids }?.let { return it }
        for (server in _state.value.servers) {
            val user = users[server.account.profile.id] ?: continue
            return user.accent
        }
        return Accent.Default
    }

    private fun ensureClient(account: ServerAccount): KalendeeApi =
        clients.getOrPut(account.profile.id) { createApi(account) }

    private fun clientFor(serverId: String): KalendeeApi {
        clients[serverId]?.let { return it }
        val account = registry.account(serverId)
            ?: throw IllegalArgumentException("Unknown server: $serverId")
        return ensureClient(account)
    }

    /**
     * Refreshes one server: `me()` first (401 marks the session signed-out), then
     * calendars and events. Any error is captured into [ServerUi.error]; previous
     * data is kept for transient failures and dropped when signed out.
     */
    private suspend fun refreshServer(serverId: String, range: Pair<Instant, Instant>) {
        val account = registry.account(serverId) ?: return
        if (!account.profile.enabled) return
        val api = ensureClient(account)

        var online: Boolean? = null
        var username: String? = account.username
        var error: String? = null
        try {
            val user = api.me()
            users[serverId] = user
            online = true
            username = user.username
        } catch (e: KalendeeApiException) {
            online = true
            if (e.isUnauthorized) {
                users.remove(serverId)
                username = null
            } else {
                error = e.message
            }
        } catch (e: Exception) {
            online = false
            error = e.message ?: "Server unreachable"
        }

        if (online == false) {
            mutex.withLock {
                updateServer(serverId) {
                    it.copy(
                        account = registry.account(serverId) ?: it.account,
                        online = false,
                        error = error,
                    )
                }
            }
            return
        }

        if (username == null) {
            mutex.withLock {
                updateServer(serverId) {
                    it.copy(
                        account = registry.account(serverId) ?: it.account,
                        online = true,
                        username = null,
                        calendars = emptyList(),
                        error = error,
                    )
                }
                _state.update { current ->
                    current.copy(
                        events = current.events.filterNot { it.serverId == serverId },
                        accent = accentFromUsers(),
                    )
                }
            }
            return
        }

        val calendars = runCatching { api.calendars() }
            .onFailure { if (error == null) error = it.message ?: "Failed to load calendars" }
            .getOrDefault(emptyList())
            .map { CalendarUi(serverId = serverId, calendar = it, visible = !it.hidden) }
        val events = runCatching { api.events(range.first, range.second) }
            .onFailure { if (error == null) error = it.message ?: "Failed to load events" }
            .getOrDefault(emptyList())

        mutex.withLock {
            val serverName = registry.account(serverId)?.profile?.name ?: account.profile.name
            val calendarsById = calendars.associateBy { it.calendar.id.value }
            _state.update { current ->
                current.copy(
                    servers = current.servers.map { server ->
                        if (server.account.profile.id == serverId) {
                            server.copy(
                                account = registry.account(serverId) ?: server.account,
                                online = online,
                                username = username,
                                calendars = calendars,
                                error = error,
                            )
                        } else {
                            server
                        }
                    },
                    events = (
                        current.events.filterNot { it.serverId == serverId } +
                            events.map { event ->
                                AggregatedEvent(
                                    serverId = serverId,
                                    serverName = serverName,
                                    calendar = calendarsById[event.calendarId.value]?.calendar
                                        ?: fallbackCalendar(event),
                                    event = event,
                                    etag = event.etag,
                                )
                            }
                        ).sortedWith(compareBy({ it.event.start }, { it.event.title })),
                    accent = accentFromUsers(),
                )
            }
        }
    }

    private fun fallbackCalendar(event: Event): Calendar = Calendar(
        id = event.calendarId,
        ownerId = UserId("unknown"),
        displayName = "Calendar",
        color = colorFor(event.calendarId.value),
        createdAt = event.createdAt,
        updatedAt = event.updatedAt,
    )

    // endregion
}
