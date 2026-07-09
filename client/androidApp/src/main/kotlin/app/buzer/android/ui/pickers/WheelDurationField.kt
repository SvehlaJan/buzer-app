package app.buzer.android.ui.pickers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.theme.Spacing
import com.commandiron.wheel_picker_compose.core.WheelTextPicker
import kotlin.math.abs

private val HOURS_RANGE = 0..6
private val MINUTE_STEPS = listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55)

internal fun totalDurationMinutes(hours: Int, minutes: Int): Int = hours * 60 + minutes

internal fun durationWheelStart(totalMinutes: Int): Pair<Int, Int> {
    val maxMinutes = HOURS_RANGE.last * 60 + MINUTE_STEPS.last()
    val clamped = totalMinutes.coerceIn(0, maxMinutes)
    val hours = (clamped / 60).coerceIn(HOURS_RANGE)
    val remainder = clamped % 60
    val minutes = MINUTE_STEPS.minByOrNull { abs(it - remainder) } ?: 0
    return hours to minutes
}

internal fun formatDuration(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return "${hours}h ${minutes}m"
}

internal fun canConfirmDuration(hours: Int, minutes: Int): Boolean =
    totalDurationMinutes(hours, minutes) > 0

@Composable
fun WheelDurationField(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = label,
    dialogTitle: String = label
) {
    var dialogVisible by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { dialogVisible = true },
        enabled = enabled,
        modifier = modifier.semantics { this.contentDescription = contentDescription }
    ) {
        Text(formatDuration(minutes))
    }

    if (dialogVisible) {
        val start = remember(minutes) { durationWheelStart(minutes) }
        var pendingHours by remember { mutableIntStateOf(start.first) }
        var pendingMinutes by remember { mutableIntStateOf(start.second) }

        AlertDialog(
            onDismissRequest = { dialogVisible = false },
            title = { Text(dialogTitle) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    WheelTextPicker(
                        texts = HOURS_RANGE.map { "${it}h" },
                        startIndex = HOURS_RANGE.indexOf(start.first),
                        size = DpSize(96.dp, 128.dp),
                        rowCount = 3,
                        onScrollFinished = { index ->
                            pendingHours = HOURS_RANGE.elementAt(index)
                            null
                        }
                    )
                    WheelTextPicker(
                        texts = MINUTE_STEPS.map { "${it}m" },
                        startIndex = MINUTE_STEPS.indexOf(start.second),
                        size = DpSize(96.dp, 128.dp),
                        rowCount = 3,
                        onScrollFinished = { index ->
                            pendingMinutes = MINUTE_STEPS[index]
                            null
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMinutesChange(totalDurationMinutes(pendingHours, pendingMinutes))
                        dialogVisible = false
                    },
                    enabled = canConfirmDuration(pendingHours, pendingMinutes)
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogVisible = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
