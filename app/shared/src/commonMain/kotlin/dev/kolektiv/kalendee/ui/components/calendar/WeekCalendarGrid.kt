package dev.kolektiv.kalendee.ui.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.ui.format.formatClock
import dev.kolektiv.kalendee.ui.format.formatTime
import dev.kolektiv.kalendee.ui.format.shortWeekday
import dev.kolektiv.kalendee.ui.theme.accentSpec
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val HourHeight = 56.dp
private val GutterWidth = 52.dp
private val MinBlockHeight = 20.dp
private val BlockGap = 2.dp
private const val HoursPerDay = 24
private const val StartHour = 7

/**
 * Week/day time grid: hour gutter, day columns with timed blocks, an all-day chip row and
 * a current-time line. All layout math lives in [CalendarLayout] so it stays testable.
 *
 * @param onCreateAt tapped empty slot (day plus minutes after midnight).
 * @param onOpenEvent tapped event block.
 * @param onDaySelected tapped day header (opens Day view).
 */
@Composable
fun WeekCalendarGrid(
    days: List<LocalDate>,
    events: List<CalendarEventItem>,
    zone: TimeZone,
    now: Instant,
    onCreateAt: (LocalDate, Int) -> Unit,
    onOpenEvent: (CalendarEventItem) -> Unit,
    onDaySelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val placedByDay = remember(days, events, zone) {
        days.associateWith { day -> placeDayEvents(day, events, zone) }
    }
    val allDayByDay = remember(days, events, zone) {
        days.associateWith { day ->
            events.filter { it.allDay && allDayOverlapsDay(it, day, zone) }
        }
    }
    val hasAllDay = allDayByDay.values.any { it.isNotEmpty() }
    val nowDay = remember(now, zone) { now.toLocalDateTime(zone).date }
    val nowMinutes = remember(now, zone) { minutesOfDay(now, zone) }
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val density = LocalDensity.current
    val initialScroll = remember(density) {
        with(density) { (HourHeight * StartHour).roundToPx() }
    }
    val scrollState = rememberScrollState(initial = initialScroll)

    Column(modifier = modifier) {
        DayHeaderRow(days = days, today = nowDay, onDaySelected = onDaySelected)
        if (hasAllDay) {
            AllDayRow(days = days, allDayByDay = allDayByDay, onOpenEvent = onOpenEvent)
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HourHeight * HoursPerDay),
                ) {
                    HourGutter()
                    days.forEach { day ->
                        DayColumn(
                            day = day,
                            isToday = day == nowDay,
                            nowMinutes = nowMinutes,
                            placed = placedByDay[day].orEmpty(),
                            zone = zone,
                            dividerColor = dividerColor,
                            onCreateAt = onCreateAt,
                            onOpenEvent = onOpenEvent,
                        )
                    }
                }
            }
            if (events.isEmpty()) {
                EmptyOverlay()
            }
        }
    }
}

@Composable
private fun DayHeaderRow(
    days: List<LocalDate>,
    today: LocalDate,
    onDaySelected: (LocalDate) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.width(GutterWidth))
        days.forEach { day ->
            val isToday = day == today
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onDaySelected(day) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = shortWeekday(day).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = day.day.toString(),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isToday) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AllDayRow(
    days: List<LocalDate>,
    allDayByDay: Map<LocalDate, List<CalendarEventItem>>,
    onOpenEvent: (CalendarEventItem) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.width(GutterWidth), contentAlignment = Alignment.TopEnd) {
            Text(
                text = "all-day",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp, top = 4.dp),
            )
        }
        days.forEach { day ->
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 1.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                allDayByDay[day].orEmpty().forEach { event ->
                    AllDayChip(event = event, onClick = { onOpenEvent(event) })
                }
            }
        }
    }
}

@Composable
private fun AllDayChip(event: CalendarEventItem, onClick: () -> Unit) {
    val (container, content) = eventPalette(event.color)
    Surface(
        color = container.copy(alpha = if (event.status == EventStatus.CANCELLED) 0.5f else 1f),
        contentColor = content,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Text(
            text = event.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (event.status == EventStatus.CANCELLED) {
                TextDecoration.LineThrough
            } else {
                null
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun HourGutter() {
    Column(modifier = Modifier.width(GutterWidth).fillMaxHeight()) {
        repeat(HoursPerDay) { hour ->
            Box(modifier = Modifier.fillMaxWidth().height(HourHeight)) {
                Text(
                    text = formatClock(hour * 60),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RowScope.DayColumn(
    day: LocalDate,
    isToday: Boolean,
    nowMinutes: Int,
    placed: List<PlacedCalendarEvent>,
    zone: TimeZone,
    dividerColor: Color,
    onCreateAt: (LocalDate, Int) -> Unit,
    onOpenEvent: (CalendarEventItem) -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .drawBehind {
                val hourPx = HourHeight.toPx()
                for (hour in 1 until HoursPerDay) {
                    drawLine(
                        color = dividerColor,
                        start = Offset(0f, hour * hourPx),
                        end = Offset(size.width, hour * hourPx),
                        strokeWidth = 1f,
                    )
                }
                drawLine(
                    color = dividerColor,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 1f,
                )
            }
            .pointerInput(day) {
                detectTapGestures { offset ->
                    val yDp = with(density) { offset.y.toDp().value }
                    val minutes = ((yDp / HourHeight.value) * 60f).roundToInt()
                        .coerceIn(0, MinutesPerDay - 1)
                    onCreateAt(day, minutes)
                }
            },
    ) {
        if (isToday) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = HourHeight * (nowMinutes / 60f))
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.error),
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            placed.forEach { placedEvent ->
                EventBlock(
                    placedEvent = placedEvent,
                    zone = zone,
                    columnWidth = maxWidth / placedEvent.column.columnCount.coerceAtLeast(1).toFloat(),
                    onClick = { onOpenEvent(placedEvent.event) },
                )
            }
        }
    }
}

@Composable
private fun EventBlock(
    placedEvent: PlacedCalendarEvent,
    zone: TimeZone,
    columnWidth: Dp,
    onClick: () -> Unit,
) {
    val event = placedEvent.event
    val (container, content) = eventPalette(event.color)
    val top = HourHeight * (placedEvent.placement.startMinutes / 60f)
    val height = maxOf(HourHeight * (placedEvent.placement.durationMinutes / 60f), MinBlockHeight)
    val cancelled = event.status == EventStatus.CANCELLED
    Box(
        modifier = Modifier
            .offset(
                x = columnWidth * placedEvent.column.column.toFloat(),
                y = top,
            )
            .width(maxOf(columnWidth - BlockGap, 12.dp))
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(container.copy(alpha = if (cancelled) 0.55f else 1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Column {
            Text(
                text = event.title,
                style = MaterialTheme.typography.labelSmall,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            )
            if (placedEvent.placement.durationMinutes >= 40) {
                Text(
                    text = formatTime(event.start, zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.8f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EmptyOverlay() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 2.dp,
            modifier = Modifier.padding(top = 48.dp),
        ) {
            Text(
                text = "No events in this period",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Container/content colors for a calendar color string, safe in light and dark schemes. */
@Composable
private fun eventPalette(color: String): Pair<Color, Color> {
    val spec = accentSpec(color)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) {
        spec.darkContainer to spec.onDarkContainer
    } else {
        spec.lightContainer to spec.onLightContainer
    }
}
