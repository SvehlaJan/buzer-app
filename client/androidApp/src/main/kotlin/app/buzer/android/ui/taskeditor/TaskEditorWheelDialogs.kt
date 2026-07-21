package app.buzer.android.ui.taskeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.theme.Spacing
import com.commandiron.wheel_picker_compose.WheelDatePicker
import com.commandiron.wheel_picker_compose.core.WheelTextPicker
import java.time.LocalDate
import kotlin.math.abs

private val timeMinuteSteps = (0..55 step 5).toList()
private val durationHours = (0..6).toList()

@Composable
internal fun DateWheelDialog(
    initialDate: LocalDate,
    onApply: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    minDate: LocalDate = initialDate.minusYears(1),
    maxDate: LocalDate = initialDate.plusYears(5)
) {
    var pendingDate by remember(initialDate) { mutableStateOf(initialDate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom date") },
        text = {
            WheelDatePicker(
                startDate = initialDate,
                minDate = minDate,
                maxDate = maxDate,
                yearsRange = minDate.year..maxDate.year,
                size = DpSize(256.dp, 128.dp),
                rowCount = 3
            ) { selectedDate ->
                pendingDate = selectedDate
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(pendingDate) }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
internal fun TimeWheelDialog(
    initialHour: Int,
    initialMinute: Int,
    onApply: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val hour = initialHour.coerceIn(0, 23)
    val minute = closestTimeMinute(initialMinute)
    var pendingHour by remember(initialHour) { mutableIntStateOf(hour) }
    var pendingMinute by remember(initialMinute) { mutableIntStateOf(minute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom start time") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                WheelTextPicker(
                    texts = (0..23).map { "%02d".format(it) },
                    startIndex = hour,
                    size = DpSize(96.dp, 128.dp),
                    rowCount = 3,
                    onScrollFinished = { index ->
                        pendingHour = index
                        null
                    }
                )
                WheelTextPicker(
                    texts = timeMinuteSteps.map { "%02d".format(it) },
                    startIndex = timeMinuteSteps.indexOf(minute),
                    size = DpSize(96.dp, 128.dp),
                    rowCount = 3,
                    onScrollFinished = { index ->
                        pendingMinute = timeMinuteSteps[index]
                        null
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(pendingHour, pendingMinute) }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
internal fun DurationWheelDialog(
    initialDurationMinutes: Int,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val initial = durationWheelStart(initialDurationMinutes)
    var pendingHours by remember(initialDurationMinutes) { mutableIntStateOf(initial.first) }
    var pendingMinutes by remember(initialDurationMinutes) { mutableIntStateOf(initial.second) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom duration") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                WheelTextPicker(
                    texts = durationHours.map { "${it}h" },
                    startIndex = durationHours.indexOf(initial.first),
                    size = DpSize(96.dp, 128.dp),
                    rowCount = 3,
                    onScrollFinished = { index ->
                        pendingHours = durationHours[index]
                        null
                    }
                )
                WheelTextPicker(
                    texts = timeMinuteSteps.map { "${it}m" },
                    startIndex = timeMinuteSteps.indexOf(initial.second),
                    size = DpSize(96.dp, 128.dp),
                    rowCount = 3,
                    onScrollFinished = { index ->
                        pendingMinutes = timeMinuteSteps[index]
                        null
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(pendingHours * 60 + pendingMinutes) },
                enabled = pendingHours > 0 || pendingMinutes > 0
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun closestTimeMinute(minute: Int): Int =
    timeMinuteSteps.minByOrNull { abs(it - minute.coerceIn(0, 59)) } ?: 0

internal fun durationWheelStart(totalMinutes: Int): Pair<Int, Int> {
    val maxMinutes = durationHours.last() * 60 + timeMinuteSteps.last()
    val clamped = totalMinutes.coerceIn(0, maxMinutes)
    val rounded = (((clamped + 2) / 5) * 5).coerceAtMost(maxMinutes)
    return (rounded / 60) to (rounded % 60)
}
