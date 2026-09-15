package dev.kolektiv.kalendee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
fun MonthPane(
    calendars: List<CalendarUi>,
    events: List<EventUi>,
    onSelect: (EventUi) -> Unit,
    onDay: (LocalDate) -> Unit,
    onDraft: (Long, Long) -> Unit,
) {
    val colors = KalendeeTheme.colors
    val metrics = KalendeeTheme.metrics
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(tz).date
    val first = LocalDate(today.year, today.month, 1)
    val gridStart = remember(first) {
        first.plus(DatePeriod(days = 1 - first.dayOfWeek.isoDayNumber))
    }
    val days = (0 until 42).map { gridStart.plus(DatePeriod(days = it)) }

    Column(Modifier.fillMaxSize().background(colors.base100)) {
        Row(Modifier.fillMaxWidth().height(36.dp).border(1.dp, colors.base300)) {
            WEEKDAYS.forEach { name ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicText(
                        name.uppercase(),
                        style = TextStyle(
                            color = colors.baseContent.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            (0 until 6).forEach { week ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    (0 until 7).forEach { dow ->
                        val date = days[week * 7 + dow]
                        val inMonth = date.month == first.month
                        val dayStart = date.atStartOfDayIn(tz).toEpochMilliseconds()
                        val dayEvents = events.filter { ev ->
                            Instant.fromEpochMilliseconds(ev.startEpochMs).toLocalDateTime(tz).date == date
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(1.dp, colors.base300)
                                .background(if (inMonth) colors.base100 else colors.base200.copy(alpha = 0.4f))
                                .padding(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .clip(CircleShape)
                                    .background(if (date == today) colors.primary else colors.base100)
                                    .clickable { onDay(date) }
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(
                                    date.dayOfMonth.toString(),
                                    style = TextStyle(
                                        color = when {
                                            date == today -> colors.primaryContent
                                            inMonth -> colors.baseContent
                                            else -> colors.baseContent.copy(alpha = 0.3f)
                                        },
                                        fontSize = 13.sp,
                                    ),
                                )
                            }
                            dayEvents.take(3).forEach { event ->
                                val color = colors.named(
                                    calendars.firstOrNull { it.id == event.calendarId }?.color ?: "primary",
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(metrics.radiusField))
                                        .background(color.copy(alpha = 0.28f))
                                        .clickable { onSelect(event) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    BasicText(
                                        event.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = TextStyle(
                                            color = colors.baseContent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                    )
                                }
                            }
                            if (dayEvents.size > 3) {
                                BasicText(
                                    "+${dayEvents.size - 3} more",
                                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                                    style = TextStyle(color = colors.baseContent.copy(alpha = 0.45f), fontSize = 10.sp),
                                )
                            }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clickable {
                                        onDraft(dayStart + 9 * 3_600_000L, dayStart + 10 * 3_600_000L)
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
