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
import app.buzer.android.ui.status.BlockingErrorState
import app.buzer.android.ui.status.BlockingLoadingState
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.status.LoadingActionButton
import app.buzer.android.ui.status.WarningStatus
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TaskSnapshotItem

sealed interface MorningReviewContentState {
    data object Loading : MorningReviewContentState
    data class Ready(val warning: String? = null, val refreshing: Boolean = false) : MorningReviewContentState
    data class Error(val message: String) : MorningReviewContentState
}

fun buildMorningReviewContentState(
    invalidTaskCount: Int,
    refreshing: Boolean,
    refreshError: String?,
    hasLoadedOnce: Boolean
): MorningReviewContentState =
    when {
        refreshError != null && !hasLoadedOnce -> MorningReviewContentState.Error(refreshError)
        refreshError != null -> MorningReviewContentState.Ready(warning = "Refresh failed. Showing last known tasks.")
        refreshing && !hasLoadedOnce && invalidTaskCount == 0 -> MorningReviewContentState.Loading
        refreshing -> MorningReviewContentState.Ready(refreshing = true)
        else -> MorningReviewContentState.Ready()
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningReviewScreen(
    invalidTasks: List<TaskSnapshotItem> = emptyList(),
    onBack: () -> Unit,
    onRepair: (String, RescheduleTaskRequest) -> Unit = { _, _ -> },
    onSnooze: () -> Unit = {},
    showBack: Boolean = true,
    contentState: MorningReviewContentState = MorningReviewContentState.Ready(),
    repairing: Boolean = false,
    snoozing: Boolean = false,
    actionErrorMessage: String? = null,
    onDismissActionError: () -> Unit = {},
    onRetryAction: () -> Unit = {},
    onRetryRefresh: () -> Unit = {}
) {
    var repairingTask by remember { mutableStateOf<TaskSnapshotItem?>(null) }
    var deadline by remember(repairingTask?.id) { mutableStateOf<DateTimeSelection?>(null) }
    var durationMinutes by remember(repairingTask?.id) {
        mutableStateOf(repairingTask?.durationMinutes ?: 30)
    }
    var errorText by remember(repairingTask?.id) { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Morning review")

        when (val state = contentState) {
            MorningReviewContentState.Loading -> {
                BlockingLoadingState("Loading morning review...")
                return@Column
            }
            is MorningReviewContentState.Error -> {
                BlockingErrorState(message = state.message, onRetry = onRetryRefresh)
                return@Column
            }
            is MorningReviewContentState.Ready -> {
                if (state.refreshing) InlineLoadingStatus("Refreshing...")
                state.warning?.let { WarningStatus(it, onRetry = onRetryRefresh) }
            }
        }

        val task = repairingTask
        if (task == null) {
            MorningReviewTaskList(
                invalidTasks = invalidTasks,
                onRepair = { repairingTask = it },
                onSnooze = onSnooze,
                onBack = onBack,
                showBack = showBack,
                snoozing = snoozing
            )
        } else {
            Text("Repair task")
            Text(task.title)
            WheelDateTimeField(
                selection = deadline,
                onSelectionChange = {
                    deadline = it
                    errorText = null
                },
                label = "Repair deadline picker",
                enabled = !repairing && !snoozing,
                modifier = Modifier.fillMaxWidth()
            )
            WheelDurationField(
                minutes = durationMinutes,
                onMinutesChange = {
                    durationMinutes = it
                    errorText = null
                },
                label = "Repair duration minutes input",
                enabled = !repairing && !snoozing,
                modifier = Modifier.fillMaxWidth()
            )
            errorText?.let { Text(it) }
            LoadingActionButton(
                label = "Save repair",
                loadingLabel = "Saving...",
                loading = repairing,
                onClick = {
                    val selectedDeadline = deadline
                    if (selectedDeadline == null) {
                        errorText = "Deadline is required"
                    } else {
                        onRepair(
                            task.id,
                            RescheduleTaskRequest(
                                dueDatetime = selectedDeadline.toIsoInstantString(),
                                durationMinutes = durationMinutes
                            )
                        )
                    }
                },
                enabled = !repairing && !snoozing,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = {
                    repairingTask = null
                    errorText = null
                },
                enabled = !repairing && !snoozing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        }
    }

    actionErrorMessage?.let { message ->
        ErrorAlertDialog(
            title = "Action failed",
            message = message,
            onDismiss = onDismissActionError,
            retryLabel = "Retry",
            onRetry = onRetryAction
        )
    }
}

@Composable
private fun MorningReviewTaskList(
    invalidTasks: List<TaskSnapshotItem>,
    onRepair: (TaskSnapshotItem) -> Unit,
    onSnooze: () -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    snoozing: Boolean
) {
    if (invalidTasks.isEmpty()) {
        Text("No invalid tasks")
    } else {
        invalidTasks.forEach { task ->
            Text(task.title)
            if (task.validationErrors.isNotEmpty()) {
                Text(task.validationErrors.joinToString(", "))
            }
            OutlinedButton(
                onClick = { onRepair(task) },
                enabled = !snoozing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Repair")
            }
        }
    }

    LoadingActionButton(
        label = "Snooze 5 min",
        loadingLabel = "Snoozing...",
        loading = snoozing,
        onClick = onSnooze,
        modifier = Modifier.fillMaxWidth()
    )
    if (showBack) {
        OutlinedButton(
            onClick = onBack,
            enabled = !snoozing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }
    }
}
