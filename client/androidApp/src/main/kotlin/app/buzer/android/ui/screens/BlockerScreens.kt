package app.buzer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.pickers.WheelDateTimeField
import app.buzer.android.ui.pickers.WheelDurationField
import app.buzer.android.ui.status.BlockingLoadingState
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.LoadingActionButton
import app.buzer.android.ui.status.WarningStatus
import app.buzer.shared.model.RescheduleTaskRequest

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
    var durationMinutes by remember { mutableStateOf(30) }
    var errorText by remember { mutableStateOf<String?>(null) }

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
            WheelDateTimeField(
                selection = deadline,
                onSelectionChange = {
                    deadline = it
                    errorText = null
                },
                label = "New start time picker",
                enabled = !actionState.actionsDisabled,
                modifier = Modifier.fillMaxWidth()
            )
            WheelDurationField(
                minutes = durationMinutes,
                onMinutesChange = {
                    durationMinutes = it
                    errorText = null
                },
                label = "Duration minutes input",
                enabled = !actionState.actionsDisabled,
                modifier = Modifier.fillMaxWidth()
            )
            errorText?.let { Text(it) }
            LoadingActionButton(
                label = "Save reschedule",
                loadingLabel = actionState.loadingLabel ?: "Rescheduling...",
                loading = actionState is BlockerActionState.Loading,
                onClick = {
                    val selectedDeadline = deadline
                    if (selectedDeadline == null) {
                        errorText = "Start time is required"
                    } else {
                        onReschedule(
                            RescheduleTaskRequest(
                                dueDatetime = selectedDeadline.toIsoInstantString(),
                                durationMinutes = durationMinutes
                            )
                        )
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
}
