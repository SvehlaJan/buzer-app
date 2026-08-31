package app.buzer.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.tasks.AddTaskDependencies
import app.buzer.android.ui.components.BlockerSurface
import app.buzer.android.ui.components.ChecklistEditor
import app.buzer.android.ui.components.ChecklistProgress
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.status.LoadingActionButton
import app.buzer.android.ui.status.WarningStatus
import app.buzer.android.ui.taskeditor.TaskEditorDraft
import app.buzer.android.ui.taskeditor.TaskEditorPresentation
import app.buzer.android.ui.taskeditor.TaskEditorUpdateChoices
import app.buzer.android.ui.taskeditor.TaskEditorUpdateContentValidation
import app.buzer.android.ui.taskeditor.TaskEditorUpdateScreen
import app.buzer.android.ui.taskeditor.initialTaskEditorUpdateDraft
import app.buzer.android.ui.taskeditor.recurrencePresetForUpdate
import app.buzer.android.ui.taskeditor.taskEditorUpdateChecklistAccess
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.ui.blockers.BlockerKind
import app.buzer.shared.ui.blockers.BlockerUiModel
import app.buzer.shared.ui.blockers.buildBlockerUiModel
import app.buzer.shared.ui.taskeditor.ChecklistEditorDraft
import app.buzer.shared.ui.taskeditor.validateChecklistDrafts
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.launch

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

    val context = LocalContext.current
    val deviceId = remember(context) {
        EnforcementDependencies.deviceIdentityStore(context.applicationContext).deviceId()
    }
    val scope = rememberCoroutineScope()
    val projectRepository = remember(loadProjects, refreshProjects) {
        if (loadProjects == null || refreshProjects == null) {
            lazy { AddTaskDependencies.projectRepository() }
        } else {
            null
        }
    }
    val loadProjectList = loadProjects ?: { projectRepository!!.value.loadProjectsOnce() }
    val refreshProjectList = refreshProjects ?: { projectRepository!!.value.refreshProjects() }
    var choices by remember { mutableStateOf(TaskEditorUpdateChoices(loading = true)) }
    var rescheduleRequested by remember { mutableStateOf(false) }
    val recovery = deadlineBlockerRecoveryState(
        task = task,
        expectedOccurrenceKey = occurrenceKey,
        rescheduleRequested = rescheduleRequested,
        snapshotConfirmed = snapshotConfirmed
    )

    fun retryProjects() {
        scope.launch {
            choices = choices.copy(loading = true, errorMessage = null)
            try {
                choices = TaskEditorUpdateChoices(
                    projects = refreshProjectList(),
                    membershipWasRefreshed = true
                )
            } catch (error: Exception) {
                AppLog.error(
                    "deadline_blocker projects refresh_failed type=${error::class.simpleName} message=${error.message}",
                    error
                )
                choices = choices.copy(
                    loading = false,
                    errorMessage = error.message ?: "Project loading failed"
                )
            }
        }
    }

    LaunchedEffect(recovery) {
        if (recovery !is DeadlineBlockerRecoveryState.Editor) return@LaunchedEffect
        choices = TaskEditorUpdateChoices(loading = true)
        try {
            choices = TaskEditorUpdateChoices(projects = loadProjectList())
        } catch (error: Exception) {
            AppLog.error(
                "deadline_blocker projects load_failed type=${error::class.simpleName} message=${error.message}",
                error
            )
            choices = TaskEditorUpdateChoices(errorMessage = error.message ?: "Project loading failed")
        }
    }

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
                deviceId = deviceId,
                choices = choices,
                onRetryChoices = ::retryProjects,
                onUpdate = onUpdate,
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
    state: app.buzer.android.ui.taskeditor.TaskEditorUpdateState,
    isDirty: Boolean
) = app.buzer.android.ui.taskeditor.taskEditorUpdateCancelRequest(state, isDirty)

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

/**
 * Uses the same assignee projection as the Update request so choice hydration cannot dirty a
 * pristine draft.
 */
internal fun deadlineBlockerDraftFingerprint(
    draft: TaskEditorDraft,
    choices: TaskEditorUpdateChoices,
    existingChildren: List<ChecklistDraftFingerprint>,
    deletedChildIds: Set<String>,
    addedChildren: List<ChecklistDraftFingerprint>
): EditTaskDraftFingerprint {
    return editTaskDraftFingerprint(
        title = draft.title,
        description = draft.description,
        projectId = draft.projectId,
        assigneeId = choices.assigneeIdForFingerprint(draft),
        start = app.buzer.android.ui.datetime.DateTimeDraftSelection(
            draft.schedule.date,
            draft.schedule.hour,
            draft.schedule.minute
        ),
        durationMinutes = draft.schedule.durationMinutes,
        recurrencePreset = draft.recurrencePresetForUpdate(),
        existingChildren = existingChildren,
        deletedChildIds = deletedChildIds,
        addedChildren = addedChildren
    )
}

@Composable
private fun DeadlineBlockerUpdateEditor(
    task: TaskSnapshotItem,
    deviceId: String,
    choices: TaskEditorUpdateChoices,
    onRetryChoices: () -> Unit,
    onUpdate: suspend (UpdateTaskRequest) -> CoordinatorActionResult,
    onSuccess: () -> Unit,
    onCancel: () -> Unit
) {
    var existingDrafts by remember(task.id) {
        mutableStateOf(task.checklistItems.sortedBy { it.order }.map(ChecklistItem::toEditorDraft))
    }
    var deletedChildren by remember(task.id) { mutableStateOf<Set<String>>(emptySet()) }
    var addedDrafts by remember(task.id) { mutableStateOf<List<ChecklistEditorDraft>>(emptyList()) }
    var capturedUpdate by remember(task.id) { mutableStateOf<EditTaskUpdateCapture?>(null) }
    val now = remember(task.id) { LocalDateTime.now() }
    val initialDraft = remember(task.id) { initialTaskEditorUpdateDraft(task) }
    val initialFingerprint = remember(task.id, choices) {
        deadlineBlockerDraftFingerprint(
            draft = initialDraft,
            choices = choices,
            existingChildren = task.checklistItems.sortedBy { it.order }.map {
                ChecklistDraftFingerprint(it.id, it.title, it.description, it.checked)
            },
            deletedChildIds = emptySet(),
            addedChildren = emptyList()
        )
    }
    val checklistMutationsAllowed = taskEditorUpdateChecklistAccess(task, hostEnabled = true).enabled

    fun currentFingerprint(draft: TaskEditorDraft): EditTaskDraftFingerprint {
        return deadlineBlockerDraftFingerprint(
            draft = draft,
            choices = choices,
            existingChildren = existingDrafts.map {
                ChecklistDraftFingerprint(it.id, it.title, it.description, it.checked)
            },
            deletedChildIds = deletedChildren,
            addedChildren = addedDrafts.map {
                ChecklistDraftFingerprint(it.clientMutationId, it.title, it.description, checked = false)
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TaskEditorUpdateScreen(
            task = task,
            choices = choices,
            presentation = deadlineBlockerUpdatePresentation,
            now = now,
            checklistDoneCount = existingDrafts.count { it.checked && it.id !in deletedChildren },
            checklistTotalCount = existingDrafts.count { it.id !in deletedChildren } + addedDrafts.size,
            checklistContent = { checklistEnabled, checklistError ->
                val checklistAccess = taskEditorUpdateChecklistAccess(task, checklistEnabled)
                checklistAccess.warningMessage?.let { WarningStatus(it) }
                checklistError?.let { Text(it) }
                ExistingChecklistEditor(
                    drafts = existingDrafts.filterNot { it.id in deletedChildren },
                    onDraftsChange = { existingDrafts = it },
                    onDelete = { childId -> deletedChildren = deletedChildren + childId },
                    enabled = checklistAccess.enabled
                )
                ChecklistEditor(
                    drafts = addedDrafts,
                    onDraftsChange = { addedDrafts = it },
                    nextClientMutationId = { UUID.randomUUID().toString() },
                    enabled = checklistAccess.enabled,
                    maxItems = (30 - existingDrafts.count { it.id !in deletedChildren }).coerceAtLeast(0)
                )
            },
            footerContent = { ActivityLogSection(task) },
            onRetryChoices = onRetryChoices,
            contentValidation = {
                val checklistValidation = validateChecklistDrafts(
                    if (checklistMutationsAllowed) addedDrafts else emptyList()
                )
                TaskEditorUpdateContentValidation(
                    canSubmit = checklistValidation.bannerMessage == null &&
                        checklistValidation.invalidDraftIds.isEmpty(),
                    message = checklistValidation.bannerMessage ?: "Each checklist item needs a title"
                        .takeIf { checklistValidation.invalidDraftIds.isNotEmpty() }
                )
            },
            isDirty = { draft -> currentFingerprint(draft) != initialFingerprint },
            onUpdate = update@{ draft ->
                val checklistValidation = validateChecklistDrafts(
                    if (checklistMutationsAllowed) addedDrafts else emptyList()
                )
                if (checklistValidation.bannerMessage != null || checklistValidation.invalidDraftIds.isNotEmpty()) {
                    return@update CoordinatorActionResult.Warning("validation_error", "Checklist items need titles", null)
                }
                val project = choices.selectedProject(draft)
                    ?: return@update CoordinatorActionResult.Warning("unavailable", "Project is unavailable", null)
                val fingerprint = currentFingerprint(draft)
                val nextCapture = captureEditTaskUpdate(capturedUpdate, fingerprint) {
                    updateRequest(
                        task = task,
                        title = draft.title,
                        description = draft.description,
                        project = project,
                        selectedAssignee = draft.assignee,
                        schedule = draft.schedule,
                        recurrencePreset = draft.recurrencePresetForUpdate(),
                        existingDrafts = existingDrafts,
                        deletedChildren = deletedChildren,
                        addedDrafts = if (checklistMutationsAllowed) addedDrafts else emptyList(),
                        deviceId = deviceId
                    )
                }
                capturedUpdate = nextCapture
                onUpdate(nextCapture.request)
            },
            onSuccess = onSuccess,
            onCancel = onCancel,
            modifier = Modifier.fillMaxSize()
        )
        if (choices.loading) InlineLoadingStatus("Loading projects...")
        choices.errorMessage?.let { message ->
            WarningStatus("Projects did not load: $message", onRetry = onRetryChoices)
        }
    }
}
