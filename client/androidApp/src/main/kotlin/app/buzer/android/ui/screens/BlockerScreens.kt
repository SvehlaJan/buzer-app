package app.buzer.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.buzer.android.ui.components.BlockerSurface
import app.buzer.android.ui.components.ChecklistProgress
import app.buzer.android.ui.components.CommitmentEditor
import app.buzer.android.ui.components.commitmentSaveLabel
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.LoadingActionButton
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.ui.blockers.BlockerKind
import app.buzer.shared.ui.blockers.BlockerUiModel
import app.buzer.shared.ui.blockers.buildBlockerUiModel
import app.buzer.shared.ui.commitment.CommitmentEditorMode
import app.buzer.shared.ui.commitment.CommitmentEditorValidation
import java.time.ZoneId

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
    model: BlockerUiModel = buildBlockerUiModel(BlockerKind.Start, null),
    showBack: Boolean = true,
    checking: Boolean = false,
    warningMessage: String? = null,
    actionState: BlockerActionState = BlockerActionState.Idle,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onRetryAction: () -> Unit = {}
) {
    BackHandler(enabled = !showBack) {
        // Production full-screen blockers must stay open until a blocker action resolves them.
    }

    BlockerSurface(
        model = model,
        warningMessage = warningMessage,
        checking = checking
    ) {
        if (checking) {
            return@BlockerSurface
        }
        if (model.totalCount > 0) {
            Text("Checklist ${model.checkedCount}/${model.totalCount}")
            model.nextUncheckedChildTitle?.let { Text("Next: $it") }
        }
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
            onRetry = onRetryAction,
            dismissLabel = null
        )
    }
}

@Composable
fun DeadlineBlockerScreen(
    onBack: () -> Unit,
    onDone: () -> Unit = {},
    onExtend: () -> Unit = {},
    onReschedule: (RescheduleTaskRequest) -> Unit = {},
    onToggleChild: (ChecklistItem, Boolean) -> Unit = { _, _ -> },
    actionIdFactory: () -> String = { "test-action-id" },
    deviceId: String = "test-device-id",
    occurrenceKey: String = "test-occurrence-key",
    model: BlockerUiModel = buildBlockerUiModel(BlockerKind.Deadline, null),
    showBack: Boolean = true,
    checking: Boolean = false,
    warningMessage: String? = null,
    actionState: BlockerActionState = BlockerActionState.Idle,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onRetryAction: () -> Unit = {}
) {
    BackHandler(enabled = !showBack) {
        // Production full-screen blockers must stay open until a blocker action resolves them.
    }

    var rescheduleVisible by remember { mutableStateOf(false) }
    var deadline by remember(model.title, model.blockWindow) { mutableStateOf(DateTimeDraftSelection.Empty) }
    var durationMinutes by remember { mutableStateOf(30) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val initialDescription = remember(model.description) { model.description.orEmpty() }
    var description by remember(model.description) { mutableStateOf(initialDescription) }
    var descriptionExpanded by remember(model.description) { mutableStateOf(initialDescription.isNotBlank()) }

    if (rescheduleVisible) {
        BlockerSurface(
            model = model.copy(eyebrow = "Reschedule task", consequenceText = null),
            warningMessage = warningMessage,
            checking = checking
        ) {
            if (checking) {
                return@BlockerSurface
            }
            Text("Reschedule task")
            CommitmentEditor(
                mode = CommitmentEditorMode.Reschedule,
                title = model.title,
                onTitleChange = {},
                titleEditable = false,
                description = description,
                onDescriptionChange = { description = it },
                descriptionExpanded = descriptionExpanded,
                onDescriptionExpandedChange = { descriptionExpanded = it },
                projectContent = { ReceiptPlaceholder("Unchanged") },
                assigneeContent = { ReceiptPlaceholder("Unchanged") },
                start = deadline,
                onStartChange = {
                    deadline = it
                    errorText = null
                },
                durationMinutes = durationMinutes,
                onDurationChange = {
                    durationMinutes = it
                    errorText = null
                },
                validation = CommitmentEditorValidation(
                    bannerMessage = null,
                    titleError = null,
                    projectError = null,
                    assigneeError = null,
                    startError = errorText,
                    durationError = null
                ),
                submitting = actionState is BlockerActionState.Loading,
                enabled = !actionState.actionsDisabled,
                submitEnabled = !actionState.actionsDisabled,
                submitLabel = commitmentSaveLabel(deadline.toSelection(), durationMinutes),
                loadingLabel = actionState.loadingLabel ?: "Rescheduling...",
                onSubmit = {
                    val selectedStart = deadline.toSelection()
                    if (selectedStart == null) {
                        errorText = "Start time is required"
                    } else {
                        onReschedule(
                            rescheduleRequestFromEditorStart(
                                actionId = actionIdFactory(),
                                deviceId = deviceId,
                                expectedOccurrenceKey = occurrenceKey,
                                start = selectedStart,
                                durationMinutes = durationMinutes,
                                initialDescription = initialDescription,
                                editedDescription = description
                            )
                        )
                    }
                },
                onCancel = {
                    rescheduleVisible = false
                    errorText = null
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    } else {
        BlockerSurface(
            model = model,
            warningMessage = warningMessage,
            checking = checking
        ) {
            if (checking) {
                return@BlockerSurface
            }
            ChecklistProgress(
                items = model.checklistItems,
                interactive = true,
                enabled = !actionState.actionsDisabled,
                onCheckedChange = onToggleChild
            )
            LoadingActionButton(
                label = "Done",
                loadingLabel = actionState.loadingLabel ?: "Completing...",
                loading = actionState is BlockerActionState.Loading,
                onClick = onDone,
                enabled = !actionState.actionsDisabled && model.doneEnabled,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = onExtend,
                enabled = !actionState.actionsDisabled && model.extendEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (model.extendEnabled) "Extend 30m" else "Extend 30m unavailable for recurring tasks")
            }
            OutlinedButton(
                onClick = {
                    rescheduleVisible = true
                    errorText = null
                },
                enabled = !actionState.actionsDisabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Reschedule action" }
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
            onRetry = onRetryAction,
            dismissLabel = null
        )
    }
}

fun descriptionAwareRescheduleRequest(
    actionId: String = "test-action-id",
    deviceId: String = "test-device-id",
    expectedOccurrenceKey: String = "test-occurrence-key",
    dueDatetime: String,
    durationMinutes: Int,
    initialDescription: String?,
    editedDescription: String
): RescheduleTaskRequest {
    val initial = initialDescription.orEmpty().trim()
    val edited = editedDescription.trim()
    return when {
        edited == initial -> RescheduleTaskRequest(
            dueDatetime = dueDatetime,
            durationMinutes = durationMinutes,
            actionId = actionId,
            deviceId = deviceId,
            expectedOccurrenceKey = expectedOccurrenceKey
        )
        edited.isBlank() -> RescheduleTaskRequest.clearDescription(
            dueDatetime = dueDatetime,
            durationMinutes = durationMinutes,
            actionId = actionId,
            deviceId = deviceId,
            expectedOccurrenceKey = expectedOccurrenceKey
        )
        else -> RescheduleTaskRequest.setDescription(
            dueDatetime = dueDatetime,
            durationMinutes = durationMinutes,
            description = edited,
            actionId = actionId,
            deviceId = deviceId,
            expectedOccurrenceKey = expectedOccurrenceKey
        )
    }
}

fun rescheduleRequestFromEditorStart(
    actionId: String = "test-action-id",
    deviceId: String = "test-device-id",
    expectedOccurrenceKey: String = "test-occurrence-key",
    start: DateTimeSelection,
    durationMinutes: Int,
    initialDescription: String?,
    editedDescription: String,
    zoneId: ZoneId = ZoneId.systemDefault()
): RescheduleTaskRequest {
    val dueDatetime = start.date.atTime(start.hour, start.minute)
        .atZone(zoneId)
        .toInstant()
        .toString()
    return descriptionAwareRescheduleRequest(
        actionId = actionId,
        deviceId = deviceId,
        expectedOccurrenceKey = expectedOccurrenceKey,
        dueDatetime = dueDatetime,
        durationMinutes = durationMinutes,
        initialDescription = initialDescription,
        editedDescription = editedDescription
    )
}

@Composable
private fun ReceiptPlaceholder(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
