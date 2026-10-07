package dev.kolektiv.kalendee.ui.components.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.design.components.DButton
import dev.kolektiv.kalendee.ui.design.components.DButtonSize
import dev.kolektiv.kalendee.ui.design.components.DButtonVariant
import dev.kolektiv.kalendee.ui.design.components.DIconButton
import dev.kolektiv.kalendee.ui.design.components.DModal
import dev.kolektiv.kalendee.ui.design.medium
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.format.monthName
import dev.kolektiv.kalendee.ui.icons.Lucide
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime

/**
 * Compact stepper-based date/time picker. Deliberately avoids experimental platform
 * pickers so it behaves identically on every target: rows of chevron buttons for
 * year/month/day (and hour/minute unless [dateOnly]).
 */
@Composable
fun DateTimePickerDialog(
    initial: LocalDateTime,
    title: String,
    dateOnly: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (LocalDateTime) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    DModal(onDismissRequest = onDismiss) {
        DText(text = title, style = DType.lg.semibold())
        Spacer(modifier = Modifier.height(LocalKalendeeDimens.current.space2))
        StepperRow(
            label = "Year",
            value = value.year.toString(),
            onDecrease = { value = withDate(value, shiftDate(value.date, years = -1)) },
            onIncrease = { value = withDate(value, shiftDate(value.date, years = 1)) },
        )
        StepperRow(
            label = "Month",
            value = monthName(value.month),
            onDecrease = { value = withDate(value, shiftDate(value.date, months = -1)) },
            onIncrease = { value = withDate(value, shiftDate(value.date, months = 1)) },
        )
        StepperRow(
            label = "Day",
            value = value.day.toString(),
            onDecrease = { value = withDate(value, shiftDate(value.date, days = -1)) },
            onIncrease = { value = withDate(value, shiftDate(value.date, days = 1)) },
        )
        if (!dateOnly) {
            StepperRow(
                label = "Hour",
                value = value.hour.pad2(),
                onDecrease = { value = withTime(value, value.hour - 1, value.minute) },
                onIncrease = { value = withTime(value, value.hour + 1, value.minute) },
            )
            StepperRow(
                label = "Minute",
                value = value.minute.pad2(),
                onDecrease = { value = withTime(value, value.hour, value.minute - 5) },
                onIncrease = { value = withTime(value, value.hour, value.minute + 5) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = LocalKalendeeDimens.current.space4),
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
            Spacer(modifier = Modifier.width(LocalKalendeeDimens.current.space2))
            DButton(
                onClick = { onConfirm(value) },
                size = DButtonSize.Sm,
            ) {
                DText(text = "OK", style = DType.sm.medium())
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    val colors = LocalKalendeeColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DText(
            text = label,
            style = DType.sm,
            color = colors.mutedContent,
            modifier = Modifier.weight(1f),
        )
        DIconButton(
            icon = Lucide.ChevronLeft,
            contentDescription = "Decrease $label",
            onClick = onDecrease,
            tint = colors.baseContent,
            size = 40.dp,
            iconSize = 18.dp,
        )
        DText(
            text = value,
            style = DType.base.medium(),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        DIconButton(
            icon = Lucide.ChevronRight,
            contentDescription = "Increase $label",
            onClick = onIncrease,
            tint = colors.baseContent,
            size = 40.dp,
            iconSize = 18.dp,
        )
    }
}

private fun withDate(value: LocalDateTime, date: LocalDate): LocalDateTime =
    date.atTime(value.hour, value.minute)

private fun withTime(value: LocalDateTime, hour: Int, minute: Int): LocalDateTime =
    value.date.atTime(
        hour = hour.coerceIn(0, 23),
        minute = ((minute % 60) + 60) % 60,
    )

private fun Int.pad2(): String = toString().padStart(2, '0')
