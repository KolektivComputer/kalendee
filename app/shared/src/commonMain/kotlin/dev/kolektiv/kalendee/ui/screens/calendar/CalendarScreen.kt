package dev.kolektiv.kalendee.ui.screens.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.calendar.CalendarView
import dev.kolektiv.kalendee.calendar.ViewWindow
import dev.kolektiv.kalendee.ui.AggregatedEvent
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.ServerUi
import dev.kolektiv.kalendee.ui.components.Notice
import dev.kolektiv.kalendee.ui.components.calendar.CalendarEventItem
import dev.kolektiv.kalendee.ui.components.calendar.CalendarFilterPanel
import dev.kolektiv.kalendee.ui.components.calendar.CalendarToolbar
import dev.kolektiv.kalendee.ui.components.calendar.DefaultSlotMinutes
import dev.kolektiv.kalendee.ui.components.calendar.ServerIssues
import dev.kolektiv.kalendee.ui.components.calendar.WeekCalendarGrid
import dev.kolektiv.kalendee.ui.components.calendar.daysOf
import dev.kolektiv.kalendee.ui.components.calendar.minutesToInstant
import dev.kolektiv.kalendee.ui.components.calendar.roundDownToSlot
import dev.kolektiv.kalendee.ui.components.messageOf
import dev.kolektiv.kalendee.ui.nav.Route
import dev.kolektiv.kalendee.ui.screens.events.EventDraftSeed
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Week/day calendar. The visible period is derived from core [ViewWindow] math for the
 * current [CalendarView] and anchor date; every period change refreshes all enabled
 * servers through [AppState.refreshAll].
 */
@Composable
fun CalendarScreen(state: AppState) {
    val ui by state.state.collectAsState()
    val scope = rememberCoroutineScope()
    val zone = remember { TimeZone.currentSystemDefault() }
    var view by remember(ui.defaultView) { mutableStateOf(ui.defaultView.forGrid()) }
    var anchor by remember { mutableStateOf(Clock.System.now().toLocalDateTime(zone).date) }
    var filtersOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val window = remember(view, anchor, zone) {
        ViewWindow.of(
            view = view.name,
            date = anchor.toString(),
            week = null,
            timeZoneId = zone.id,
            now = Clock.System.now(),
        )
    }
    val days = remember(window) { daysOf(window) }

    LaunchedEffect(window.range) {
        state.refreshAll(window.range.start to window.range.end)
    }

    val now by produceState(initialValue = Clock.System.now()) {
        while (true) {
            delay(30_000)
            value = Clock.System.now()
        }
    }

    val visibleEvents = remember(ui.events, ui.servers) {
        filterVisibleEvents(ui.events, ui.servers)
    }
    val items = remember(visibleEvents, window.range) {
        visibleEvents
            .filter { it.event.end > window.range.start && it.event.start < window.range.end }
            .map { it.toGridItem() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            CalendarToolbar(
                label = window.label,
                view = view,
                filtersOpen = filtersOpen,
                onPrevious = { anchor = window.previous },
                onNext = { anchor = window.next },
                onToday = { anchor = Clock.System.now().toLocalDateTime(zone).date },
                onViewSelected = { selected -> view = selected.forGrid() },
                onToggleFilters = { filtersOpen = !filtersOpen },
            )
            if (ui.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Notice(text = ui.notice, onDismiss = state::clearNotice)
            Notice(text = message, onDismiss = { message = null })
            ServerIssues(ui.servers)
            if (filtersOpen) {
                CalendarFilterPanel(
                    servers = ui.servers,
                    onToggleVisibility = { serverId, calendarId, visible ->
                        scope.launch {
                            runCatching { state.setCalendarVisible(serverId, calendarId, visible) }
                                .onFailure { message = messageOf(it) }
                        }
                    },
                )
            }
            WeekCalendarGrid(
                days = days,
                events = items,
                zone = zone,
                now = now,
                onCreateAt = { day, minutes ->
                    val target = firstWritableCalendar(state.state.value.servers)
                    if (target == null) {
                        message = "Add a calendar on a signed-in server to create events."
                    } else {
                        val startMinutes = roundDownToSlot(minutes)
                        EventDraftSeed.offer(
                            start = minutesToInstant(day, startMinutes, zone),
                            end = minutesToInstant(day, startMinutes + DefaultSlotMinutes, zone),
                        )
                        state.navigator.push(Route.EventEditor(target.first, target.second, null))
                    }
                },
                onOpenEvent = { item ->
                    state.navigator.push(Route.EventDetail(item.serverId, item.eventId))
                },
                onDaySelected = { day ->
                    view = CalendarView.Day
                    anchor = day
                },
                modifier = Modifier.weight(1f),
            )
        }
        FloatingActionButton(
            onClick = {
                val target = firstWritableCalendar(state.state.value.servers)
                if (target == null) {
                    message = "Add a calendar on a signed-in server to create events."
                } else {
                    EventDraftSeed.clear()
                    state.navigator.push(Route.EventEditor(target.first, target.second, null))
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Text(text = "+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun CalendarView.forGrid(): CalendarView =
    if (this == CalendarView.Month) CalendarView.Week else this

private fun AggregatedEvent.toGridItem(): CalendarEventItem = CalendarEventItem(
    serverId = serverId,
    eventId = event.id.value,
    title = event.title,
    color = calendar.color,
    start = event.start,
    end = event.end,
    allDay = event.allDay,
    status = event.status,
)

/** `(serverId, calendarId)` pairs the user currently has visible. */
internal fun visibleCalendarKeys(servers: List<ServerUi>): Set<Pair<String, String>> =
    servers.asSequence()
        .filter { it.account.profile.enabled && it.signedIn }
        .flatMap { server ->
            server.calendars
                .filter { it.visible }
                .map { server.account.profile.id to it.calendar.id.value }
        }
        .toSet()

/**
 * Client-side visibility filter: an event renders only when its server is enabled and its
 * calendar is visible. Events that arrive before their calendar list (fallback calendar)
 * still render unless the event itself is marked hidden.
 */
internal fun filterVisibleEvents(
    events: List<AggregatedEvent>,
    servers: List<ServerUi>,
): List<AggregatedEvent> {
    val enabled = servers.filter { it.account.profile.enabled }.associateBy { it.account.profile.id }
    val visible = visibleCalendarKeys(servers)
    return events.filter { aggregated ->
        val server = enabled[aggregated.serverId] ?: return@filter false
        val key = aggregated.serverId to aggregated.event.calendarId.value
        key in visible ||
            (server.calendars.none { it.calendar.id == aggregated.event.calendarId } && !aggregated.calendar.hidden)
    }
}

/** First writable calendar of the first signed-in server, falling back to any calendar. */
internal fun firstWritableCalendar(servers: List<ServerUi>): Pair<String, String>? {
    for (server in servers) {
        if (!server.account.profile.enabled || !server.signedIn) continue
        val calendar = server.calendars.firstOrNull { it.visible && it.calendar.permission.canWrite }
            ?: server.calendars.firstOrNull { it.calendar.permission.canWrite }
            ?: server.calendars.firstOrNull()
        if (calendar != null) {
            return server.account.profile.id to calendar.calendar.id.value
        }
    }
    return null
}
