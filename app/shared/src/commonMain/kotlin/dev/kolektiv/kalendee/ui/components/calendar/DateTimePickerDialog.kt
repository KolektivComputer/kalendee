package dev.kolektiv.kalendee.ui.components.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.ui.format.monthName
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.atTime

/**
 * Compact stepper-based date/time picker. Deliberately avoids experimental platform
 * pickers so it behaves identically on every target: rows of `-`/`+` buttons for
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDecrease) { Text("-") }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        TextButton(onClick = onIncrease) { Text("+") }
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
