package dev.kolektiv.kalendee.ui.components.calendar

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.design.medium
import dev.kolektiv.kalendee.ui.design.rememberCalendarColorSpec
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.format.formatClock
import dev.kolektiv.kalendee.ui.format.formatTime
import dev.kolektiv.kalendee.ui.format.shortWeekday
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
    val colors = LocalKalendeeColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.width(GutterWidth))
        days.forEach { day ->
            val isToday = day == today
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .calendarClickable { onDaySelected(day) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DText(
                    text = shortWeekday(day).uppercase(),
                    style = DType.xs.medium(),
                    color = if (isToday) colors.primary else colors.mutedContent,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isToday) colors.primary else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    DText(
                        text = day.day.toString(),
                        style = DType.sm.semibold(),
                        color = if (isToday) colors.primaryContent else colors.baseContent,
                        textAlign = TextAlign.Center,
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
    val colors = LocalKalendeeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.width(GutterWidth), contentAlignment = Alignment.TopEnd) {
            DText(
                text = "all-day",
                style = DType.xs,
                color = colors.mutedContent,
                maxLines = 1,
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
    val dimens = LocalKalendeeDimens.current
    val spec = rememberCalendarColorSpec(event.color, event.calendarId)
    val cancelled = event.status == EventStatus.CANCELLED
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(dimens.fieldShape)
            .background(spec.fill.copy(alpha = if (cancelled) 0.55f else 1f))
            .calendarClickable(onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        DText(
            text = event.title,
            style = DType.xs.copy(
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            ),
            color = spec.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HourGutter() {
    val colors = LocalKalendeeColors.current
    Column(modifier = Modifier.width(GutterWidth).fillMaxHeight()) {
        repeat(HoursPerDay) { hour ->
            Box(modifier = Modifier.fillMaxWidth().height(HourHeight)) {
                DText(
                    text = formatClock(hour * 60),
                    style = DType.xs,
                    color = colors.mutedContent,
                    maxLines = 1,
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
    onCreateAt: (LocalDate, Int) -> Unit,
    onOpenEvent: (CalendarEventItem) -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(if (isToday) colors.primary.copy(alpha = 0.1f) else Color.Transparent)
            .drawBehind {
                val hourPx = HourHeight.toPx()
                for (hour in 1 until HoursPerDay) {
                    drawLine(
                        color = colors.base300,
                        start = Offset(0f, hour * hourPx),
                        end = Offset(size.width, hour * hourPx),
                        strokeWidth = 1f,
                    )
                }
                drawLine(
                    color = colors.base300,
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
            val lineOffset = HourHeight * (nowMinutes / 60f)
            Box(
                modifier = Modifier
                    .offset(y = lineOffset)
                    .size(8.dp)
                    .offset(x = (-4).dp)
                    .clip(CircleShape)
                    .background(colors.error),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = lineOffset)
                    .height(2.dp)
                    .background(colors.error),
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
    val dimens = LocalKalendeeDimens.current
    val event = placedEvent.event
    val spec = rememberCalendarColorSpec(event.color, event.calendarId)
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
            .clip(dimens.fieldShape)
            .background(spec.fill.copy(alpha = if (cancelled) 0.55f else 1f))
            .calendarClickable(onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Column {
            DText(
                text = event.title,
                style = DType.xs.copy(
                    textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                ),
                color = spec.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (placedEvent.placement.durationMinutes >= 40) {
                DText(
                    text = formatTime(event.start, zone),
                    style = DType.xs,
                    color = spec.content.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyOverlay() {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier
                .padding(top = 48.dp)
                .clip(dimens.boxShape)
                .background(colors.base200)
                .padding(horizontal = dimens.space4, vertical = dimens.space2),
        ) {
            DText(
                text = "No events in this period",
                style = DType.sm,
                color = colors.mutedContent,
            )
        }
    }
}
