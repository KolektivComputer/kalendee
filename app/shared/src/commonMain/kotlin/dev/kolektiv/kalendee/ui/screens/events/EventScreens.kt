package dev.kolektiv.kalendee.ui.screens.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import dev.kolektiv.kalendee.ui.components.calendar.calendarClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.calendar.Event
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.client.KalendeeApiException
import dev.kolektiv.kalendee.client.TaggedEvent
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.ServerUi
import dev.kolektiv.kalendee.ui.components.calendar.DateTimePickerDialog
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.design.components.DBadge
import dev.kolektiv.kalendee.ui.design.components.DButton
import dev.kolektiv.kalendee.ui.design.components.DButtonSize
import dev.kolektiv.kalendee.ui.design.components.DButtonVariant
import dev.kolektiv.kalendee.ui.design.components.DCard
import dev.kolektiv.kalendee.ui.design.components.DErrorAlert
import dev.kolektiv.kalendee.ui.design.components.DFieldset
import dev.kolektiv.kalendee.ui.design.components.DIcon
import dev.kolektiv.kalendee.ui.design.components.DIconButton
import dev.kolektiv.kalendee.ui.design.components.DJoinGroup
import dev.kolektiv.kalendee.ui.design.components.DJoinItem
import dev.kolektiv.kalendee.ui.design.components.DListItem
import dev.kolektiv.kalendee.ui.design.components.DListSection
import dev.kolektiv.kalendee.ui.design.components.DModal
import dev.kolektiv.kalendee.ui.design.components.DSheet
import dev.kolektiv.kalendee.ui.design.components.DSpinner
import dev.kolektiv.kalendee.ui.design.components.DSwitch
import dev.kolektiv.kalendee.ui.design.components.DTextArea
import dev.kolektiv.kalendee.ui.design.components.DTextField
import dev.kolektiv.kalendee.ui.design.medium
import dev.kolektiv.kalendee.ui.design.rememberCalendarColorSpec
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.format.formatDateTime
import dev.kolektiv.kalendee.ui.format.formatEventWhen
import dev.kolektiv.kalendee.ui.format.formatLongDate
import dev.kolektiv.kalendee.ui.icons.Lucide
import dev.kolektiv.kalendee.ui.nav.Route
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

private enum class EditorPicker { Start, End, Until }

/**
 * Create ([eventId] == null, calendar selectable) or edit an event. Loads the stored
 * event plus etag in edit mode; 412 conflicts reload the etag and ask for a retry.
 *
 * In create mode the calendar row opens a bottom sheet of writable calendars grouped by
 * server (AppState exposes no organization list, so groups are per server). In edit mode
 * the calendar is fixed, matching the web editor.
 */
@Composable
fun EventEditorScreen(
    state: AppState,
    serverId: String,
    calendarId: String,
    eventId: String?,
) {
    val ui by state.state.collectAsState()
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
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
    var selectedServerId by remember(serverId, calendarId, eventId) { mutableStateOf(serverId) }
    var selectedCalendarId by remember(serverId, calendarId, eventId) { mutableStateOf(calendarId) }
    var calendarSheetOpen by remember { mutableStateOf(false) }

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

    val server = ui.servers.firstOrNull { it.account.profile.id == selectedServerId }
    val calendarUi = server?.calendars?.firstOrNull { it.calendar.id.value == selectedCalendarId }
    val calendarName = calendarUi?.calendar?.displayName ?: "Calendar $selectedCalendarId"

    EditorScaffold(
        title = if (creating) "New event" else "Edit event",
        subtitle = server?.account?.profile?.name,
        onBack = { state.navigator.pop() },
    ) {
        when {
            loading -> LoadingBlock()
            loadError != null -> {
                DErrorAlert(text = loadError.orEmpty())
                DButton(
                    onClick = { reloadKey += 1 },
                    modifier = Modifier.fillMaxWidth().padding(top = dimens.space2),
                ) {
                    DText(text = "Retry", style = DType.sm.medium())
                }
            }

            else -> {
                DTextField(
                    value = draft.title,
                    onValueChange = {
                        draft = draft.copy(title = it)
                        errors = errors.copy(title = null)
                    },
                    label = "Title",
                    error = errors.title,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = dimens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DText(
                        text = "All day",
                        style = DType.base,
                        modifier = Modifier.weight(1f),
                    )
                    DSwitch(
                        checked = draft.allDay,
                        onCheckedChange = { checked ->
                            draft = if (checked) draft.toAllDay() else draft.toTimed()
                            errors = errors.copy(whenError = null)
                        },
                    )
                }

                PickerRow(
                    label = "Starts",
                    value = formatDraftStart(draft),
                    onClick = { picker = EditorPicker.Start },
                )
                PickerRow(
                    label = "Ends",
                    value = formatDraftEnd(draft),
                    onClick = { picker = EditorPicker.End },
                )
                errors.whenError?.let { whenError ->
                    DErrorAlert(text = whenError, modifier = Modifier.padding(top = dimens.space1))
                }

                DTextField(
                    value = draft.location,
                    onValueChange = { draft = draft.copy(location = it) },
                    label = "Location",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = dimens.space3),
                )
                DTextArea(
                    value = draft.description,
                    onValueChange = { draft = draft.copy(description = it) },
                    label = "Description",
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = dimens.space3),
                )

                RepeatSection(
                    draft = draft,
                    error = errors.recurrence,
                    onRecurrence = { choice ->
                        draft = draft.copy(
                            recurrence = choice,
                            untilDate = if (choice == RecurrenceChoice.None) null else draft.untilDate,
                        )
                        errors = errors.copy(recurrence = null)
                    },
                )
                if (draft.recurrence != RecurrenceChoice.None) {
                    PickerRow(
                        label = "Until",
                        value = draft.untilDate?.let(::formatLongDate) ?: "Never",
                        onClick = { picker = EditorPicker.Until },
                    )
                    if (draft.untilDate != null) {
                        DButton(
                            onClick = { draft = draft.copy(untilDate = null) },
                            variant = DButtonVariant.Ghost,
                            size = DButtonSize.Sm,
                        ) {
                            DText(text = "Clear until", style = DType.sm.medium())
                        }
                    }
                }

                if (creating) {
                    DFieldset(title = "Calendar", modifier = Modifier.padding(top = dimens.space3)) {
                        CalendarRow(
                            serverName = server?.account?.profile?.name,
                            calendarName = calendarName,
                            color = calendarUi?.calendar?.color.orEmpty(),
                            calendarId = selectedCalendarId,
                            onClick = { calendarSheetOpen = true },
                        )
                    }
                } else {
                    DFieldset(title = "Calendar", modifier = Modifier.padding(top = dimens.space3)) {
                        CalendarRow(
                            serverName = server?.account?.profile?.name,
                            calendarName = calendarName,
                            color = calendarUi?.calendar?.color.orEmpty(),
                            calendarId = selectedCalendarId,
                            onClick = null,
                        )
                        DText(
                            text = "Events can't be moved between calendars.",
                            style = DType.xs,
                            color = colors.mutedContent,
                            modifier = Modifier.padding(top = dimens.space1),
                        )
                    }
                }

                saveError?.let { error ->
                    DErrorAlert(text = error, modifier = Modifier.padding(top = dimens.space3))
                }

                DButton(
                    onClick = {
                        val current = draft
                        val validation = current.validate()
                        errors = validation
                        if (!validation.isValid) return@DButton
                        scope.launch {
                            busy = true
                            saveError = null
                            try {
                                if (eventId == null) {
                                    state.createEvent(
                                        selectedServerId,
                                        selectedCalendarId,
                                        current.toCreateEvent(zone),
                                    )
                                } else {
                                    state.updateEvent(
                                        selectedServerId,
                                        eventId,
                                        current.toUpdateEvent(zone),
                                        etag,
                                    )
                                }
                                state.navigator.pop()
                            } catch (e: KalendeeApiException) {
                                if (e.isPreconditionFailed && eventId != null) {
                                    etag = runCatching { state.eventDetail(selectedServerId, eventId).etag }
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
                    loading = busy,
                    modifier = Modifier.fillMaxWidth().padding(top = dimens.space3),
                ) {
                    DText(text = "Save", style = DType.sm.medium())
                }

                if (!creating) {
                    DButton(
                        onClick = { confirmDelete = true },
                        variant = DButtonVariant.Ghost,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = dimens.space2),
                    ) {
                        DIcon(icon = Lucide.Trash2, tint = colors.error, size = 16.dp)
                        Spacer(modifier = Modifier.width(dimens.space2))
                        DText(text = "Delete event", color = colors.error)
                    }
                }
            }
        }
    }

    if (calendarSheetOpen) {
        CalendarPickerSheet(
            servers = ui.servers,
            selectedServerId = selectedServerId,
            selectedCalendarId = selectedCalendarId,
            onSelect = { server, calendar ->
                selectedServerId = server
                selectedCalendarId = calendar
                calendarSheetOpen = false
            },
            onDismiss = { calendarSheetOpen = false },
        )
    }

    if (confirmDelete) {
        ConfirmDeleteModal(
            text = "This removes the event from the server. This cannot be undone.",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                eventId?.let { id ->
                    scope.launch {
                        busy = true
                        saveError = null
                        try {
                            state.deleteEvent(selectedServerId, id, etag)
                            state.navigator.pop()
                        } catch (e: Exception) {
                            saveError = messageOf(e)
                        } finally {
                            busy = false
                        }
                    }
                }
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

@Composable
private fun RepeatSection(
    draft: EventDraft,
    error: String?,
    onRecurrence: (RecurrenceChoice) -> Unit,
) {
    val dimens = LocalKalendeeDimens.current
    DFieldset(title = "Repeat", error = error, modifier = Modifier.padding(top = dimens.space3)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            DJoinGroup {
                RecurrenceChoice.entries.forEachIndexed { index, choice ->
                    val selected = draft.recurrence == choice
                    DJoinItem(
                        onClick = { onRecurrence(choice) },
                        selected = selected,
                        leadingDivider = index > 0,
                    ) {
                        DText(
                            text = choice.label,
                            style = if (selected) DType.sm.semibold() else DType.sm,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    DFieldset(title = label, modifier = Modifier.padding(top = dimens.space3)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.input)
                .clip(dimens.fieldShape)
                .background(colors.base100)
                .border(dimens.border, colors.base300, dimens.fieldShape)
                .calendarClickable(onClick)
                .padding(horizontal = dimens.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DText(
                text = value,
                style = DType.sm,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DIcon(icon = Lucide.ChevronDown, tint = colors.mutedContent, size = 16.dp)
        }
    }
}

@Composable
private fun CalendarRow(
    serverName: String?,
    calendarName: String,
    color: String,
    calendarId: String,
    onClick: (() -> Unit)?,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val spec = rememberCalendarColorSpec(color, calendarId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.input)
            .clip(dimens.fieldShape)
            .background(if (onClick != null) colors.base100 else colors.base200)
            .then(
                if (onClick != null) {
                    Modifier
                        .border(dimens.border, colors.base300, dimens.fieldShape)
                        .calendarClickable(onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(spec.fill),
        )
        Spacer(modifier = Modifier.width(dimens.space2))
        DText(
            text = calendarName,
            style = DType.sm,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!serverName.isNullOrBlank()) {
            DText(
                text = serverName,
                style = DType.xs,
                color = colors.mutedContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) {
            Spacer(modifier = Modifier.width(dimens.space2))
            DIcon(icon = Lucide.ChevronDown, tint = colors.mutedContent, size = 16.dp)
        }
    }
}

/**
 * Bottom sheet of writable calendars grouped by server. The web also groups by
 * organization; AppState has no organization list, so groups are per server only.
 */
@Composable
private fun CalendarPickerSheet(
    servers: List<ServerUi>,
    selectedServerId: String,
    selectedCalendarId: String,
    onSelect: (serverId: String, calendarId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val groups = servers
        .filter { it.signedIn && it.account.profile.enabled }
        .map { server -> server to server.calendars.filter { it.calendar.permission.canWrite } }
        .filter { it.second.isNotEmpty() }

    DSheet(onDismissRequest = onDismiss) {
        DText(text = "Choose calendar", style = DType.lg.semibold())
        if (groups.isEmpty()) {
            DText(
                text = "No writable calendars found.",
                style = DType.sm,
                color = colors.mutedContent,
                modifier = Modifier.padding(vertical = dimens.space3),
            )
        }
        groups.forEach { (server, calendars) ->
            DListSection(label = server.account.profile.name) {
                calendars.forEach { ui ->
                    val selected = server.account.profile.id == selectedServerId &&
                        ui.calendar.id.value == selectedCalendarId
                    DListItem(
                        title = ui.calendar.displayName,
                        subtitle = server.account.profile.name,
                        leading = {
                            CalendarDot(
                                color = ui.calendar.color,
                                calendarId = ui.calendar.id.value,
                            )
                        },
                        trailing = {
                            if (selected) {
                                DIcon(icon = Lucide.Check, tint = colors.primary, size = 18.dp)
                            }
                        },
                        selected = selected,
                        onClick = { onSelect(server.account.profile.id, ui.calendar.id.value) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(dimens.space2))
    }
}

@Composable
private fun CalendarDot(color: String, calendarId: String) {
    val spec = rememberCalendarColorSpec(color, calendarId)
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(spec.fill),
    )
}

/** Read-only view of one event with edit/delete actions and a delete confirmation. */
@Composable
fun EventDetailScreen(
    state: AppState,
    serverId: String,
    eventId: String,
) {
    val ui by state.state.collectAsState()
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
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
    val calendarColor = event?.let { stored ->
        server?.calendars
            ?.firstOrNull { it.calendar.id == stored.calendarId }
            ?.calendar
            ?.color
            ?: ui.events.firstOrNull { it.event.id == stored.id }?.calendar?.color
    }.orEmpty()

    EditorScaffold(
        title = "Event",
        subtitle = server?.account?.profile?.name,
        onBack = { state.navigator.pop() },
    ) {
        when {
            event == null && loading -> LoadingBlock()

            event == null -> {
                DErrorAlert(text = loadError ?: "Event could not be loaded.")
                DButton(
                    onClick = { reloadKey += 1 },
                    modifier = Modifier.fillMaxWidth().padding(top = dimens.space2),
                ) {
                    DText(text = "Retry", style = DType.sm.medium())
                }
            }

            else -> {
                if (loading) {
                    LoadingBlock()
                }
                DCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DText(
                            text = event.title,
                            style = DType.xl.semibold(),
                            modifier = Modifier.weight(1f),
                        )
                        DBadge(text = statusLabel(event.status))
                    }
                    if (!calendarName.isNullOrBlank()) {
                        Row(
                            modifier = Modifier.padding(top = dimens.space2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CalendarDot(
                                color = calendarColor,
                                calendarId = event.calendarId.value,
                            )
                            Spacer(modifier = Modifier.width(dimens.space2))
                            DText(
                                text = calendarName,
                                style = DType.sm,
                                color = colors.mutedContent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                DListSection(label = "Details") {
                    DetailRow(
                        icon = Lucide.Clock,
                        label = "When",
                        value = formatEventWhen(event, zone),
                    )
                    event.location?.takeIf { it.isNotBlank() }?.let { location ->
                        DetailRow(icon = Lucide.MapPin, label = "Location", value = location)
                    }
                    event.url?.takeIf { it.isNotBlank() }?.let { url ->
                        DetailRow(icon = Lucide.Link, label = "URL", value = url)
                    }
                    event.description?.takeIf { it.isNotBlank() }?.let { description ->
                        DetailRow(icon = Lucide.List, label = "Description", value = description)
                    }
                }

                actionError?.let { error ->
                    DErrorAlert(text = error, modifier = Modifier.padding(top = dimens.space3))
                }

                DButton(
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
                    modifier = Modifier.fillMaxWidth().padding(top = dimens.space4),
                ) {
                    DIcon(icon = Lucide.Pencil, size = 16.dp)
                    Spacer(modifier = Modifier.width(dimens.space2))
                    DText(text = "Edit", style = DType.sm.medium())
                }
                DButton(
                    onClick = { confirmDelete = true },
                    variant = DButtonVariant.Ghost,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = dimens.space2),
                ) {
                    DIcon(icon = Lucide.Trash2, tint = colors.error, size = 16.dp)
                    Spacer(modifier = Modifier.width(dimens.space2))
                    DText(text = "Delete", color = colors.error)
                }
            }
        }
    }

    if (confirmDelete && event != null) {
        ConfirmDeleteModal(
            text = "This removes the event from the server. This cannot be undone.",
            onDismiss = { confirmDelete = false },
            onConfirm = {
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
            },
        )
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = dimens.space2)) {
        DIcon(icon = icon, tint = colors.mutedContent, size = 18.dp)
        Spacer(modifier = Modifier.width(dimens.space3))
        Column(modifier = Modifier.weight(1f)) {
            DText(text = label.uppercase(), style = DType.sectionLabel, color = colors.mutedContent)
            DText(text = value, style = DType.base)
        }
    }
}

@Composable
private fun ConfirmDeleteModal(
    text: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    DModal(onDismissRequest = onDismiss) {
        DText(text = "Delete event?", style = DType.lg.semibold())
        DText(
            text = text,
            style = DType.sm,
            color = colors.subtleContent,
            modifier = Modifier.padding(top = dimens.space2),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = dimens.space4),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DButton(
                onClick = onDismiss,
                variant = DButtonVariant.Ghost,
                size = DButtonSize.Sm,
            ) {
                DText(text = "Cancel", style = DType.sm.medium())
            }
            Spacer(modifier = Modifier.width(dimens.space2))
            DButton(
                onClick = onConfirm,
                variant = DButtonVariant.Error,
                size = DButtonSize.Sm,
            ) {
                DText(text = "Delete", style = DType.sm.medium())
            }
        }
    }
}

@Composable
private fun EditorScaffold(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    Box(
        modifier = Modifier.fillMaxSize().background(colors.base100),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dimens.space4, vertical = dimens.space4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DIconButton(
                    icon = Lucide.ArrowLeft,
                    contentDescription = "Back",
                    onClick = onBack,
                    tint = colors.baseContent,
                )
                Column(modifier = Modifier.weight(1f).padding(start = dimens.space1)) {
                    DText(
                        text = title,
                        style = DType.xl.semibold(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        DText(
                            text = subtitle,
                            style = DType.sm,
                            color = colors.mutedContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(dimens.space4))
            content()
            Spacer(modifier = Modifier.height(dimens.space6))
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = LocalKalendeeDimens.current.space6),
        contentAlignment = Alignment.Center,
    ) {
        DSpinner(size = 24.dp)
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

private fun statusLabel(status: EventStatus): String = when (status) {
    EventStatus.CONFIRMED -> "Confirmed"
    EventStatus.TENTATIVE -> "Tentative"
    EventStatus.CANCELLED -> "Cancelled"
}

private fun messageOf(error: Throwable): String = when (error) {
    is KalendeeApiException -> error.message
    else -> error.message?.takeIf { it.isNotBlank() }
        ?: error::class.simpleName
        ?: "Something went wrong"
}
