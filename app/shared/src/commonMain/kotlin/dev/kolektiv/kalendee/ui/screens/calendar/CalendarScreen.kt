package dev.kolektiv.kalendee.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.calendar.CalendarView
import dev.kolektiv.kalendee.calendar.ViewWindow
import dev.kolektiv.kalendee.ui.AggregatedEvent
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.ServerUi
import dev.kolektiv.kalendee.ui.components.calendar.CalendarEventItem
import dev.kolektiv.kalendee.ui.components.calendar.CalendarFilterPanel
import dev.kolektiv.kalendee.ui.components.calendar.CalendarToolbar
import dev.kolektiv.kalendee.ui.components.calendar.DefaultSlotMinutes
import dev.kolektiv.kalendee.ui.components.calendar.MonthGrid
import dev.kolektiv.kalendee.ui.components.calendar.ServerIssues
import dev.kolektiv.kalendee.ui.components.calendar.WeekCalendarGrid
import dev.kolektiv.kalendee.ui.components.calendar.daysOf
import dev.kolektiv.kalendee.ui.components.calendar.minutesToInstant
import dev.kolektiv.kalendee.ui.components.calendar.roundDownToSlot
import dev.kolektiv.kalendee.ui.components.calendar.shiftAnchor
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.components.DButton
import dev.kolektiv.kalendee.ui.design.components.DButtonShape
import dev.kolektiv.kalendee.ui.design.components.DButtonSize
import dev.kolektiv.kalendee.ui.design.components.DIcon
import dev.kolektiv.kalendee.ui.design.components.DIconButton
import dev.kolektiv.kalendee.ui.design.components.DInfoAlert
import dev.kolektiv.kalendee.ui.icons.Lucide
import dev.kolektiv.kalendee.ui.nav.Route
import dev.kolektiv.kalendee.ui.screens.events.EventDraftSeed
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Pager is anchored at [PagerCenter]; swiping left/right moves one period per page. */
private const val PagerCenter = 100
private const val PagerPages = 201

/**
 * Swipeable calendar. The pager owns horizontal navigation: one page is one day, week or
 * month depending on [CalendarView]. A settled page shifts the anchor date with core
 * [shiftAnchor] semantics, recomputes the window through [ViewWindow.of] and reloads the
 * period range via [AppState.refreshAll]; the pager then recenters so the current period
 * always sits at [PagerCenter]. There are no prev/next buttons.
 */
@Composable
fun CalendarScreen(state: AppState) {
    val ui by state.state.collectAsState()
    val scope = rememberCoroutineScope()
    val colors = LocalKalendeeColors.current
    val zone = remember { TimeZone.currentSystemDefault() }
    val appStart = remember { Clock.System.now() }
    var view by remember(ui.defaultView) { mutableStateOf(ui.defaultView) }
    var anchor by remember { mutableStateOf(Clock.System.now().toLocalDateTime(zone).date) }
    var filtersOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState(initialPage = PagerCenter) { PagerPages }

    val window = remember(view, anchor, zone) {
        ViewWindow.of(view.name, anchor.toString(), null, zone.id, appStart)
    }

    LaunchedEffect(window.range) {
        state.refreshAll(window.range.start to window.range.end)
    }

    LaunchedEffect(pagerState, view) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val delta = page - PagerCenter
            if (delta != 0) {
                anchor = shiftAnchor(view, anchor, delta)
                pagerState.scrollToPage(PagerCenter)
            }
        }
    }

    val now by produceState(initialValue = Clock.System.now()) {
        while (true) {
            delay(30_000)
            value = Clock.System.now()
        }
    }
    val today = remember(now, zone) { now.toLocalDateTime(zone).date }

    val visibleEvents = remember(ui.events, ui.servers) {
        filterVisibleEvents(ui.events, ui.servers)
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.base100)) {
        CalendarToolbar(
            label = window.label,
            view = view,
            filtersOpen = filtersOpen,
            loading = ui.loading,
            onToday = { anchor = today },
            onSelectView = { selected -> view = selected },
            onToggleFilters = { filtersOpen = !filtersOpen },
            onRefresh = {
                scope.launch { state.refreshAll(window.range.start to window.range.end) }
            },
        )
        ui.notice?.let { notice ->
            DismissibleAlert(text = notice, onDismiss = state::clearNotice)
        }
        message?.let { text ->
            DismissibleAlert(text = text, onDismiss = { message = null })
        }
        ServerIssues(ui.servers)
        if (filtersOpen) {
            CalendarFilterPanel(
                servers = ui.servers,
                onToggleVisibility = { serverId, calendarId, visible ->
                    scope.launch {
                        runCatching { state.setCalendarVisible(serverId, calendarId, visible) }
                            .onFailure { message = errorText(it) }
                    }
                },
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                val pageWindow = remember(view, anchor, zone, page) {
                    val shifted = shiftAnchor(view, anchor, page - PagerCenter)
                    ViewWindow.of(view.name, shifted.toString(), null, zone.id, appStart)
                }
                val pageItems = remember(visibleEvents, pageWindow.range) {
                    visibleEvents
                        .filter { it.event.end > pageWindow.range.start && it.event.start < pageWindow.range.end }
                        .map { it.toGridItem() }
                }
                if (view == CalendarView.Month) {
                    MonthGrid(
                        window = pageWindow,
                        events = pageItems,
                        zone = zone,
                        today = today,
                        onDaySelected = { day ->
                            view = CalendarView.Day
                            anchor = day
                        },
                        onCreateAt = { day, minutes ->
                            maybeCreate(state, day, minutes, zone) { message = it }
                        },
                        onOpenEvent = { item ->
                            state.navigator.push(Route.EventDetail(item.serverId, item.eventId))
                        },
                    )
                } else {
                    WeekCalendarGrid(
                        days = daysOf(pageWindow),
                        events = pageItems,
                        zone = zone,
                        now = now,
                        onCreateAt = { day, minutes -> maybeCreate(state, day, minutes, zone) { message = it } },
                        onOpenEvent = { item ->
                            state.navigator.push(Route.EventDetail(item.serverId, item.eventId))
                        },
                        onDaySelected = { day ->
                            view = CalendarView.Day
                            anchor = day
                        },
                    )
                }
            }
            DButton(
                onClick = {
                    val target = firstWritableCalendar(state.state.value.servers)
                    if (target == null) {
                        message = NoCalendarMessage
                    } else {
                        EventDraftSeed.clear()
                        state.navigator.push(Route.EventEditor(target.first, target.second, null))
                    }
                },
                shape = DButtonShape.Circle,
                size = DButtonSize.Md,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                DIcon(icon = Lucide.Plus, size = 20.dp, contentDescription = "New event")
            }
        }
    }
}

private const val NoCalendarMessage = "Add a calendar on a signed-in server to create events."

private fun maybeCreate(
    state: AppState,
    day: LocalDate,
    minutes: Int,
    zone: TimeZone,
    onMessage: (String) -> Unit,
) {
    val target = firstWritableCalendar(state.state.value.servers)
    if (target == null) {
        onMessage(NoCalendarMessage)
        return
    }
    val startMinutes = roundDownToSlot(minutes)
    EventDraftSeed.offer(
        start = minutesToInstant(day, startMinutes, zone),
        end = minutesToInstant(day, startMinutes + DefaultSlotMinutes, zone),
    )
    state.navigator.push(Route.EventEditor(target.first, target.second, null))
}

@Composable
private fun DismissibleAlert(text: String, onDismiss: () -> Unit) {
    val colors = LocalKalendeeColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            DInfoAlert(text = text)
        }
        DIconButton(
            icon = Lucide.X,
            contentDescription = "Dismiss",
            onClick = onDismiss,
            tint = colors.mutedContent,
            size = 32.dp,
            iconSize = 16.dp,
        )
    }
}

private fun errorText(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "Something went wrong"

private fun AggregatedEvent.toGridItem(): CalendarEventItem = CalendarEventItem(
    serverId = serverId,
    eventId = event.id.value,
    title = event.title,
    color = calendar.color,
    start = event.start,
    end = event.end,
    allDay = event.allDay,
    status = event.status,
    calendarId = event.calendarId.value,
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
