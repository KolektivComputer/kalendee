package dev.kolektiv.kalendee.ui

import dev.kolektiv.kalendee.calendar.CalendarView
import dev.kolektiv.kalendee.client.KalendeeApi
import dev.kolektiv.kalendee.client.KalendeeApiException
import dev.kolektiv.kalendee.client.KeyValueStore
import dev.kolektiv.kalendee.client.ServerAccount
import dev.kolektiv.kalendee.client.ServerProfile
import dev.kolektiv.kalendee.client.ServerRegistry
import dev.kolektiv.kalendee.ui.nav.Route
import dev.kolektiv.kalendee.ui.screens.friends.friendRelationshipLabel
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone

private const val USER_ID = "7d444840-9dc0-11d1-b245-5ffdce74fad2"
private const val CALENDAR_ID = "b1a2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
private const val EVENT_ID = "3f0c6e0a-8b1e-4c2e-9a5d-7f6b1c2d3e4f"

private val jsonHeaders: Headers =
    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private val DISCOVERY_JSON = """{"service":"kalendee","api":"/api/v1"}"""

private fun userJson(username: String = "alice", accent: String = "primary"): String = """
    {
      "id": "$USER_ID",
      "username": "$username",
      "displayName": "Alice",
      "email": null,
      "emailVerified": false,
      "avatarVersion": null,
      "timeZone": "UTC",
      "accent": "$accent",
      "admin": false,
      "publicAccess": "inherit",
      "createdAt": "2026-01-01T00:00:00Z"
    }
""".trimIndent()

private val ORGANIZATIONS_JSON = """
    {
      "organizations": [
        {
          "id": "org-1",
          "slug": "acme",
          "displayName": "Acme Inc",
          "description": null,
          "visibility": "private",
          "avatarUrl": null,
          "role": "member",
          "memberCount": 4
        }
      ]
    }
""".trimIndent()

private val FRIENDS_JSON = """
    {
      "friends": [
        {
          "userId": "user-2",
          "username": "bob",
          "displayName": "Bob",
          "avatarUrl": null
        }
      ],
      "incoming": [
        {
          "id": "req-1",
          "user": {
            "userId": "user-3",
            "username": "carol",
            "displayName": "Carol",
            "avatarUrl": null
          },
          "createdAt": "2026-10-01T00:00:00Z"
        }
      ]
    }
""".trimIndent()

private val FRIENDS_AFTER_ACCEPT_JSON = """
    {
      "friends": [
        {
          "userId": "user-2",
          "username": "bob",
          "displayName": "Bob",
          "avatarUrl": null
        },
        {
          "userId": "user-3",
          "username": "carol",
          "displayName": "Carol",
          "avatarUrl": null
        }
      ],
      "incoming": []
    }
""".trimIndent()

private val FRIENDS_BOB_ONLY_JSON = """
    {
      "friends": [
        {
          "userId": "user-2",
          "username": "bob",
          "displayName": "Bob",
          "avatarUrl": null
        }
      ],
      "incoming": []
    }
""".trimIndent()

private val FRIENDS_CAROL_ONLY_JSON = """
    {
      "friends": [
        {
          "userId": "user-3",
          "username": "carol",
          "displayName": "Carol",
          "avatarUrl": null
        }
      ],
      "incoming": []
    }
""".trimIndent()

private val FRIEND_REQUEST_ACCEPTED_JSON = """
    {
      "status": "accepted",
      "friend": {
        "userId": "user-3",
        "username": "carol",
        "displayName": "Carol",
        "avatarUrl": null
      }
    }
""".trimIndent()

private val FRIEND_REQUEST_PENDING_JSON = """
    {
      "status": "pending",
      "friend": {
        "userId": "user-4",
        "username": "dave",
        "displayName": "Dave",
        "avatarUrl": null
      }
    }
""".trimIndent()

private val SEARCH_RESULTS_JSON = """
    {
      "results": [
        {
          "userId": "user-4",
          "username": "dave",
          "displayName": "Dave",
          "avatarUrl": null,
          "relationship": "none"
        },
        {
          "userId": "user-3",
          "username": "carol",
          "displayName": "Carol",
          "avatarUrl": null,
          "relationship": "pending_in"
        }
      ]
    }
""".trimIndent()

private fun calendarJson(): String = """
    {
      "id": "$CALENDAR_ID",
      "ownerId": "$USER_ID",
      "displayName": "Work",
      "timeZone": "UTC",
      "color": "#3b82f6",
      "hidden": false,
      "permission": "owner",
      "accessMode": "inherit",
      "createdAt": "2026-10-01T00:00:00Z",
      "updatedAt": "2026-10-01T00:00:00Z"
    }
""".trimIndent()

private fun eventJson(title: String = "Standup"): String = """
    {
      "id": "$EVENT_ID",
      "calendarId": "$CALENDAR_ID",
      "title": "$title",
      "start": "2026-10-06T09:00:00Z",
      "end": "2026-10-06T09:30:00Z",
      "allDay": false,
      "status": "CONFIRMED",
      "etag": "etag-1",
      "createdAt": "2026-10-01T08:00:00Z",
      "updatedAt": "2026-10-01T08:00:00Z"
    }
""".trimIndent()

private val REMINDERS_JSON = """
    [
      {
        "eventId": "$EVENT_ID",
        "calendarId": "$CALENDAR_ID",
        "calendarName": "Work",
        "calendarColor": "#3b82f6",
        "title": "Standup",
        "start": "2026-10-06T09:00:00Z",
        "allDay": false,
        "offsetSeconds": 600,
        "remindAt": "2026-10-06T08:50:00Z"
      }
    ]
""".trimIndent()

private class TestStore : KeyValueStore {
    val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

private val neverEngine = MockEngine { error("no requests expected") }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun testState(
    store: KeyValueStore,
    engineFor: (ServerAccount) -> HttpClientEngine,
): AppState = AppState(
    store = store,
    apiFactory = { account ->
        KalendeeApi(
            baseUrl = account.profile.baseUrl,
            tokenProvider = { account.token },
            engine = engineFor(account),
        )
    },
    dispatcher = UnconfinedTestDispatcher(),
)

private fun signedInAccount(id: String, name: String, baseUrl: String): ServerAccount = ServerAccount(
    profile = ServerProfile(id = id, name = name, baseUrl = baseUrl),
    username = "alice",
    token = "kalendee_session=$id",
)

class AppStateTest {

    @Test
    fun addServerValidatesDiscoveryAndPersists() = runTest {
        val store = TestStore()
        val engine = MockEngine { request ->
            assertEquals("/api/v1", request.url.encodedPath)
            respond(DISCOVERY_JSON, HttpStatusCode.OK, jsonHeaders)
        }
        val state = testState(store) { engine }

        val id = state.addServer("calendar.example", "Home")

        val server = state.state.value.servers.single()
        assertEquals(id, server.account.profile.id)
        assertEquals("Home", server.account.profile.name)
        assertEquals("https://calendar.example", server.account.profile.baseUrl)
        assertNotNull(store.values["kalendee.servers.v1"])
    }

    @Test
    fun addServerRejectsBlankUrl() = runTest {
        val store = TestStore()
        val state = testState(store) { neverEngine }

        assertFailsWith<IllegalArgumentException> { state.addServer("   ") }

        assertTrue(state.state.value.servers.isEmpty())
        assertNull(store.values["kalendee.servers.v1"])
    }

    @Test
    fun addServerRejectsNonKalendeeServer() = runTest {
        val store = TestStore()
        val engine = MockEngine {
            respond("""{"service":"something-else","api":"/api/v1"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val state = testState(store) { engine }

        assertFailsWith<KalendeeApiException> { state.addServer("https://other.example") }

        assertTrue(state.state.value.servers.isEmpty())
        assertNull(store.values["kalendee.servers.v1"])
    }

    @Test
    fun addServerSurfacesDiscoveryHttpErrors() = runTest {
        val store = TestStore()
        val engine = MockEngine {
            respond(
                content = """{"error":"not_found","message":"no api here"}""",
                status = HttpStatusCode.NotFound,
                headers = jsonHeaders,
            )
        }
        val state = testState(store) { engine }

        val failure = assertFailsWith<KalendeeApiException> {
            state.addServer("https://other.example")
        }

        assertEquals(404, failure.status)
        assertEquals("no api here", failure.message)
    }

    @Test
    fun refreshAggregatesServersAndToleratesFailures() = runTest {
        val store = TestStore()
        val registry = ServerRegistry(store)
        registry.upsert(signedInAccount("a", "Alpha", "https://a.example"))
        registry.upsert(signedInAccount("b", "Beta", "https://b.example"))

        val good = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/me" -> respond(userJson(), HttpStatusCode.OK, jsonHeaders)
                "/api/v1/calendars" -> respond("[${calendarJson()}]", HttpStatusCode.OK, jsonHeaders)
                "/api/v1/events" -> respond("[${eventJson()}]", HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
        val bad = MockEngine { throw IllegalStateException("connection refused") }
        val state = testState(store) { account ->
            if (account.profile.id == "a") good else bad
        }

        state.loadPersisted()
        state.refreshAll(Instant.parse("2026-10-01T00:00:00Z") to Instant.parse("2026-10-08T00:00:00Z"))

        val ui = state.state.value
        assertEquals(2, ui.servers.size)
        assertFalse(ui.loading)

        val alpha = ui.servers.first { it.account.profile.id == "a" }
        assertEquals(true, alpha.online)
        assertEquals("alice", alpha.username)
        assertEquals(1, alpha.calendars.size)
        assertEquals(1, ui.events.size)
        assertEquals("Alpha", ui.events.single().serverName)
        assertEquals("Standup", ui.events.single().event.title)

        val beta = ui.servers.first { it.account.profile.id == "b" }
        assertEquals(false, beta.online)
        assertNotNull(beta.error)
        assertTrue(beta.calendars.isEmpty())
    }

    @Test
    fun refreshMarksUnauthorizedServerSignedOut() = runTest {
        val store = TestStore()
        ServerRegistry(store).upsert(signedInAccount("a", "Alpha", "https://a.example"))
        val engine = MockEngine {
            respond(
                content = """{"error":"unauthorized","message":"unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = jsonHeaders,
            )
        }
        val state = testState(store) { engine }

        state.loadPersisted()
        state.refreshAll()

        val server = state.state.value.servers.single()
        assertEquals(true, server.online)
        assertNull(server.username)
        assertTrue(server.calendars.isEmpty())
        assertTrue(state.state.value.events.isEmpty())
    }

    @Test
    fun preferencesPersistAndReload() = runTest {
        val store = TestStore()
        val first = testState(store) { neverEngine }

        first.setThemeMode(ThemeMode.Dark)
        first.setDefaultView(CalendarView.Day)

        assertEquals("Dark", store.values["kalendee.prefs.themeMode"])
        assertEquals("Day", store.values["kalendee.prefs.defaultView"])

        val second = testState(store) { neverEngine }
        second.loadPersisted()

        assertEquals(ThemeMode.Dark, second.state.value.themeMode)
        assertEquals(CalendarView.Day, second.state.value.defaultView)
    }

    @Test
    fun loadPersistedOpensCalendarWhenASessionExists() = runTest {
        val store = TestStore()
        ServerRegistry(store).upsert(signedInAccount("a", "Alpha", "https://a.example"))
        val state = testState(store) { neverEngine }

        state.loadPersisted()

        assertEquals(Route.Calendar, state.navigator.current)
        assertEquals(1, state.state.value.servers.size)
    }

    @Test
    fun loadPersistedIsIdempotent() = runTest {
        val store = TestStore()
        val state = testState(store) { neverEngine }

        state.loadPersisted()
        state.loadPersisted()

        assertTrue(state.state.value.servers.isEmpty())
        assertEquals(Route.Servers, state.navigator.current)
    }

    @Test
    fun refreshRemindersPopulatesStateAndScheduler() = runTest {
        val store = TestStore()
        ServerRegistry(store).upsert(signedInAccount("a", "Alpha", "https://a.example"))
        val engine = MockEngine { request ->
            assertEquals("/api/v1/reminders/upcoming", request.url.encodedPath)
            respond(REMINDERS_JSON, HttpStatusCode.OK, jsonHeaders)
        }
        val state = testState(store) { engine }

        state.loadPersisted()
        state.refreshReminders()

        val reminder = state.state.value.upcoming.single()
        assertEquals("a:$EVENT_ID:600", reminder.id)
        assertEquals("Alpha", reminder.serverName)
    }

    @Test
    fun defaultRangeIsTheCurrentWeek() {
        val (start, end) = defaultCalendarRange(
            now = Instant.parse("2026-10-07T12:00:00Z"),
            zone = TimeZone.UTC,
        )

        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), start)
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), end)
    }

    @Test
    fun serverNameFallsBackToHost() {
        assertEquals("cal.example.com", serverNameFromUrl("https://cal.example.com/base"))
        assertEquals("cal.example.com", serverNameFromUrl("https://cal.example.com"))
        assertEquals("not a url", serverNameFromUrl("not a url"))
    }

    @Test
    fun refreshSocialAggregatesPerServerAndToleratesFailures() = runTest {
        val store = TestStore()
        val registry = ServerRegistry(store)
        registry.upsert(signedInAccount("a", "Alpha", "https://a.example"))
        registry.upsert(signedInAccount("b", "Beta", "https://b.example"))

        val good = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/organizations" -> respond(ORGANIZATIONS_JSON, HttpStatusCode.OK, jsonHeaders)
                "/api/v1/friends" -> respond(FRIENDS_JSON, HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
        val bad = MockEngine { throw IllegalStateException("connection refused") }
        val state = testState(store) { account ->
            if (account.profile.id == "a") good else bad
        }

        state.loadPersisted()
        state.refreshSocial()

        val ui = state.state.value
        assertFalse(ui.socialLoading)
        assertEquals("Acme Inc", ui.organizationsByServer.getValue("a").single().displayName)
        assertEquals("bob", ui.friendsByServer.getValue("a").friends.single().username)
        assertEquals("carol", ui.friendsByServer.getValue("a").incoming.single().user.username)
        assertNull(ui.organizationsByServer["b"])
        assertNotNull(ui.socialErrors["b"])
    }

    @Test
    fun acceptFriendRequestReplacesFriendsForServer() = runTest {
        val store = TestStore()
        ServerRegistry(store).upsert(signedInAccount("a", "Alpha", "https://a.example"))
        val engine = MockEngine { request ->
            assertEquals("/api/v1/friends/requests/req-1/accept", request.url.encodedPath)
            assertEquals("POST", request.method.value)
            respond(FRIENDS_AFTER_ACCEPT_JSON, HttpStatusCode.OK, jsonHeaders)
        }
        val state = testState(store) { engine }
        state.loadPersisted()

        state.acceptFriendRequest("a", "req-1")

        val friends = state.state.value.friendsByServer.getValue("a")
        assertEquals(2, friends.friends.size)
        assertTrue(friends.incoming.isEmpty())
        assertTrue(state.state.value.socialErrors.isEmpty())
    }

    @Test
    fun declineFriendRequestReplacesFriendsForServer() = runTest {
        val store = TestStore()
        ServerRegistry(store).upsert(signedInAccount("a", "Alpha", "https://a.example"))
        val engine = MockEngine { request ->
            assertEquals("/api/v1/friends/requests/req-1/decline", request.url.encodedPath)
            assertEquals("POST", request.method.value)
            respond(FRIENDS_BOB_ONLY_JSON, HttpStatusCode.OK, jsonHeaders)
        }
        val state = testState(store) { engine }
        state.loadPersisted()

        state.declineFriendRequest("a", "req-1")

        val friends = state.state.value.friendsByServer.getValue("a")
        assertEquals("bob", friends.friends.single().username)
        assertTrue(friends.incoming.isEmpty())
    }

    @Test
    fun removeFriendReplacesFriendsForServer() = runTest {
        val store = TestStore()
        ServerRegistry(store).upsert(signedInAccount("a", "Alpha", "https://a.example"))
        val engine = MockEngine { request ->
            assertEquals("/api/v1/friends/user-2", request.url.encodedPath)
            assertEquals("DELETE", request.method.value)
            respond(FRIENDS_CAROL_ONLY_JSON, HttpStatusCode.OK, jsonHeaders)
        }
        val state = testState(store) { engine }
        state.loadPersisted()

        state.removeFriend("a", "user-2")

        val friends = state.state.value.friendsByServer.getValue("a")
        assertEquals("carol", friends.friends.single().username)
    }

    @Test
    fun friendMutationFailureIsRecordedPerServerAndRethrown() = runTest {
        val store = TestStore()
        val registry = ServerRegistry(store)
        registry.upsert(signedInAccount("a", "Alpha", "https://a.example"))
        registry.upsert(signedInAccount("b", "Beta", "https://b.example"))
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/organizations" -> respond("""{"organizations":[]}""", HttpStatusCode.OK, jsonHeaders)
                "/api/v1/friends" -> respond(FRIENDS_JSON, HttpStatusCode.OK, jsonHeaders)
                "/api/v1/friends/requests/req-1/accept" -> throw IllegalStateException("connection refused")
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
        val state = testState(store) { engine }
        state.loadPersisted()
        state.refreshSocial()

        assertFailsWith<IllegalStateException> { state.acceptFriendRequest("a", "req-1") }

        val ui = state.state.value
        assertEquals("carol", ui.friendsByServer.getValue("a").incoming.single().user.username)
        assertEquals("connection refused", ui.socialErrors["a"])
        assertNull(ui.socialErrors["b"])
    }

    @Test
    fun sendFriendRequestAutoAcceptRefreshesFriends() = runTest {
        val store = TestStore()
        ServerRegistry(store).upsert(signedInAccount("a", "Alpha", "https://a.example"))
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/v1/friends/requests" -> respond(
                    FRIEND_REQUEST_ACCEPTED_JSON,
                    HttpStatusCode.Created,
                    jsonHeaders,
                )

                "/api/v1/friends" -> respond(FRIENDS_AFTER_ACCEPT_JSON, HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
        val state = testState(store) { engine }
        state.loadPersisted()

        val out = state.sendFriendRequest("a", "carol")

        assertEquals("accepted", out.status)
        assertEquals("carol", out.friend?.username)
        assertEquals(2, state.state.value.friendsByServer.getValue("a").friends.size)
        assertEquals(listOf("/api/v1/friends/requests", "/api/v1/friends"), paths)
    }

    @Test
    fun sendFriendRequestPendingLeavesFriendsMapUntouched() = runTest {
        val store = TestStore()
        ServerRegistry(store).upsert(signedInAccount("a", "Alpha", "https://a.example"))
        val engine = MockEngine { request ->
            assertEquals("/api/v1/friends/requests", request.url.encodedPath)
            respond(FRIEND_REQUEST_PENDING_JSON, HttpStatusCode.Created, jsonHeaders)
        }
        val state = testState(store) { engine }
        state.loadPersisted()

        val out = state.sendFriendRequest("a", "dave")

        assertEquals("pending", out.status)
        assertNull(state.state.value.friendsByServer["a"])
    }

    @Test
    fun searchUsersPassesTrimmedQueryAndReturnsResults() = runTest {
        val store = TestStore()
        ServerRegistry(store).upsert(signedInAccount("a", "Alpha", "https://a.example"))
        var seenQuery: String? = null
        val engine = MockEngine { request ->
            assertEquals("/api/v1/users/search", request.url.encodedPath)
            seenQuery = request.url.parameters["q"]
            respond(SEARCH_RESULTS_JSON, HttpStatusCode.OK, jsonHeaders)
        }
        val state = testState(store) { engine }
        state.loadPersisted()

        val results = state.searchUsers("a", "  car  ")

        assertEquals("car", seenQuery)
        assertEquals(2, results.size)
        assertEquals("dave", results.first().username)
        assertEquals("pending_in", results.last().relationship)
    }

    @Test
    fun blankSearchReturnsEmptyWithoutARequest() = runTest {
        val store = TestStore()
        ServerRegistry(store).upsert(signedInAccount("a", "Alpha", "https://a.example"))
        val state = testState(store) { neverEngine }
        state.loadPersisted()

        assertTrue(state.searchUsers("a", "   ").isEmpty())
    }

    @Test
    fun friendRelationshipLabelsMapServerValues() {
        assertEquals("Requested", friendRelationshipLabel("pending_out"))
        assertEquals("Respond in requests", friendRelationshipLabel("pending_in"))
        assertEquals("Friends", friendRelationshipLabel("friends"))
        assertNull(friendRelationshipLabel("none"))
        assertNull(friendRelationshipLabel("something-else"))
    }

    @Test
    fun localAccentOverrideWinsOverServerAccent() = runTest {
        val store = TestStore()
        ServerRegistry(store).upsert(signedInAccount("a", "Alpha", "https://a.example"))
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/me" -> respond(userJson(accent = "secondary"), HttpStatusCode.OK, jsonHeaders)
                "/api/v1/calendars" -> respond("[]", HttpStatusCode.OK, jsonHeaders)
                "/api/v1/events" -> respond("[]", HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
        val state = testState(store) { engine }

        state.setAccent("success")
        state.setAccent("not-a-color")
        assertEquals("success", store.values["kalendee.prefs.accent"])
        assertEquals("success", state.state.value.accent)

        state.loadPersisted()
        state.refreshAll()
        assertEquals("success", state.state.value.accent)
    }

    @Test
    fun renameServerPersistsAndUpdatesTheUi() = runTest {
        val store = TestStore()
        ServerRegistry(store).upsert(signedInAccount("a", "Alpha", "https://a.example"))
        val state = testState(store) { neverEngine }

        state.loadPersisted()
        state.renameServer("a", "  Renamed  ")

        assertEquals("Renamed", state.state.value.servers.single().account.profile.name)
        assertEquals("Renamed", ServerRegistry(store).account("a")?.profile?.name)
        assertFailsWith<IllegalArgumentException> { state.renameServer("a", "   ") }
    }
}
