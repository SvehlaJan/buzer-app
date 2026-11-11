package app.buzer.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.components.BlockerSurface
import app.buzer.android.ui.components.ChecklistProgress
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.LoadingActionButton
import app.buzer.android.ui.taskeditor.TaskEditorPresentation
import app.buzer.android.ui.taskeditor.TaskEditorUpdateScreen
import app.buzer.android.ui.taskeditor.TaskEditorUpdateState
import app.buzer.android.ui.taskeditor.rememberTaskEditorChoices
import app.buzer.android.ui.taskeditor.rememberTaskEditorProjectRepository
import app.buzer.android.ui.taskeditor.taskEditorUpdateCancelRequest
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.ui.blockers.BlockerKind
import app.buzer.shared.ui.blockers.BlockerUiModel
import app.buzer.shared.ui.blockers.buildBlockerUiModel
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
    onRefreshTask: () -> Unit = {},
    onOpenTodoist: () -> Unit = {},
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

    val effectiveWarning = warningMessage ?: errorMessage

    BlockerSurface(
        model = model,
        warningMessage = effectiveWarning,
        checking = checking,
        onRefresh = onRefreshTask,
        onOpenTodoist = onOpenTodoist
    ) {
        if (checking) return@BlockerSurface
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

    if (errorMessage != null && warningMessage == null) {
        ErrorAlertDialog(
            title = "Action failed",
            message = errorMessage,
            onDismiss = onDismissError,
            retryLabel = null,
            dismissLabel = "Dismiss"
        )
    }
}

@Composable
fun DeadlineBlockerScreen(
    onBack: () -> Unit,
    onDone: () -> Unit = {},
    onExtend: () -> Unit = {},
    onToggleChild: (ChecklistItem, Boolean) -> Unit = { _, _ -> },
    onOpenTodoist: () -> Unit = {},
    task: TaskSnapshotItem? = null,
    onUpdate: suspend (UpdateTaskRequest) -> CoordinatorActionResult = {
        CoordinatorActionResult.Warning("unavailable", "Reschedule is unavailable", null)
    },
    onRefreshUpdateWarning: suspend () -> Unit = {},
    onUpdateSuccess: () -> Unit = onBack,
    onRefreshTask: () -> Unit = {},
    occurrenceKey: String = "test-occurrence-key",
    snapshotConfirmed: Boolean = true,
    model: BlockerUiModel = buildBlockerUiModel(BlockerKind.Deadline, null),
    showBack: Boolean = true,
    checking: Boolean = false,
    warningMessage: String? = null,
    actionState: BlockerActionState = BlockerActionState.Idle,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    onRetryAction: () -> Unit = {},
    loadProjects: (suspend () -> List<ProjectItem>)? = null,
    refreshProjects: (suspend () -> List<ProjectItem>)? = null
) {
    BackHandler(enabled = !showBack) {
        // Production full-screen blockers must stay open until a blocker action resolves them.
    }

    var rescheduleRequested by remember { mutableStateOf(false) }
    val recovery = deadlineBlockerRecoveryState(
        task = task,
        expectedOccurrenceKey = occurrenceKey,
        rescheduleRequested = rescheduleRequested,
        snapshotConfirmed = snapshotConfirmed
    )

    val effectiveWarning = warningMessage ?: errorMessage

    when (recovery) {
        DeadlineBlockerRecoveryState.Actions -> DeadlineBlockerActions(
            model = model,
            onDone = onDone,
            onExtend = onExtend,
            onOpenReschedule = { rescheduleRequested = true },
            onToggleChild = onToggleChild,
            onRefreshTask = onRefreshTask,
            onOpenTodoist = onOpenTodoist,
            onBack = onBack,
            showBack = showBack,
            checking = checking,
            warningMessage = effectiveWarning,
            actionState = actionState
        )
        is DeadlineBlockerRecoveryState.Editor -> {
            DeadlineBlockerUpdateEditor(
                task = recovery.task,
                loadProjects = loadProjects,
                refreshProjects = refreshProjects,
                onUpdate = onUpdate,
                onRefreshWarning = onRefreshUpdateWarning,
                onSuccess = onUpdateSuccess,
                onCancel = { rescheduleRequested = false }
            )
            BackHandler(enabled = deadlineBlockerConsumesSystemBack(showBack)) {
                // System Back never exits a full-screen blocker, including while editing a recovery.
            }
        }
        DeadlineBlockerRecoveryState.RefreshRequired -> DeadlineBlockerRefreshRequired(
            model = model,
            checking = checking,
            warningMessage = effectiveWarning,
            onRefresh = onRefreshTask,
            onCancel = { rescheduleRequested = false }
        )
    }

    if (errorMessage != null && warningMessage == null) {
        ErrorAlertDialog(
            title = "Action failed",
            message = errorMessage,
            onDismiss = onDismissError,
            retryLabel = null,
            dismissLabel = "Dismiss"
        )
    }
}

internal sealed interface DeadlineBlockerRecoveryState {
    data object Actions : DeadlineBlockerRecoveryState
    data class Editor(val task: TaskSnapshotItem) : DeadlineBlockerRecoveryState
    data object RefreshRequired : DeadlineBlockerRecoveryState
}

internal val deadlineBlockerUpdatePresentation = TaskEditorPresentation(
    screenTitle = "Reschedule task",
    primaryVerb = "Reschedule"
)

internal fun deadlineBlockerRecoveryState(
    task: TaskSnapshotItem?,
    expectedOccurrenceKey: String,
    rescheduleRequested: Boolean,
    snapshotConfirmed: Boolean = true
): DeadlineBlockerRecoveryState = when {
    !rescheduleRequested -> DeadlineBlockerRecoveryState.Actions
    !snapshotConfirmed ||
        task == null ||
        task.occurrenceKey != expectedOccurrenceKey ||
        task.isCompleted ||
        task.isDeleted -> DeadlineBlockerRecoveryState.RefreshRequired
    else -> DeadlineBlockerRecoveryState.Editor(task)
}

internal fun deadlineBlockerCancelRequest(
    state: TaskEditorUpdateState,
    isDirty: Boolean
) = taskEditorUpdateCancelRequest(state, isDirty)

internal fun deadlineBlockerConsumesSystemBack(showBack: Boolean): Boolean = !showBack

@Composable
private fun DeadlineBlockerActions(
    model: BlockerUiModel,
    onDone: () -> Unit,
    onExtend: () -> Unit,
    onOpenReschedule: () -> Unit,
    onToggleChild: (ChecklistItem, Boolean) -> Unit,
    onRefreshTask: () -> Unit,
    onOpenTodoist: () -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    checking: Boolean,
    warningMessage: String?,
    actionState: BlockerActionState
) {
    BlockerSurface(
        model = model,
        warningMessage = warningMessage,
        checking = checking,
        onRefresh = onRefreshTask,
        onOpenTodoist = onOpenTodoist
    ) {
        if (checking) return@BlockerSurface
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
            onClick = onOpenReschedule,
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

@Composable
private fun DeadlineBlockerRefreshRequired(
    model: BlockerUiModel,
    checking: Boolean,
    warningMessage: String?,
    onRefresh: () -> Unit,
    onCancel: () -> Unit
) {
    BlockerSurface(
        model = model.copy(eyebrow = "Reschedule task", consequenceText = null),
        warningMessage = warningMessage,
        checking = checking
    ) {
        if (checking) return@BlockerSurface
        Text("A complete current task snapshot is required to reschedule.")
        Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Text("Refresh")
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@Composable
private fun DeadlineBlockerUpdateEditor(
    task: TaskSnapshotItem,
    loadProjects: (suspend () -> List<ProjectItem>)?,
    refreshProjects: (suspend () -> List<ProjectItem>)?,
    onUpdate: suspend (UpdateTaskRequest) -> CoordinatorActionResult,
    onRefreshWarning: suspend () -> Unit,
    onSuccess: () -> Unit,
    onCancel: () -> Unit
) {
    val projectRepository = rememberTaskEditorProjectRepository(
        loadProjects = loadProjects,
        refreshProjects = refreshProjects
    )
    val choicesHandle = rememberTaskEditorChoices(
        repository = projectRepository,
        key = task.id,
        requireNonEmptyProjects = false
    )
    val now = remember(task.id) { LocalDateTime.now() }

    TaskEditorUpdateScreen(
        task = task,
        choices = choicesHandle.choices,
        presentation = deadlineBlockerUpdatePresentation,
        now = now,
        onRetryChoices = choicesHandle.retry,
        onRefreshWarning = onRefreshWarning,
        onUpdate = onUpdate,
        onSuccess = onSuccess,
        onCancel = onCancel,
        footerContent = { ActivityLogSection(task) },
        modifier = Modifier.fillMaxSize()
    )
}
