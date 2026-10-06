package dev.kolektiv.kalendee.ui.screens.events

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.client.KalendeeApiException
import dev.kolektiv.kalendee.client.TaggedEvent
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.components.InlineError
import dev.kolektiv.kalendee.ui.components.PageColumn
import dev.kolektiv.kalendee.ui.components.ScreenTitle
import dev.kolektiv.kalendee.ui.components.SectionTitle
import dev.kolektiv.kalendee.ui.components.calendar.DateTimePickerDialog
import dev.kolektiv.kalendee.ui.components.messageOf
import dev.kolektiv.kalendee.ui.format.formatDateTime
import dev.kolektiv.kalendee.ui.format.formatEventWhen
import dev.kolektiv.kalendee.ui.format.formatLongDate
import dev.kolektiv.kalendee.ui.nav.Route
import dev.kolektiv.kalendee.ui.theme.accentSpec
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

private enum class EditorPicker { Start, End, Until }

/**
 * Create ([eventId] == null, calendar fixed by the route) or edit an event. Loads the
 * stored event plus etag in edit mode; 412 conflicts reload the etag and ask for a retry.
 */
@Composable
fun EventEditorScreen(
    state: AppState,
    serverId: String,
    calendarId: String,
    eventId: String?,
) {
    val ui by state.state.collectAsState()
    val zone = remember { TimeZone.currentSystemDefault() }
    val scope = rememberCoroutineScope()
    val creating = eventId == null
    val seed = remember(serverId, calendarId, eventId) {
        if (creating) EventDraftSeed.take() else null
    }
    val initialDraft = remember(serverId, calendarId, eventId) { defaultEventDraft(zone, seed) }
    var draft by remember(initialDraft) { mutableStateOf(initialDraft) }
    var errors by remember { mutableStateOf(EventDraftErrors()) }
    var etag by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(!creating) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var picker by remember { mutableStateOf<EditorPicker?>(null) }

    LaunchedEffect(serverId, eventId, reloadKey) {
        if (eventId == null) return@LaunchedEffect
        loading = true
        loadError = null
        try {
            val tagged = state.eventDetail(serverId, eventId)
            etag = tagged.etag
            draft = draftFrom(tagged.event, zone)
        } catch (e: Exception) {
            loadError = messageOf(e)
        } finally {
            loading = false
        }
    }

    val server = ui.servers.firstOrNull { it.account.profile.id == serverId }
    val calendarName = server?.calendars
        ?.firstOrNull { it.calendar.id.value == calendarId }
        ?.calendar
        ?.displayName
        ?: "Calendar $calendarId"

    PageColumn {
        ScreenTitle(
            title = if (creating) "New event" else "Edit event",
            subtitle = server?.account?.profile?.name,
            onBack = { state.navigator.pop() },
        )

        if (loading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@PageColumn
        }
        if (loadError != null) {
            InlineError(loadError)
            Button(
                onClick = { reloadKey += 1 },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Retry")
            }
            return@PageColumn
        }

        OutlinedTextField(
            value = draft.title,
            onValueChange = {
                draft = draft.copy(title = it)
                errors = errors.copy(title = null)
            },
            label = { Text("Title") },
            isError = errors.title != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        InlineError(errors.title)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "All day",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = draft.allDay,
                onCheckedChange = { checked ->
                    draft = if (checked) draft.toAllDay() else draft.toTimed()
                    errors = errors.copy(whenError = null)
                },
            )
        }

        DateTimeRow(
            label = "Starts",
            value = formatDraftStart(draft),
            onClick = { picker = EditorPicker.Start },
        )
        DateTimeRow(
            label = "Ends",
            value = formatDraftEnd(draft),
            onClick = { picker = EditorPicker.End },
        )
        InlineError(errors.whenError)

        OutlinedTextField(
            value = draft.location,
            onValueChange = { draft = draft.copy(location = it) },
            label = { Text("Location") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = draft.description,
            onValueChange = { draft = draft.copy(description = it) },
            label = { Text("Description") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        SectionTitle("Repeat")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecurrenceChoice.entries.forEach { choice ->
                TextButton(
                    onClick = {
                        draft = draft.copy(
                            recurrence = choice,
                            untilDate = if (choice == RecurrenceChoice.None) null else draft.untilDate,
                        )
                        errors = errors.copy(recurrence = null)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (draft.recurrence == choice) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                ) {
                    Text(choice.label)
                }
            }
        }
        if (draft.recurrence != RecurrenceChoice.None) {
            DateTimeRow(
                label = "Until",
                value = draft.untilDate?.let(::formatLongDate) ?: "Never",
                onClick = { picker = EditorPicker.Until },
            )
            if (draft.untilDate != null) {
                TextButton(onClick = { draft = draft.copy(untilDate = null) }) {
                    Text("Clear until")
                }
            }
        }
        InlineError(errors.recurrence)

        SectionTitle("Calendar")
        Row(verticalAlignment = Alignment.CenterVertically) {
            server?.calendars
                ?.firstOrNull { it.calendar.id.value == calendarId }
                ?.calendar
                ?.let { calendar ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(accentSpec(calendar.color).light),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            Text(text = calendarName, style = MaterialTheme.typography.bodyLarge)
        }

        InlineError(saveError)
        Button(
            onClick = {
                val current = draft
                val validation = current.validate()
                errors = validation
                if (!validation.isValid) return@Button
                scope.launch {
                    busy = true
                    saveError = null
                    try {
                        if (eventId == null) {
                            state.createEvent(serverId, calendarId, current.toCreateEvent(zone))
                        } else {
                            state.updateEvent(serverId, eventId, current.toUpdateEvent(zone), etag)
                        }
                        state.navigator.pop()
                    } catch (e: KalendeeApiException) {
                        if (e.isPreconditionFailed && eventId != null) {
                            etag = runCatching { state.eventDetail(serverId, eventId).etag }
                                .getOrNull()
                                ?: etag
                            saveError = "This event changed on the server. " +
                                "The latest version was loaded — review and save again."
                        } else {
                            saveError = messageOf(e)
                        }
                    } catch (e: Exception) {
                        saveError = messageOf(e)
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(if (busy) "Saving…" else "Save")
        }

        if (!creating) {
            OutlinedButton(
                onClick = { confirmDelete = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Delete event")
            }
        }

        Spacer(modifier = Modifier.padding(bottom = 24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete event?") },
            text = { Text("This removes the event from the server. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    eventId?.let { id ->
                        scope.launch {
                            busy = true
                            saveError = null
                            try {
                                state.deleteEvent(serverId, id, etag)
                                state.navigator.pop()
                            } catch (e: Exception) {
                                saveError = messageOf(e)
                            } finally {
                                busy = false
                            }
                        }
                    }
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    when (picker) {
        EditorPicker.Start -> DateTimePickerDialog(
            initial = draft.start,
            title = if (draft.allDay) "Start date" else "Starts",
            dateOnly = draft.allDay,
            onDismiss = { picker = null },
            onConfirm = { picked ->
                draft = if (draft.allDay) {
                    val startDate = picked.date
                    val endDate = if (draft.end.date <= startDate) {
                        startDate.plus(1, DateTimeUnit.DAY)
                    } else {
                        draft.end.date
                    }
                    draft.copy(start = startDate.atTime(0, 0), end = endDate.atTime(0, 0))
                } else if (picked >= draft.end) {
                    draft.copy(start = picked, end = shiftLocalMinutes(picked, 60, zone))
                } else {
                    draft.copy(start = picked)
                }
                errors = errors.copy(whenError = null)
                picker = null
            },
        )
        EditorPicker.End -> DateTimePickerDialog(
            initial = if (draft.allDay) {
                draft.end.date.minus(1, DateTimeUnit.DAY).atTime(0, 0)
            } else {
                draft.end
            },
            title = if (draft.allDay) "End date" else "Ends",
            dateOnly = draft.allDay,
            onDismiss = { picker = null },
            onConfirm = { picked ->
                draft = if (draft.allDay) {
                    draft.copy(end = picked.date.plus(1, DateTimeUnit.DAY).atTime(0, 0))
                } else {
                    draft.copy(end = picked)
                }
                errors = errors.copy(whenError = null)
                picker = null
            },
        )
        EditorPicker.Until -> DateTimePickerDialog(
            initial = (draft.untilDate ?: draft.start.date).atTime(0, 0),
            title = "Repeat until",
            dateOnly = true,
            onDismiss = { picker = null },
            onConfirm = { picked ->
                draft = draft.copy(untilDate = picked.date)
                errors = errors.copy(recurrence = null)
                picker = null
            },
        )
        null -> Unit
    }
}

private fun formatDraftStart(draft: EventDraft): String =
    if (draft.allDay) formatLongDate(draft.start.date) else formatDateTime(draft.start)

private fun formatDraftEnd(draft: EventDraft): String =
    if (draft.allDay) {
        formatLongDate(draft.end.date.minus(1, DateTimeUnit.DAY))
    } else {
        formatDateTime(draft.end)
    }

@Composable
private fun DateTimeRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onClick) {
            Text(value)
        }
    }
}

/** Read-only view of one event with edit/delete actions and a delete confirmation. */
@Composable
fun EventDetailScreen(
    state: AppState,
    serverId: String,
    eventId: String,
) {
    val ui by state.state.collectAsState()
    val zone = remember { TimeZone.currentSystemDefault() }
    val scope = rememberCoroutineScope()
    var tagged by remember(serverId, eventId) { mutableStateOf<TaggedEvent?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(serverId, eventId, reloadKey) {
        loading = true
        loadError = null
        try {
            tagged = state.eventDetail(serverId, eventId)
        } catch (e: Exception) {
            loadError = messageOf(e)
        } finally {
            loading = false
        }
    }

    val server = ui.servers.firstOrNull { it.account.profile.id == serverId }
    val event = tagged?.event
    val calendarName = event?.let { stored ->
        server?.calendars
            ?.firstOrNull { it.calendar.id == stored.calendarId }
            ?.calendar
            ?.displayName
            ?: ui.events.firstOrNull { it.event.id == stored.id }?.calendar?.displayName
    }

    PageColumn {
        ScreenTitle(
            title = "Event",
            subtitle = server?.account?.profile?.name,
            onBack = { state.navigator.pop() },
        )
        when {
            event == null && loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            event == null -> {
                InlineError(loadError ?: "Event could not be loaded.")
                Button(
                    onClick = { reloadKey += 1 },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Retry")
                }
            }

            else -> {
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                EventDetailBody(event = event, calendarName = calendarName, zone = zone)
                InlineError(actionError)
                Button(
                    onClick = {
                        state.navigator.push(
                            Route.EventEditor(
                                serverId = serverId,
                                calendarId = event.calendarId.value,
                                eventId = event.id.value,
                            ),
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Edit")
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("Delete")
                }
            }
        }
    }

    if (confirmDelete && event != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete event?") },
            text = { Text("This removes the event from the server. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        busy = true
                        actionError = null
                        try {
                            state.deleteEvent(serverId, event.id.value, tagged?.etag)
                            state.navigator.pop()
                        } catch (e: Exception) {
                            actionError = messageOf(e)
                        } finally {
                            busy = false
                        }
                    }
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EventDetailBody(event: Event, calendarName: String?, zone: TimeZone) {
    Text(text = event.title, style = MaterialTheme.typography.headlineSmall)
    if (!calendarName.isNullOrBlank()) {
        Text(
            text = calendarName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    SectionTitle("When")
    Text(text = formatEventWhen(event, zone), style = MaterialTheme.typography.bodyLarge)

    event.location?.takeIf { it.isNotBlank() }?.let { location ->
        SectionTitle("Location")
        Text(text = location, style = MaterialTheme.typography.bodyLarge)
    }
    event.description?.takeIf { it.isNotBlank() }?.let { description ->
        SectionTitle("Description")
        Text(text = description, style = MaterialTheme.typography.bodyLarge)
    }
    event.url?.takeIf { it.isNotBlank() }?.let { url ->
        SectionTitle("URL")
        Text(text = url, style = MaterialTheme.typography.bodyLarge)
    }

    SectionTitle("Status")
    Text(text = statusLabel(event.status), style = MaterialTheme.typography.bodyLarge)
}

private fun statusLabel(status: EventStatus): String = when (status) {
    EventStatus.CONFIRMED -> "Confirmed"
    EventStatus.TENTATIVE -> "Tentative"
    EventStatus.CANCELLED -> "Cancelled"
}
