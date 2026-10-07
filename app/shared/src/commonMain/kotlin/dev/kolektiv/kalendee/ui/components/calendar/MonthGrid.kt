package dev.kolektiv.kalendee.ui.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.calendar.EventStatus
import dev.kolektiv.kalendee.calendar.ViewWindow
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.design.rememberCalendarColorSpec
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.format.shortWeekday
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

private val MonthRowMinHeight = 104.dp
private val DayHeaderHeight = 40.dp
private val ChipHeight = 18.dp
private const val OutOfMonthAlpha = 0.4f

/**
 * Tapped month cell prefill: a 09:00 timed draft. The web `MonthGrid` creates all-day
 * events, but the mobile hand-off ([EventDraftSeed][dev.kolektiv.kalendee.ui.screens.events.EventDraftSeed])
 * only carries timed instants, so month cells seed 09:00–09:30 (rounded to the slot).
 */
const val MonthDefaultCreateMinutes: Int = 9 * 60

/**
 * Phone-first month grid mirroring the web `MonthGrid.svelte`: 7 weekday columns and
 * the whole-week rows of the core [ViewWindow]. Day cells show up to two colored chips
 * plus a `+N` overflow line; tapping the day number switches to Day view, tapping the
 * cell body opens the event editor prefilled for that date, tapping a chip opens the
 * event. Cells are never horizontally scrolled; chips truncate.
 */
@Composable
fun MonthGrid(
    window: ViewWindow,
    events: List<CalendarEventItem>,
    zone: TimeZone,
    today: LocalDate,
    onDaySelected: (LocalDate) -> Unit,
    onCreateAt: (LocalDate, Int) -> Unit,
    onOpenEvent: (CalendarEventItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells = remember(window, events, zone, today) { monthCells(window, events, zone, today) }
    val weeks = remember(cells) { cells.chunked(7) }
    val scrollState = rememberScrollState()

    Column(modifier = modifier) {
        WeekdayHeader(weekStart = cells.firstOrNull()?.date ?: window.gridStart)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val rowHeight = maxOf(
                MonthRowMinHeight,
                maxHeight / weeks.size.coerceAtLeast(1),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
            ) {
                weeks.forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
                        week.forEach { cell ->
                            MonthDayCellView(
                                cell = cell,
                                zone = zone,
                                onDaySelected = onDaySelected,
                                onCreateAt = onCreateAt,
                                onOpenEvent = onOpenEvent,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekdayHeader(weekStart: LocalDate) {
    val colors = LocalKalendeeColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        List(7) { index ->
            DText(
                text = shortWeekday(weekStart.plus(index, DateTimeUnit.DAY)).uppercase(),
                style = DType.xs,
                color = colors.mutedContent,
                maxLines = 1,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RowScope.MonthDayCellView(
    cell: MonthDayCell,
    zone: TimeZone,
    onDaySelected: (LocalDate) -> Unit,
    onCreateAt: (LocalDate, Int) -> Unit,
    onOpenEvent: (CalendarEventItem) -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val alpha = if (cell.inMonth) 1f else OutOfMonthAlpha
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .drawBehind {
                drawLine(
                    color = colors.base300,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f,
                )
                drawLine(
                    color = colors.base300,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 1f,
                )
            }
            .background(if (cell.isToday) colors.primary.copy(alpha = 0.1f) else Color.Transparent)
            .padding(horizontal = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(DayHeaderHeight)
                .clip(dimens.fieldShape)
                .calendarClickable { onDaySelected(cell.date) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DText(
                text = cell.date.day.toString(),
                style = DType.sm.semibold(),
                color = when {
                    cell.isToday -> colors.primary
                    cell.inMonth -> colors.baseContent
                    else -> colors.mutedContent
                },
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(dimens.fieldShape)
                .calendarClickable { onCreateAt(cell.date, MonthDefaultCreateMinutes) }
                .padding(start = 2.dp, end = 2.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            cell.events.forEach { event ->
                MonthChip(
                    event = event,
                    zone = zone,
                    alpha = alpha,
                    onClick = { onOpenEvent(event) },
                )
            }
            if (cell.overflow > 0) {
                DText(
                    text = "+${cell.overflow}",
                    style = DType.xs,
                    color = colors.mutedContent.copy(alpha = colors.mutedContent.alpha * alpha),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MonthChip(
    event: CalendarEventItem,
    zone: TimeZone,
    alpha: Float,
    onClick: () -> Unit,
) {
    val dimens = LocalKalendeeDimens.current
    val spec = rememberCalendarColorSpec(event.color, event.calendarId)
    val cancelled = event.status == EventStatus.CANCELLED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ChipHeight)
            .clip(dimens.fieldShape)
            .background(spec.fill.copy(alpha = (if (cancelled) 0.55f else 1f) * alpha))
            .calendarClickable(onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DText(
            text = monthChipLabel(event, zone),
            style = DType.xs.copy(
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            ),
            color = spec.content.copy(alpha = spec.content.alpha * alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
