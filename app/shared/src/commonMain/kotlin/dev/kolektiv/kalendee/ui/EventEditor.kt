package dev.kolektiv.kalendee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun EventEditor(
    open: Boolean,
    event: EventUi?,
    calendars: List<CalendarUi>,
    draftStart: Long,
    draftEnd: Long,
    onDismiss: () -> Unit,
    onSave: (EventUi) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (!open) return
    val colors = KalendeeTheme.colors
    val metrics = KalendeeTheme.metrics
    val tz = TimeZone.currentSystemDefault()
    var title by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var calendarId by remember { mutableStateOf(calendars.firstOrNull()?.id.orEmpty()) }
    var allDay by remember { mutableStateOf(false) }

    LaunchedEffect(event?.id, draftStart, draftEnd) {
        title = event?.title.orEmpty()
        location = event?.location.orEmpty()
        notes = event?.notes.orEmpty()
        calendarId = event?.calendarId ?: calendars.firstOrNull()?.id.orEmpty()
        allDay = event?.allDay == true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.base100.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.base200)
                .border(2.dp, colors.base300, RoundedCornerShape(18.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 18.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    if (event == null) "New event" else "Edit event",
                    style = TextStyle(color = colors.baseContent, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("×", style = TextStyle(color = colors.baseContent, fontSize = 20.sp))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.base300))
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Field(value = title, onValueChange = { title = it }, placeholder = "Add a title")
                BasicText(
                    formatRange(event?.startEpochMs ?: draftStart, event?.endEpochMs ?: draftEnd, tz),
                    style = TextStyle(color = colors.baseContent.copy(alpha = 0.55f), fontSize = 12.sp),
                )
                Row(
                    modifier = Modifier.clickable { allDay = !allDay },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .border(1.dp, colors.base300, RoundedCornerShape(3.dp))
                            .background(if (allDay) colors.primary else colors.base100),
                    )
                    BasicText("All day", style = TextStyle(color = colors.baseContent.copy(alpha = 0.7f), fontSize = 13.sp))
                }
                Field(value = location, onValueChange = { location = it }, placeholder = "Location")
                calendars.forEach { calendar ->
                    val selected = calendar.id == calendarId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(metrics.radiusField))
                            .background(if (selected) colors.base100 else colors.base200)
                            .clickable { calendarId = calendar.id }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape).background(colors.named(calendar.color)),
                        )
                        BasicText(calendar.name, style = TextStyle(color = colors.baseContent, fontSize = 13.sp))
                    }
                }
                Field(value = notes, onValueChange = { notes = it }, placeholder = "Notes", singleLine = false)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (event != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(metrics.radiusField))
                            .clickable { onDelete(event.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        BasicText("Delete", style = TextStyle(color = colors.error, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                    }
                    Spacer(Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                    GhostButton("Cancel", onClick = onDismiss)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(metrics.radiusField))
                        .background(colors.primary)
                        .clickable {
                            onSave(
                                EventUi(
                                    id = event?.id ?: "draft",
                                    calendarId = calendarId,
                                    title = title.ifBlank { "Untitled" },
                                    notes = notes,
                                    location = location,
                                    startEpochMs = event?.startEpochMs ?: draftStart,
                                    endEpochMs = event?.endEpochMs ?: draftEnd,
                                    allDay = allDay,
                                ),
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    BasicText("Save", style = TextStyle(color = colors.primaryContent, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
) {
    val colors = KalendeeTheme.colors
    val metrics = KalendeeTheme.metrics
    val shape = RoundedCornerShape(metrics.radiusField)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = colors.baseContent, fontSize = 14.sp),
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (singleLine) 40.dp else 88.dp)
                    .background(colors.base100, shape)
                    .border(1.dp, colors.base300, shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) {
                    BasicText(placeholder, style = TextStyle(color = colors.baseContent.copy(alpha = 0.4f), fontSize = 14.sp))
                }
                inner()
            }
        },
    )
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit) {
    val colors = KalendeeTheme.colors
    val metrics = KalendeeTheme.metrics
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(metrics.radiusField))
            .background(colors.base200)
            .border(1.dp, colors.base300, RoundedCornerShape(metrics.radiusField))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        BasicText(label, style = TextStyle(color = colors.baseContent, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

private fun formatRange(startMs: Long, endMs: Long, tz: TimeZone): String {
    val start = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(tz)
    val end = Instant.fromEpochMilliseconds(endMs).toLocalDateTime(tz)
    fun hour(h: Int, m: Int): String {
        val suffix = if (h < 12) "AM" else "PM"
        val twelve = ((h + 11) % 12) + 1
        return if (m == 0) "$twelve $suffix" else "$twelve:${m.toString().padStart(2, '0')} $suffix"
    }
    return "${hour(start.hour, start.minute)} – ${hour(end.hour, end.minute)}"
}
