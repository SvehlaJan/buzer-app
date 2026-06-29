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
import app.buzer.android.ui.status.BlockingLoadingState
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.LoadingActionButton
import app.buzer.android.ui.status.WarningStatus
import app.buzer.shared.model.RescheduleTaskRequest
import java.time.LocalDate
import java.time.LocalDateTime

sealed interface BlockerActionState {
    data object Idle : BlockerActionState
    data class Loading(val label: String) : BlockerActionState
    data class Failed(val message: String) : BlockerActionState

    val actionsDisabled: Boolean
        get() = this is Loading

    val loadingLabel: String?
        get() = (this as? Loading)?.label
}

@Composable
fun StartBlockerScreen(
    onBack: () -> Unit,
    onStart: () -> Unit = {},
    onPostpone: () -> Unit = {},
    showBack: Boolean = true,
    checking: Boolean = false,
    warningMessage: String? = null,
    actionState: BlockerActionState = BlockerActionState.Idle,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onRetryAction: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (checking) {
            BlockingLoadingState("Checking task state...")
            return@Column
        }
        warningMessage?.let { WarningStatus(it) }
        Text("Start blocker")
        LoadingActionButton(
            label = "I'm starting",
            loadingLabel = actionState.loadingLabel ?: "Starting...",
            loading = actionState is BlockerActionState.Loading,
            onClick = onStart,
            enabled = !actionState.actionsDisabled,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = onPostpone,
            enabled = !actionState.actionsDisabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (actionState is BlockerActionState.Loading) "Working..." else "Postpone 5 min")
        }
        if (showBack) {
            OutlinedButton(
                onClick = onBack,
                enabled = !actionState.actionsDisabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back")
            }
        }
    }

    errorMessage?.let { message ->
        ErrorAlertDialog(
            title = "Action failed",
            message = message,
            onDismiss = onDismissError,
            retryLabel = "Retry",
            onRetry = onRetryAction
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeadlineBlockerScreen(
    onBack: () -> Unit,
    onDone: () -> Unit = {},
    onExtend: () -> Unit = {},
    onReschedule: (RescheduleTaskRequest) -> Unit = {},
    showBack: Boolean = true,
    checking: Boolean = false,
    warningMessage: String? = null,
    actionState: BlockerActionState = BlockerActionState.Idle,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onRetryAction: () -> Unit = {}
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
        if (checking) {
            BlockingLoadingState("Checking task state...")
            return@Column
        }
        warningMessage?.let { WarningStatus(it) }
        if (rescheduleVisible) {
            Text("Reschedule task")
            OutlinedButton(
                onClick = {
                    showDatePicker = true
                    errorText = null
                },
                enabled = !actionState.actionsDisabled,
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
                enabled = !actionState.actionsDisabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Duration minutes input" }
            )
            errorText?.let { Text(it) }
            LoadingActionButton(
                label = "Save reschedule",
                loadingLabel = actionState.loadingLabel ?: "Rescheduling...",
                loading = actionState is BlockerActionState.Loading,
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
                enabled = !actionState.actionsDisabled,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = {
                    rescheduleVisible = false
                    errorText = null
                },
                enabled = !actionState.actionsDisabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        } else {
            Text("Deadline blocker")
            LoadingActionButton(
                label = "Done",
                loadingLabel = actionState.loadingLabel ?: "Completing...",
                loading = actionState is BlockerActionState.Loading,
                onClick = onDone,
                enabled = !actionState.actionsDisabled,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = onExtend,
                enabled = !actionState.actionsDisabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Extend 30 min")
            }
            OutlinedButton(
                onClick = {
                    rescheduleVisible = true
                    errorText = null
                },
                enabled = !actionState.actionsDisabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reschedule")
            }
            if (showBack) {
                OutlinedButton(
                    onClick = onBack,
                    enabled = !actionState.actionsDisabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Back")
                }
            }
        }
    }

    errorMessage?.let { message ->
        ErrorAlertDialog(
            title = "Action failed",
            message = message,
            onDismiss = onDismissError,
            retryLabel = "Retry",
            onRetry = onRetryAction
        )
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
