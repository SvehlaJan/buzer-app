package app.buzer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.tasks.AddTaskDependencies
import app.buzer.android.ui.components.BuzerPanel
import app.buzer.android.ui.components.ChecklistEditor
import app.buzer.android.ui.components.TaskDetailsBlock
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.status.BlockingErrorState
import app.buzer.android.ui.status.BlockingLoadingState
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
import app.buzer.android.ui.taskeditor.taskEditorUpdateChecklistAccess
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.ui.taskeditor.ChecklistEditorDraft
import app.buzer.shared.ui.taskeditor.validateChecklistDrafts
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.launch

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

data class MorningReviewReadyState(
    val headerText: String,
    val headerProgressText: String?,
    val warning: String?,
    val refreshing: Boolean,
    val cards: List<MorningReviewTaskCardState>,
    val actionLabels: List<String>
)

data class MorningReviewTaskCardState(
    val task: TaskSnapshotItem,
    val title: String,
    val problemText: String,
    val description: String?,
    val repairActionLabel: String = "Repair",
    val snoozeActionLabel: String = "Snooze",
    val progressText: String? = null
) {
    fun allText(): String =
        listOfNotNull(title, problemText, description, repairActionLabel, snoozeActionLabel, progressText)
            .joinToString(" ")
}

internal val morningReviewRepairPresentation = TaskEditorPresentation(
    screenTitle = "Repair task",
    primaryVerb = "Repair",
    showUpdateFooter = true
)

internal data class MorningReviewRepairCompletion(
    val remainingInvalidTasks: List<TaskSnapshotItem>,
    val shouldFinish: Boolean
)

internal fun completeMorningReviewRepair(
    invalidTasks: List<TaskSnapshotItem>,
    repairedTaskId: String
): MorningReviewRepairCompletion {
    val remainingInvalidTasks = invalidTasks.filterNot { it.id == repairedTaskId }
    return MorningReviewRepairCompletion(
        remainingInvalidTasks = remainingInvalidTasks,
        shouldFinish = remainingInvalidTasks.isEmpty()
    )
}

fun buildMorningReviewReadyState(
    invalidTasks: List<TaskSnapshotItem>,
    warning: String?,
    refreshing: Boolean
): MorningReviewReadyState {
    val count = invalidTasks.size
    return MorningReviewReadyState(
        headerText = if (count == 0) {
            "All commitments are valid"
        } else {
            "$count ${if (count == 1) "task needs" else "tasks need"} attention"
        },
        headerProgressText = count.takeIf { it > 0 }?.let { "$it unresolved" },
        warning = warning,
        refreshing = refreshing,
        cards = invalidTasks.map { task ->
            MorningReviewTaskCardState(
                task = task,
                title = task.title,
                problemText = plainLanguageProblem(task.validationErrors),
                description = task.description?.trim()?.takeIf { it.isNotEmpty() }
            )
        },
        actionLabels = if (count == 0) listOf("Done") else emptyList()
    )
}

private fun plainLanguageProblem(validationErrors: List<String>): String {
    val errors = validationErrors.toSet()
    val missingDueDatetime = "missing_due_datetime" in errors || "due_datetime_required" in errors
    val missingDuration = "missing_duration" in errors || "duration_required" in errors
    return when {
        missingDueDatetime && missingDuration -> "No start time or duration set"
        missingDueDatetime -> "No start time set"
        missingDuration -> "No duration set"
        validationErrors.isEmpty() -> "Commitment details need repair"
        else -> validationErrors.joinToString(", ") { it.replace('_', ' ') }
    }
}

@Composable
fun MorningReviewScreen(
    invalidTasks: List<TaskSnapshotItem> = emptyList(),
    onBack: () -> Unit,
    onRepair: suspend (String, UpdateTaskRequest) -> CoordinatorActionResult = { _, _ ->
        CoordinatorActionResult.FailedAndHidden("Repair is unavailable")
    },
    onRepairSuccess: (String) -> Unit = {},
    onSnooze: () -> Unit = {},
    showBack: Boolean = true,
    contentState: MorningReviewContentState = MorningReviewContentState.Ready(),
    snoozing: Boolean = false,
    actionErrorMessage: String? = null,
    onDismissActionError: () -> Unit = {},
    onRetryAction: () -> Unit = {},
    onRetryRefresh: () -> Unit = {},
    loadProjects: (suspend () -> List<ProjectItem>)? = null,
    refreshProjects: (suspend () -> List<ProjectItem>)? = null
) {
    val context = LocalContext.current
    val deviceId = remember(context) {
        EnforcementDependencies.deviceIdentityStore(context.applicationContext).deviceId()
    }
    val scope = rememberCoroutineScope()
    val projectRepository = remember(loadProjects, refreshProjects) {
        if (loadProjects == null || refreshProjects == null) {
            AddTaskDependencies.projectRepository()
        } else {
            null
        }
    }
    val loadProjectList = loadProjects ?: projectRepository!!::loadProjectsOnce
    val refreshProjectList = refreshProjects ?: projectRepository!!::refreshProjects
    var choices by remember { mutableStateOf(TaskEditorUpdateChoices(loading = true)) }
    var repairingTask by remember { mutableStateOf<TaskSnapshotItem?>(null) }

    fun retryProjects() {
        scope.launch {
            choices = choices.copy(loading = true, errorMessage = null)
            try {
                choices = TaskEditorUpdateChoices(
                    projects = refreshProjectList(),
                    membershipWasRefreshed = true
                )
            } catch (error: Exception) {
                AppLog.error("morning_review projects refresh_failed type=${error::class.simpleName} message=${error.message}", error)
                choices = choices.copy(
                    loading = false,
                    errorMessage = error.message ?: "Project loading failed"
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        choices = TaskEditorUpdateChoices(loading = true)
        try {
            choices = TaskEditorUpdateChoices(projects = loadProjectList())
        } catch (error: Exception) {
            AppLog.error("morning_review projects load_failed type=${error::class.simpleName} message=${error.message}", error)
            choices = TaskEditorUpdateChoices(errorMessage = error.message ?: "Project loading failed")
        }
    }

    // A successful repair updates the authoritative invalid-task list in the hosting activity.
    // Do not leave an editor open for a task that no longer requires repair.
    LaunchedEffect(invalidTasks) {
        if (repairingTask != null && invalidTasks.none { it.id == repairingTask?.id }) {
            repairingTask = null
        }
    }

    repairingTask?.let { task ->
        MorningReviewRepairEditor(
            task = task,
            deviceId = deviceId,
            choices = choices,
            onRetryChoices = ::retryProjects,
            onRepair = onRepair,
            onSuccess = { onRepairSuccess(task.id) },
            onCancel = { repairingTask = null }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
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
                val readyState = buildMorningReviewReadyState(
                    invalidTasks = invalidTasks,
                    warning = state.warning,
                    refreshing = state.refreshing
                )
                MorningReviewHeader(
                    state = readyState,
                    onRetryRefresh = onRetryRefresh
                )
            }
        }

        MorningReviewTaskList(
            invalidTasks = invalidTasks,
            onRepair = { repairingTask = it },
            onSnooze = onSnooze,
            onBack = onBack,
            showBack = showBack,
            snoozing = snoozing,
            contentState = contentState
        )
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
internal fun ActivityLogSection(task: TaskSnapshotItem) {
    if (task.activityLogEntries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.fillMaxWidth()) {
        Text("Activity", style = MaterialTheme.typography.labelLarge)
        task.activityLogEntries.forEach { entry ->
            Text("${entry.timestamp}: ${entry.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MorningReviewRepairEditor(
    task: TaskSnapshotItem,
    deviceId: String,
    choices: TaskEditorUpdateChoices,
    onRetryChoices: () -> Unit,
    onRepair: suspend (String, UpdateTaskRequest) -> CoordinatorActionResult,
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
    val initialFingerprint = remember(task.id) {
        editTaskDraftFingerprint(
            title = initialDraft.title,
            description = initialDraft.description,
            projectId = task.projectId,
            assigneeId = task.assigneeId,
            start = DateTimeDraftSelection(
                initialDraft.schedule.date,
                initialDraft.schedule.hour,
                initialDraft.schedule.minute
            ),
            durationMinutes = initialDraft.schedule.durationMinutes,
            recurrencePreset = task.recurrence.preset,
            existingChildren = task.checklistItems.sortedBy { it.order }.map {
                ChecklistDraftFingerprint(it.id, it.title, it.description, it.checked)
            },
            deletedChildIds = emptySet(),
            addedChildren = emptyList()
        )
    }
    val checklistMutationsAllowed = taskEditorUpdateChecklistAccess(task, hostEnabled = true).enabled

    fun currentFingerprint(draft: TaskEditorDraft): EditTaskDraftFingerprint {
        val selectedProject = choices.selectedProject(draft)
        val assigneeId = draft.assignee?.id?.takeIf {
            selectedProject?.canAssignTasks == true && it.isNotBlank()
        } ?: draft.assignee?.id?.takeIf { selectedProject == null && it.isNotBlank() }
        return editTaskDraftFingerprint(
            title = draft.title,
            description = draft.description,
            projectId = draft.projectId,
            assigneeId = assigneeId,
            start = DateTimeDraftSelection(draft.schedule.date, draft.schedule.hour, draft.schedule.minute),
            durationMinutes = draft.schedule.durationMinutes,
            recurrencePreset = draft.recurrencePreset.takeUnless {
                draft.recurrenceKind == app.buzer.shared.model.RecurrenceKind.custom &&
                    !draft.repeatReplacementSelected
            },
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
            presentation = morningReviewRepairPresentation,
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
                    return@update CoordinatorActionResult.FailedAndHidden("Checklist items need titles")
                }
                val project = choices.selectedProject(draft)
                    ?: return@update CoordinatorActionResult.FailedAndHidden("Project is unavailable")
                val fingerprint = currentFingerprint(draft)
                val nextCapture = captureEditTaskUpdate(capturedUpdate, fingerprint) {
                    updateRequest(
                        task = task,
                        title = draft.title,
                        description = draft.description,
                        project = project,
                        selectedAssignee = draft.assignee,
                        schedule = draft.schedule,
                        recurrencePreset = draft.recurrencePreset.takeUnless {
                            draft.recurrenceKind == app.buzer.shared.model.RecurrenceKind.custom &&
                                !draft.repeatReplacementSelected
                        },
                        existingDrafts = existingDrafts,
                        deletedChildren = deletedChildren,
                        addedDrafts = if (checklistMutationsAllowed) addedDrafts else emptyList(),
                        deviceId = deviceId
                    )
                }
                capturedUpdate = nextCapture
                onRepair(task.id, nextCapture.request)
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

@Composable
private fun MorningReviewHeader(
    state: MorningReviewReadyState,
    onRetryRefresh: () -> Unit
) {
    BuzerPanel {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = state.headerText,
                style = MaterialTheme.typography.headlineSmall
            )
            state.headerProgressText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (state.refreshing) InlineLoadingStatus("Refreshing...")
            state.warning?.let { WarningStatus(it, onRetry = onRetryRefresh) }
        }
    }
}

@Composable
private fun MorningReviewTaskList(
    invalidTasks: List<TaskSnapshotItem>,
    onRepair: (TaskSnapshotItem) -> Unit,
    onSnooze: () -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    snoozing: Boolean,
    contentState: MorningReviewContentState
) {
    val readyState = (contentState as? MorningReviewContentState.Ready)?.let {
        buildMorningReviewReadyState(
            invalidTasks = invalidTasks,
            warning = it.warning,
            refreshing = it.refreshing
        )
    } ?: buildMorningReviewReadyState(invalidTasks, warning = null, refreshing = false)

    if (readyState.cards.isEmpty()) {
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Done")
        }
    } else {
        readyState.cards.forEach { card ->
            MorningReviewRepairCard(
                state = card,
                onRepair = { onRepair(card.task) },
                onSnooze = onSnooze,
                snoozing = snoozing
            )
        }
    }

    if (showBack) {
        TextButton(
            onClick = onBack,
            enabled = !snoozing
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun MorningReviewRepairCard(
    state: MorningReviewTaskCardState,
    onRepair: () -> Unit,
    onSnooze: () -> Unit,
    snoozing: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = state.problemText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TaskDetailsBlock(description = state.description)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                LoadingActionButton(
                    label = state.repairActionLabel,
                    loadingLabel = "Repairing...",
                    loading = false,
                    onClick = onRepair,
                    enabled = !snoozing,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("morning-repair-${state.task.id}")
                )
                TextButton(
                    onClick = onSnooze,
                    enabled = !snoozing,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("morning-snooze-${state.task.id}")
                ) {
                    Text(state.snoozeActionLabel)
                }
            }
        }
    }
}
