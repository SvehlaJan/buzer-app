package app.buzer.android.ui.pickers

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.datetime.DateTimeSelection
import com.commandiron.wheel_picker_compose.WheelDateTimePicker
import java.time.LocalDateTime

internal fun toDateTimeSelection(dateTime: LocalDateTime): DateTimeSelection =
    DateTimeSelection(date = dateTime.toLocalDate(), hour = dateTime.hour, minute = dateTime.minute)

internal fun toLocalDateTime(selection: DateTimeSelection): LocalDateTime =
    selection.date.atTime(selection.hour, selection.minute)

internal fun startDateTimeFor(selection: DateTimeSelection?, now: LocalDateTime): LocalDateTime =
    selection?.let(::toLocalDateTime) ?: now

@Composable
fun WheelDateTimeField(
    selection: DateTimeSelection?,
    onSelectionChange: (DateTimeSelection) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var dialogVisible by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { dialogVisible = true },
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = label }
    ) {
        Text(selection?.formatForDisplay() ?: "No start time selected")
    }

    if (dialogVisible) {
        val start = remember(selection) { startDateTimeFor(selection, LocalDateTime.now()) }
        var pending by remember { mutableStateOf(start) }

        AlertDialog(
            onDismissRequest = { dialogVisible = false },
            title = { Text(label) },
            text = {
                WheelDateTimePicker(
                    startDateTime = start,
                    size = DpSize(256.dp, 128.dp),
                    rowCount = 3
                ) { snapped ->
                    pending = snapped
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSelectionChange(toDateTimeSelection(pending))
                        dialogVisible = false
                    }
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
