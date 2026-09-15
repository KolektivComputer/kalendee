package dev.kolektiv.kalendee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.max
import kotlin.math.roundToInt

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val MINUTE_MS = 60_000L
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private data class Packed(
    val event: EventUi,
    val startMin: Int,
    val endMin: Int,
    val col: Int,
    val cols: Int,
)

private fun pack(events: List<EventUi>, dayStart: Long): List<Packed> {
    val items = events.map { event ->
        Packed(
            event = event,
            startMin = max(0, ((event.startEpochMs - dayStart) / MINUTE_MS).toInt()),
            endMin = minOf(24 * 60, ((event.endEpochMs - dayStart) / MINUTE_MS).toInt()),
            col = 0,
            cols = 1,
        )
    }.sortedWith(compareBy<Packed> { it.startMin }.thenByDescending { it.endMin - it.startMin })
        .toMutableList()
    val colEnds = mutableListOf<Int>()
    for (i in items.indices) {
        var col = colEnds.indexOfFirst { it <= items[i].startMin }
        if (col == -1) {
            col = colEnds.size
            colEnds.add(items[i].endMin)
        } else {
            colEnds[col] = items[i].endMin
        }
        items[i] = items[i].copy(col = col)
    }
    val cols = max(1, colEnds.size)
    return items.map { it.copy(cols = cols) }
}

@Composable
fun WeekPane(
    view: CalendarView,
    calendars: List<CalendarUi>,
    events: List<EventUi>,
    onSelect: (EventUi) -> Unit,
    onDraft: (Long, Long) -> Unit,
) {
    val colors = KalendeeTheme.colors
    val metrics = KalendeeTheme.metrics
    val density = LocalDensity.current
    val hourPx = with(density) { metrics.hourHeight.toPx() }
    val columns = if (view == CalendarView.Day) 1 else 7
    val tz = TimeZone.currentSystemDefault()
    val now = Clock.System.now()
    val today = now.toLocalDateTime(tz).date
    val monday = remember(today) {
        today.plus(DatePeriod(days = 1 - today.dayOfWeek.isoDayNumber))
    }
    val days = (0 until columns).map { monday.plus(DatePeriod(days = it)) }
    val allDay = events.filter { it.allDay }

    Column(Modifier.fillMaxSize().background(colors.base100)) {
        Row(Modifier.fillMaxWidth().height(64.dp).border(1.dp, colors.base300)) {
            Box(Modifier.width(metrics.gutter))
            days.forEach { date ->
                val isToday = date == today
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().border(1.dp, colors.base300),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(
                        WEEKDAYS[date.dayOfWeek.isoDayNumber - 1],
                        modifier = Modifier.padding(top = 8.dp),
                        style = TextStyle(
                            color = colors.baseContent.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clip(CircleShape)
                            .background(if (isToday) colors.primary else colors.base100)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        BasicText(
                            date.dayOfMonth.toString(),
                            style = TextStyle(
                                color = if (isToday) colors.primaryContent else colors.baseContent,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
        if (allDay.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().border(1.dp, colors.base300)) {
                Box(Modifier.width(metrics.gutter).padding(8.dp)) {
                    BasicText("all-day", style = TextStyle(color = colors.baseContent.copy(alpha = 0.4f), fontSize = 10.sp))
                }
                days.forEach { date ->
                    Column(Modifier.weight(1f).border(1.dp, colors.base300).padding(4.dp)) {
                        allDay.filter { ev ->
                            Instant.fromEpochMilliseconds(ev.startEpochMs).toLocalDateTime(tz).date == date
                        }.forEach { event ->
                            val color = colors.named(calendars.firstOrNull { it.id == event.calendarId }?.color ?: "primary")
                            Box(
                                Modifier
                                    .padding(bottom = 2.dp)
                                    .clip(RoundedCornerShape(metrics.radiusField))
                                    .background(color.copy(alpha = 0.28f))
                                    .clickable { onSelect(event) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                BasicText(
                                    event.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = colors.baseContent, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                )
                            }
                        }
                    }
                }
            }
        }
        val scroll = rememberScrollState()
        Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Column(Modifier.width(metrics.gutter)) {
                repeat(24) { hour ->
                    Box(Modifier.height(metrics.hourHeight).fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                        if (hour != 0) {
                            BasicText(
                                formatHour(hour),
                                modifier = Modifier.padding(end = 8.dp),
                                style = TextStyle(color = colors.baseContent.copy(alpha = 0.4f), fontSize = 11.sp),
                            )
                        }
                    }
                }
            }
            days.forEach { date ->
                val dayStart = date.atStartOfDayIn(tz).toEpochMilliseconds()
                var colWidth by remember { mutableStateOf(1f) }
                val packed = remember(events, dayStart) {
                    pack(
                        events.filter { ev ->
                            !ev.allDay && ev.startEpochMs < dayStart + DAY_MS && ev.endEpochMs > dayStart
                        },
                        dayStart,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(metrics.hourHeight * 24)
                        .border(1.dp, colors.base300)
                        .background(colors.base200)
                        .onSizeChanged { colWidth = it.width.toFloat() }
                        .pointerInput(dayStart) {
                            detectTapGestures { offset ->
                                val startMin = ((offset.y / hourPx) * 60).roundToInt().coerceIn(0, 24 * 60 - 15)
                                val snapped = (startMin / 15) * 15
                                onDraft(dayStart + snapped * MINUTE_MS, dayStart + (snapped + 60) * MINUTE_MS)
                            }
                        },
                ) {
                    repeat(24) { hour ->
                        Box(
                            Modifier
                                .offset { IntOffset(0, (hour * hourPx).roundToInt()) }
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colors.base300),
                        )
                    }
                    packed.forEach { block ->
                        val cal = calendars.firstOrNull { it.id == block.event.calendarId }
                        val color = colors.named(cal?.color ?: "primary")
                        val top = (block.startMin / 60f) * hourPx
                        val heightPx = max(18f, ((block.endMin - block.startMin) / 60f) * hourPx)
                        val widthFrac = 1f / block.cols
                        val leftPx = 4f + block.col * (colWidth * widthFrac)
                        val widthPx = (colWidth * widthFrac) - 8f
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(leftPx.roundToInt(), top.roundToInt()) }
                                .width(with(density) { widthPx.toDp().coerceAtLeast(8.dp) })
                                .height(with(density) { heightPx.toDp() })
                                .clip(RoundedCornerShape(4.dp))
                                .background(color.copy(alpha = 0.22f))
                                .clickable { onSelect(block.event) },
                        ) {
                            Box(Modifier.fillMaxHeight().width(5.dp).background(color))
                            Column(Modifier.padding(start = 10.dp, top = 4.dp, end = 4.dp)) {
                                BasicText(
                                    block.event.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = colors.baseContent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                                )
                                BasicText(
                                    formatHourMinutes(block.startMin),
                                    style = TextStyle(color = colors.baseContent.copy(alpha = 0.6f), fontSize = 10.sp),
                                )
                            }
                        }
                    }
                    if (date == today) {
                        val nowLdt = now.toLocalDateTime(tz)
                        val nowMin = nowLdt.hour * 60 + nowLdt.minute
                        val y = (nowMin / 60f) * hourPx
                        Box(
                            Modifier
                                .offset { IntOffset(0, y.roundToInt()) }
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colors.error),
                        )
                        Box(
                            Modifier
                                .offset { IntOffset(-5, y.roundToInt() - 5) }
                                .clip(CircleShape)
                                .background(colors.error)
                                .width(10.dp)
                                .height(10.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun formatHour(hour: Int): String {
    val h = ((hour + 11) % 12) + 1
    val suffix = if (hour < 12) "AM" else "PM"
    return "$h $suffix"
}

private fun formatHourMinutes(minutes: Int): String {
    val hour = minutes / 60
    val min = minutes % 60
    val h = ((hour + 11) % 12) + 1
    val suffix = if (hour < 12) "AM" else "PM"
    return if (min == 0) "$h $suffix" else "$h:${min.toString().padStart(2, '0')} $suffix"
}
