package dev.kolektiv.kalendee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kolektiv.kalendee.client.ClientCalendar
import dev.kolektiv.kalendee.client.ClientEvent
import dev.kolektiv.kalendee.client.KalendeeClient
import dev.kolektiv.kalendee.client.SessionStore
import dev.kolektiv.kalendee.notify.Reminder
import dev.kolektiv.kalendee.notify.ReminderScheduler
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

@Composable
fun KalendeeApp() {
    val sessions = remember { SessionStore() }
    val client = remember(sessions) { KalendeeClient(sessions) }
    val reminders = remember { ReminderScheduler() }
    var showLogin by remember { mutableStateOf(false) }
    var loadTick by remember { mutableStateOf(0) }
    var signedInName by remember { mutableStateOf<String?>(null) }
    if (showLogin) {
        LoginPane(
            sessions = sessions,
            onLocal = {
                sessions.setLocalOnly(true)
                signedInName = null
                showLogin = false
            },
            onSignedIn = {
                sessions.setLocalOnly(false)
                showLogin = false
                loadTick++
            },
        )
        return
    }
    val colors = KalendeeTheme.colors
    var view by remember { mutableStateOf(CalendarView.Week) }
    var calendars by remember { mutableStateOf(demoCalendars()) }
    var events by remember { mutableStateOf(demoEvents()) }
    var selectedId by remember { mutableStateOf(calendars.first().id) }
    var editing by remember { mutableStateOf<EventUi?>(null) }
    var creating by remember { mutableStateOf(false) }
    var draftStart by remember { mutableStateOf(0L) }
    var draftEnd by remember { mutableStateOf(0L) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf("profile") }
    var accent by remember { mutableStateOf("primary") }
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    var cursor by remember { mutableStateOf(today) }

    LaunchedEffect(loadTick) {
        val session = sessions.load()
        if (sessions.localOnly() || session == null) {
            signedInName = null
            return@LaunchedEffect
        }
        signedInName = session.displayName.ifBlank { session.username }
        val now = Clock.System.now()
        runCatching {
            val remoteCalendars = client.listCalendars()
            val remoteEvents = client.listEvents(
                fromIso = (now - 14.days).toString(),
                toIso = (now + 60.days).toString(),
            )
            Pair(remoteCalendars, remoteEvents)
        }.onSuccess { (remoteCalendars, remoteEvents) ->
            val mappedCalendars = remoteCalendars.map { it.toUi() }
            val mappedEvents = remoteEvents.map { it.toUi() }
            if (mappedCalendars.isNotEmpty()) {
                calendars = mappedCalendars
                selectedId = mappedCalendars.first().id
            }
            events = mappedEvents
            scheduleUpcomingReminders(reminders, mappedEvents)
        }
    }

    val visible = calendars.filter { !it.hidden }
    val visibleEvents = events.filter { e -> visible.any { it.id == e.calendarId } }
    val label = when (view) {
        CalendarView.Day -> "${cursor.dayOfWeek.name.lowercase().replaceFirstChar { it.titlecase() }}, ${monthName(cursor.monthNumber)} ${cursor.dayOfMonth}"
        CalendarView.Month -> "${monthName(cursor.monthNumber)} ${cursor.year}"
        CalendarView.Week -> "This week"
    }

    fun shift(dir: Int) {
        cursor = when (view) {
            CalendarView.Day -> cursor.plus(DatePeriod(days = dir))
            CalendarView.Week -> cursor.plus(DatePeriod(days = dir * 7))
            CalendarView.Month -> cursor.plus(DatePeriod(months = dir))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.base100)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    settingsOpen = false
                    creating = false
                    editing = null
                    true
                } else false
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            CalendarSidebar(
                calendars = calendars,
                selectedId = selectedId,
                onSelect = { selectedId = it },
                onToggle = { id ->
                    calendars = calendars.map { if (it.id == id) it.copy(hidden = !it.hidden) else it }
                },
                onOpenSettings = {
                    settingsSection = "profile"
                    settingsOpen = true
                },
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TopBar(
                    label = label,
                    view = view,
                    onView = { view = it },
                    onToday = { cursor = today },
                    onShift = { shift(it) },
                    onSettings = {
                        settingsSection = "appearance"
                        settingsOpen = true
                    },
                )
                when (view) {
                    CalendarView.Month -> MonthPane(
                        calendars = visible,
                        events = visibleEvents,
                        onSelect = { editing = it },
                        onDay = {
                            cursor = it
                            view = CalendarView.Day
                        },
                        onDraft = { start, end ->
                            draftStart = start
                            draftEnd = end
                            creating = true
                        },
                    )
                    else -> WeekPane(
                        view = view,
                        calendars = visible,
                        events = visibleEvents,
                        onSelect = { editing = it },
                        onDraft = { start, end ->
                            draftStart = start
                            draftEnd = end
                            creating = true
                        },
                    )
                }
            }
        }
        EventEditor(
            open = creating || editing != null,
            event = editing,
            calendars = calendars.filter { !it.hidden },
            draftStart = draftStart,
            draftEnd = draftEnd,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { saved ->
                events = if (editing != null) {
                    events.map { if (it.id == saved.id) saved else it }
                } else {
                    events + saved.copy(id = "e-${events.size + 1}")
                }
                creating = false
                editing = null
            },
            onDelete = { id ->
                events = events.filterNot { it.id == id }
                creating = false
                editing = null
            },
        )
        SettingsOverlay(
            open = settingsOpen,
            section = settingsSection,
            viewerName = signedInName ?: "Working locally",
            calendars = calendars,
            accent = accent,
            onSection = { settingsSection = it },
            onAccent = { accent = it },
            onDismiss = { settingsOpen = false },
            onSignIn = { showLogin = true },
            onSyncNow = { loadTick++ },
            signedIn = signedInName != null,
            localOnly = sessions.localOnly() || signedInName == null,
        )
    }
}

@Composable
private fun TopBar(
    label: String,
    view: CalendarView,
    onView: (CalendarView) -> Unit,
    onToday: () -> Unit,
    onShift: (Int) -> Unit,
    onSettings: () -> Unit,
) {
    val colors = KalendeeTheme.colors
    val metrics = KalendeeTheme.metrics
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(colors.base100)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .background(colors.base200, RoundedCornerShape(metrics.radiusField)),
        ) {
            Box(
                Modifier
                    .clickable { onShift(-1) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) { BasicText("‹", style = TextStyle(color = colors.baseContent, fontSize = 16.sp)) }
            Box(
                Modifier
                    .clickable(onClick = onToday)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) { BasicText("Today", style = TextStyle(color = colors.baseContent, fontSize = 13.sp, fontWeight = FontWeight.Medium)) }
            Box(
                Modifier
                    .clickable { onShift(1) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) { BasicText("›", style = TextStyle(color = colors.baseContent, fontSize = 16.sp)) }
        }
        BasicText(label, style = TextStyle(color = colors.baseContent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .background(colors.base200, RoundedCornerShape(8.dp))
                .padding(3.dp),
        ) {
            CalendarView.entries.forEach { item ->
                val selected = item == view
                Box(
                    modifier = Modifier
                        .background(if (selected) colors.base100 else colors.base200, RoundedCornerShape(6.dp))
                        .clickable { onView(item) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    BasicText(
                        item.name.lowercase().replaceFirstChar { it.titlecase() },
                        style = TextStyle(
                            color = if (selected) colors.baseContent else colors.baseContent.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .clickable(onClick = onSettings)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            BasicText("Settings", style = TextStyle(color = colors.baseContent.copy(alpha = 0.7f), fontSize = 13.sp))
        }
    }
}

private fun monthName(month: Int): String = listOf(
    "", "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)[month]

private fun ClientCalendar.toUi(): CalendarUi = CalendarUi(
    id = id,
    name = name,
    color = color,
    hidden = hidden,
)

private fun ClientEvent.toUi(): EventUi = EventUi(
    id = id,
    calendarId = calendarId,
    title = title,
    notes = notes,
    location = location,
    startEpochMs = parseIsoMillis(startIso),
    endEpochMs = parseIsoMillis(endIso),
    allDay = allDay,
)

private fun parseIsoMillis(iso: String): Long =
    runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrElse {
        runCatching { Instant.parse("${iso.take(10)}T00:00:00Z").toEpochMilliseconds() }.getOrDefault(0L)
    }

private fun scheduleUpcomingReminders(scheduler: ReminderScheduler, events: List<EventUi>) {
    val now = Clock.System.now().toEpochMilliseconds()
    val horizon = now + 48.hours.inWholeMilliseconds
    events.filter { !it.allDay && it.startEpochMs > now && it.startEpochMs <= horizon }.forEach { event ->
        scheduler.schedule(
            Reminder(
                eventId = event.id,
                title = event.title,
                fireAtEpochMs = event.startEpochMs - 15.minutes.inWholeMilliseconds,
            ),
        )
    }
}
