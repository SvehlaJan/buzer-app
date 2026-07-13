package app.buzer.android.ui.pickers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.theme.Spacing
import com.commandiron.wheel_picker_compose.WheelDateTimePicker
import java.time.LocalDateTime
import java.time.LocalTime

internal fun toDateTimeSelection(dateTime: LocalDateTime): DateTimeSelection =
    DateTimeSelection(date = dateTime.toLocalDate(), hour = dateTime.hour, minute = dateTime.minute)

internal fun toLocalDateTime(selection: DateTimeSelection): LocalDateTime =
    selection.date.atTime(selection.hour, selection.minute)

internal fun startDateTimeFor(selection: DateTimeSelection?, now: LocalDateTime): LocalDateTime =
    selection?.let(::toLocalDateTime) ?: now

internal fun startDateTimeForDraft(selection: DateTimeDraftSelection, now: LocalDateTime): LocalDateTime {
    val date = selection.date ?: now.toLocalDate()
    val hour = selection.hour ?: now.hour
    val minute = selection.minute ?: now.minute
    return LocalDateTime.of(date, LocalTime.of(hour, minute))
}

internal fun dateButtonLabel(selection: DateTimeDraftSelection): String = selection.dateLabel

internal fun timeButtonLabel(selection: DateTimeDraftSelection): String = selection.timeLabel

@Composable
fun WheelDateTimeField(
    selection: DateTimeDraftSelection,
    onSelectionChange: (DateTimeDraftSelection) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = label,
    dialogTitle: String = label
) {
    var dialogVisible by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        OutlinedButton(
            onClick = { dialogVisible = true },
            enabled = enabled,
            modifier = Modifier.weight(1f)
        ) {
            Text(dateButtonLabel(selection))
        }
        OutlinedButton(
            onClick = { dialogVisible = true },
            enabled = enabled,
            modifier = Modifier.weight(1f)
        ) {
            Text(timeButtonLabel(selection))
        }
    }

    if (dialogVisible) {
        val start = remember(selection) { startDateTimeForDraft(selection, LocalDateTime.now()) }
        var pending by remember { mutableStateOf(start) }

        AlertDialog(
            onDismissRequest = { dialogVisible = false },
            title = { Text(dialogTitle) },
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
                        onSelectionChange(DateTimeDraftSelection.fromSelection(toDateTimeSelection(pending)))
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
