package dev.kolektiv.kalendee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CalendarSidebar(
    calendars: List<CalendarUi>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onToggle: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val colors = KalendeeTheme.colors
    val metrics = KalendeeTheme.metrics
    Column(
        modifier = Modifier
            .width(metrics.sidebar)
            .fillMaxHeight()
            .background(colors.base100)
            .border(width = 1.dp, color = colors.base300),
    ) {
        BasicText(
            "Calendars",
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
            style = TextStyle(
                color = colors.baseContent.copy(alpha = 0.48f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
            ),
        )
        calendars.forEach { calendar ->
            val selected = calendar.id == selectedId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (selected) colors.base200 else colors.base100)
                    .clickable { onSelect(calendar.id) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val swatch = colors.named(calendar.color)
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .border(1.dp, swatch, RoundedCornerShape(3.dp))
                        .background(if (calendar.hidden) colors.base100 else swatch)
                        .clickable { onToggle(calendar.id) },
                )
                BasicText(
                    calendar.name,
                    style = TextStyle(
                        color = if (calendar.hidden) colors.baseContent.copy(alpha = 0.4f) else colors.baseContent,
                        fontSize = 14.sp,
                    ),
                )
            }
        }
        Box(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 1.dp, color = colors.base300)
                .clickable(onClick = onOpenSettings)
                .padding(12.dp),
        ) {
            BasicText(
                "Built by Kolektiv",
                style = TextStyle(color = colors.baseContent.copy(alpha = 0.45f), fontSize = 11.sp),
            )
        }
    }
}
