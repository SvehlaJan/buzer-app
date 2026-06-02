package app.buzer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.datetime.datePickerMillisToLocalDate
import app.buzer.android.ui.datetime.localDateToDatePickerMillis
import app.buzer.shared.model.RescheduleTaskRequest
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
fun StartBlockerScreen(
    onBack: () -> Unit,
    onStart: () -> Unit = {},
    onPostpone: () -> Unit = {},
    showBack: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Start blocker")
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("I'm starting")
        }
        OutlinedButton(onClick = onPostpone, modifier = Modifier.fillMaxWidth()) {
            Text("Postpone 5 min")
        }
        if (showBack) {
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadlineBlockerScreen(
    onBack: () -> Unit,
    onDone: () -> Unit = {},
    onExtend: () -> Unit = {},
    onReschedule: (RescheduleTaskRequest) -> Unit = {},
    showBack: Boolean = true
) {
    var rescheduleVisible by remember { mutableStateOf(false) }
    var deadline by remember { mutableStateOf<DateTimeSelection?>(null) }
    var durationText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
    val defaultDateTime = remember { LocalDateTime.now().plusHours(1) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (rescheduleVisible) {
            Text("Reschedule task")
            OutlinedButton(
                onClick = {
                    showDatePicker = true
                    errorText = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "New deadline picker" }
            ) {
                Text(deadline?.formatForDisplay() ?: "No deadline selected")
            }
            OutlinedTextField(
                value = durationText,
                onValueChange = {
                    durationText = it
                    errorText = null
                },
                label = { Text("Duration minutes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Duration minutes input" }
            )
            errorText?.let { Text(it) }
            Button(
                onClick = {
                    val selectedDeadline = deadline
                    val duration = durationText.trim().toIntOrNull()
                    when {
                        selectedDeadline == null -> errorText = "Deadline is required"
                        duration == null || duration <= 0 -> {
                            errorText = "Duration must be a positive whole number of minutes"
                        }
                        else -> {
                            onReschedule(
                                RescheduleTaskRequest(
                                    dueDatetime = selectedDeadline.toIsoInstantString(),
                                    durationMinutes = duration
                                )
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save reschedule")
            }
            OutlinedButton(
                onClick = {
                    rescheduleVisible = false
                    errorText = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        } else {
            Text("Deadline blocker")
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
            OutlinedButton(onClick = onExtend, modifier = Modifier.fillMaxWidth()) {
                Text("Extend 30 min")
            }
            OutlinedButton(
                onClick = {
                    rescheduleVisible = true
                    errorText = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reschedule")
            }
            if (showBack) {
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
            }
        }
    }

    val initialDate = deadline?.date ?: LocalDate.now()
    val initialHour = deadline?.hour ?: defaultDateTime.hour
    val initialMinute = deadline?.minute ?: 0

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = localDateToDatePickerMillis(initialDate)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedMillis ->
                            pendingDate = datePickerMillisToLocalDate(selectedMillis)
                            showDatePicker = false
                            showTimePicker = true
                        }
                    },
                    enabled = datePickerState.selectedDateMillis != null
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = initialHour,
            initialMinute = initialMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select deadline time") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deadline = DateTimeSelection(
                            date = pendingDate ?: initialDate,
                            hour = timePickerState.hour,
                            minute = timePickerState.minute
                        )
                        showTimePicker = false
                        pendingDate = null
                        errorText = null
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTimePicker = false
                        pendingDate = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
