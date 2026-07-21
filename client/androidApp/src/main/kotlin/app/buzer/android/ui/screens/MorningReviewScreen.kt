package app.buzer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.tasks.AddTaskDependencies
import app.buzer.android.ui.addtask.AddTaskInvalidField
import app.buzer.android.ui.components.BuzerPanel
import app.buzer.android.ui.components.CommitmentEditor
import app.buzer.android.ui.components.ChecklistEditor
import app.buzer.android.ui.components.TaskDetailsBlock
import app.buzer.android.ui.components.commitmentSaveLabel
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.status.BlockingErrorState
import app.buzer.android.ui.status.BlockingLoadingState
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.status.LoadingActionButton
import app.buzer.android.ui.status.WarningStatus
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.NullablePatch
import app.buzer.shared.model.RootTaskPatch
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.model.ChecklistDraft
import app.buzer.shared.model.ChecklistMutation
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.ui.commitment.CommitmentEditorMode
import app.buzer.shared.ui.commitment.CommitmentEditorValidation
import app.buzer.shared.ui.commitment.ChecklistEditorDraft
import app.buzer.shared.ui.commitment.validateChecklistDrafts
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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

data class MorningReviewRepairEditorState(
    val mode: CommitmentEditorMode,
    val titleEditable: Boolean,
    val title: String,
    val description: String,
    val projectLabel: String?,
    val assigneeLabel: String?,
    val start: DateTimeDraftSelection,
    val durationMinutes: Int,
    val submitLabel: String
)

data class MorningReviewRepairValidation(
    val bannerMessage: String?,
    val titleError: String?,
    val projectError: String?,
    val assigneeError: String?,
    val startError: String?,
    val firstInvalidField: AddTaskInvalidField?
) {
    fun asCommitmentEditorValidation(): CommitmentEditorValidation =
        CommitmentEditorValidation(
            bannerMessage = bannerMessage,
            titleError = titleError,
            projectError = projectError,
            assigneeError = assigneeError,
            startError = startError,
            durationError = null
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

fun buildMorningReviewRepairEditorState(
    task: TaskSnapshotItem,
    start: DateTimeDraftSelection,
    durationMinutes: Int,
    projects: List<ProjectItem> = emptyList(),
    assigneeOptions: List<ProjectCollaborator> = emptyList()
): MorningReviewRepairEditorState =
    MorningReviewRepairEditorState(
        mode = CommitmentEditorMode.Repair,
        titleEditable = true,
        title = task.title,
        description = task.description.orEmpty(),
        projectLabel = projects.firstOrNull { it.id == task.projectId }?.name ?: task.projectId,
        assigneeLabel = (
            projects.firstOrNull { it.id == task.projectId }?.collaborators?.ifEmpty { assigneeOptions }
                ?: assigneeOptions
            ).firstOrNull { it.id == task.assigneeId }?.displayName ?: task.assigneeId,
        start = start,
        durationMinutes = durationMinutes,
        submitLabel = commitmentSaveLabel(start.toSelection(), durationMinutes)
    )

fun buildMorningReviewRepairValidation(
    title: String,
    selectedProject: ProjectItem?,
    selectedAssignee: ProjectCollaborator?,
    start: DateTimeDraftSelection
): MorningReviewRepairValidation {
    val invalidField = when {
        title.trim().isEmpty() -> AddTaskInvalidField.Title
        selectedProject == null -> AddTaskInvalidField.Project
        selectedProject.canAssignTasks && selectedAssignee == null -> AddTaskInvalidField.Assignee
        start.toSelection() == null -> AddTaskInvalidField.Start
        else -> null
    }
    val message = when (invalidField) {
        AddTaskInvalidField.Title -> "Title is required"
        AddTaskInvalidField.Project -> "Project is required"
        AddTaskInvalidField.Assignee -> "Assignee is required before saving"
        AddTaskInvalidField.Start -> "Start time is required"
        AddTaskInvalidField.Duration,
        null -> null
    }
    return MorningReviewRepairValidation(
        bannerMessage = message,
        titleError = "Required".takeIf { invalidField == AddTaskInvalidField.Title },
        projectError = "Required".takeIf { invalidField == AddTaskInvalidField.Project },
        assigneeError = "Required".takeIf { invalidField == AddTaskInvalidField.Assignee },
        startError = "Required".takeIf { invalidField == AddTaskInvalidField.Start },
        firstInvalidField = invalidField
    )
}

internal fun updateTaskRequestFromEditorStart(
    actionId: String,
    deviceId: String,
    expectedOccurrenceKey: String,
    expectedTaskRevision: String,
    title: String,
    description: String,
    selectedProject: ProjectItem,
    selectedAssignee: ProjectCollaborator?,
    start: DateTimeSelection,
    durationMinutes: Int,
    recurrencePreset: RecurrencePreset? = null,
    existingChecklistItems: List<ChecklistItem> = emptyList(),
    existingDrafts: List<ExistingChecklistDraft> = emptyList(),
    deletedChildIds: Set<String> = emptySet(),
    checklistDrafts: List<ChecklistEditorDraft> = emptyList(),
    zoneId: ZoneId = ZoneId.systemDefault()
): UpdateTaskRequest {
    val dueDatetime = start.date.atTime(start.hour, start.minute)
        .atZone(zoneId)
        .toInstant()
        .toString()
    return UpdateTaskRequest(
        actionId = actionId,
        deviceId = deviceId,
        expectedOccurrenceKey = expectedOccurrenceKey,
        rootPatch = RootTaskPatch(
            expectedTaskRevision = expectedTaskRevision,
            title = title.trim(),
            description = NullablePatch.Value(description.trim().takeIf { it.isNotEmpty() }),
            projectId = selectedProject.id,
            assigneeId = NullablePatch.Value(
                selectedAssignee?.id?.takeIf { selectedProject.canAssignTasks }
            ),
            dueDatetime = NullablePatch.Value(dueDatetime),
            durationMinutes = NullablePatch.Value(durationMinutes),
            recurrencePreset = recurrencePreset,
            timeZone = zoneId.id
        ),
        childMutations = existingChildMutations(
            existingChecklistItems = existingChecklistItems,
            existingDrafts = existingDrafts,
            deletedChildIds = deletedChildIds
        ) + checklistDrafts.map { draft ->
            ChecklistMutation.Add(draft.clientMutationId, draft.title.trim(), draft.description)
        }
    )
}

private fun existingChildMutations(
    existingChecklistItems: List<ChecklistItem>,
    existingDrafts: List<ExistingChecklistDraft>,
    deletedChildIds: Set<String>
): List<ChecklistMutation> {
    val originalById = existingChecklistItems.associateBy { it.id }
    val changes = existingDrafts.filterNot { it.id in deletedChildIds }.flatMap { draft ->
        val original = originalById.getValue(draft.id)
        buildList {
            if (draft.title.trim() != original.title || draft.description != original.description) {
                add(ChecklistMutation.Edit(UUID.randomUUID().toString(), draft.id, draft.childRevision, draft.title.trim(), draft.description))
            }
            if (draft.checked != original.checked) {
                add(if (draft.checked) ChecklistMutation.Check(UUID.randomUUID().toString(), draft.id, draft.childRevision)
                else ChecklistMutation.Uncheck(UUID.randomUUID().toString(), draft.id, draft.childRevision))
            }
        }
    }
    return changes + existingChecklistItems.filter { it.id in deletedChildIds }.map {
        ChecklistMutation.Delete(UUID.randomUUID().toString(), it.id, it.childRevision)
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningReviewScreen(
    invalidTasks: List<TaskSnapshotItem> = emptyList(),
    onBack: () -> Unit,
    onRepair: (String, UpdateTaskRequest) -> Unit = { _, _ -> },
    onSnooze: () -> Unit = {},
    showBack: Boolean = true,
    contentState: MorningReviewContentState = MorningReviewContentState.Ready(),
    repairing: Boolean = false,
    snoozing: Boolean = false,
    actionErrorMessage: String? = null,
    onDismissActionError: () -> Unit = {},
    onRetryAction: () -> Unit = {},
    onRetryRefresh: () -> Unit = {},
    assigneeOptions: List<ProjectCollaborator> = emptyList(),
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
    var projects by remember { mutableStateOf<List<ProjectItem>>(emptyList()) }
    var projectsLoading by remember { mutableStateOf(true) }
    var projectLoadError by remember { mutableStateOf<String?>(null) }
    var repairingTask by remember { mutableStateOf<TaskSnapshotItem?>(null) }
    var title by remember(repairingTask?.id) {
        mutableStateOf(repairingTask?.title.orEmpty())
    }
    var selectedProject by remember(repairingTask?.id) {
        mutableStateOf<ProjectItem?>(null)
    }
    var projectMenuExpanded by remember(repairingTask?.id) {
        mutableStateOf(false)
    }
    var selectedAssignee by remember(repairingTask?.id) {
        mutableStateOf<ProjectCollaborator?>(null)
    }
    var start by remember(repairingTask?.id) {
        mutableStateOf(repairingTask?.initialRepairStartDraft() ?: DateTimeDraftSelection.Empty)
    }
    var durationMinutes by remember(repairingTask?.id) {
        mutableStateOf(repairingTask?.durationMinutes ?: 30)
    }
    var description by remember(repairingTask?.id) {
        mutableStateOf(repairingTask?.description.orEmpty())
    }
    var descriptionExpanded by remember(repairingTask?.id) {
        mutableStateOf(false)
    }
    var recurrencePreset by remember(repairingTask?.id) {
        mutableStateOf(repairingTask?.recurrence?.preset)
    }
    var checklistDrafts by remember(repairingTask?.id) { mutableStateOf<List<ChecklistEditorDraft>>(emptyList()) }
    var existingChecklistDrafts by remember(repairingTask?.id) {
        mutableStateOf(repairingTask?.checklistItems?.sortedBy { it.order }?.map(ChecklistItem::toEditorDraft).orEmpty())
    }
    var deletedChecklistChildIds by remember(repairingTask?.id) { mutableStateOf<Set<String>>(emptySet()) }
    var errorText by remember(repairingTask?.id) { mutableStateOf<String?>(null) }

    fun retryProjects() {
        scope.launch {
            projectsLoading = true
            projectLoadError = null
            try {
                projects = refreshProjectList()
            } catch (error: Exception) {
                AppLog.error("morning_review projects refresh_failed type=${error::class.simpleName} message=${error.message}", error)
                projectLoadError = error.message ?: "Project loading failed"
            } finally {
                projectsLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        projectsLoading = true
        projectLoadError = null
        try {
            projects = loadProjectList()
        } catch (error: Exception) {
            AppLog.error("morning_review projects load_failed type=${error::class.simpleName} message=${error.message}", error)
            projectLoadError = error.message ?: "Project loading failed"
        } finally {
            projectsLoading = false
        }
    }

    LaunchedEffect(repairingTask?.id, projects) {
        val task = repairingTask ?: return@LaunchedEffect
        title = task.title
        selectedProject = projects.firstOrNull { it.id == task.projectId }
        selectedAssignee = selectedProject?.collaborators?.firstOrNull { it.id == task.assigneeId }
            ?: assigneeOptions.firstOrNull { it.id == task.assigneeId }
    }

    // A successful repair updates the authoritative invalid-task list in the hosting activity.
    // Do not leave an editor open for a task that no longer requires repair.
    LaunchedEffect(invalidTasks) {
        if (repairingTask != null && invalidTasks.none { it.id == repairingTask?.id }) {
            repairingTask = null
            errorText = null
        }
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

        val task = repairingTask
        if (task == null) {
            MorningReviewTaskList(
                invalidTasks = invalidTasks,
                onRepair = { repairingTask = it },
                onSnooze = onSnooze,
                onBack = onBack,
                showBack = showBack,
                snoozing = snoozing,
                contentState = contentState
            )
        } else {
            val customRecurrence = task.recurrence.kind == RecurrenceKind.custom
            projectLoadError?.let { WarningStatus("Projects did not load: $it", onRetry = { retryProjects() }) }
            if (task.hierarchyStatus == HierarchyStatus.unsupported) {
                WarningStatus(task.hierarchyViolation ?: "This task has unsupported nested checklist items")
            }
            val editorState = buildMorningReviewRepairEditorState(
                task = task,
                start = start,
                durationMinutes = durationMinutes,
                projects = projects,
                assigneeOptions = assigneeOptions
            )
            CommitmentEditor(
                mode = editorState.mode,
                title = title,
                onTitleChange = {
                    title = it
                    errorText = null
                },
                titleEditable = editorState.titleEditable,
                description = description,
                onDescriptionChange = {
                    description = it
                    errorText = null
                },
                descriptionExpanded = descriptionExpanded,
                onDescriptionExpandedChange = { descriptionExpanded = it },
                projectContent = {
                    ProjectSelector(
                        selectedProject = selectedProject,
                        projects = projects,
                        projectsLoading = projectsLoading,
                        projectMenuExpanded = projectMenuExpanded,
                        onProjectMenuExpandedChange = { projectMenuExpanded = it },
                        onProjectSelected = { project ->
                            selectedProject = project
                            selectedAssignee = project.collaborators.firstOrNull {
                                it.isCurrentUser && !project.canAssignTasks
                            }
                            projectMenuExpanded = false
                            errorText = null
                        },
                        enabled = !repairing && !snoozing && !projectsLoading && projects.isNotEmpty()
                    )
                },
                assigneeContent = {
                    AssigneeSelector(
                        selectedProject = selectedProject,
                        assigneeOptions = selectedProject?.collaborators?.ifEmpty { assigneeOptions } ?: assigneeOptions,
                        selectedAssignee = selectedAssignee,
                        onAssigneeSelected = { option ->
                            selectedAssignee = option
                            errorText = null
                        },
                        enabled = !repairing && !snoozing
                    )
                },
                start = start,
                onStartChange = {
                    start = it
                    errorText = null
                },
                durationMinutes = durationMinutes,
                onDurationChange = {
                    durationMinutes = it
                    errorText = null
                },
                recurrencePreset = recurrencePreset,
                onRecurrencePresetChange = { recurrencePreset = it; errorText = null },
                checklistContent = {
                    if (customRecurrence && recurrencePreset == null) {
                        Text("Custom recurrence: ${task.recurrence.rule ?: task.recurrence.displayLabel}")
                        Text("Choose a numbered option only to replace it.")
                    }
                    ExistingChecklistEditor(
                        drafts = existingChecklistDrafts.filterNot { it.id in deletedChecklistChildIds },
                        onDraftsChange = { existingChecklistDrafts = it; errorText = null },
                        onDelete = { childId ->
                            deletedChecklistChildIds = deletedChecklistChildIds + childId
                            errorText = null
                        },
                        enabled = !repairing && !snoozing && task.hierarchyStatus == HierarchyStatus.valid
                    )
                    ChecklistEditor(
                        drafts = checklistDrafts,
                        onDraftsChange = { checklistDrafts = it; errorText = null },
                        nextClientMutationId = { UUID.randomUUID().toString() },
                        enabled = !repairing && !snoozing && task.hierarchyStatus == HierarchyStatus.valid,
                        maxItems = (30 - existingChecklistDrafts.count { it.id !in deletedChecklistChildIds }).coerceAtLeast(0)
                    )
                },
                validation = buildMorningReviewRepairValidation(
                    title = title,
                    selectedProject = selectedProject,
                    selectedAssignee = selectedAssignee,
                    start = start
                ).let { validation ->
                    validation.asCommitmentEditorValidation().copy(
                        bannerMessage = errorText ?: validation.bannerMessage
                    )
                },
                submitting = repairing,
                enabled = !repairing && !snoozing,
                submitEnabled = !repairing && !snoozing && !projectsLoading && projectLoadError == null,
                submitLabel = editorState.submitLabel,
                loadingLabel = "Saving...",
                onSubmit = {
                    val selectedStart = start.toSelection()
                    val project = selectedProject
                    val validation = buildMorningReviewRepairValidation(
                        title = title,
                        selectedProject = project,
                        selectedAssignee = selectedAssignee,
                        start = start
                    )
                    if (validation.firstInvalidField != null) {
                        errorText = validation.bannerMessage
                    } else if (validateChecklistDrafts(checklistDrafts).let { it.bannerMessage != null || it.invalidDraftIds.isNotEmpty() }) {
                        errorText = "Each checklist item needs a title (maximum 30)"
                    } else if (project != null && selectedStart != null) {
                        onRepair(
                            task.id,
                            updateTaskRequestFromEditorStart(
                                actionId = UUID.randomUUID().toString(),
                                deviceId = deviceId,
                                expectedOccurrenceKey = task.occurrenceKey,
                                expectedTaskRevision = task.taskRevision,
                                title = title,
                                description = description,
                                selectedProject = project,
                                selectedAssignee = selectedAssignee,
                                start = selectedStart,
                                durationMinutes = durationMinutes,
                                recurrencePreset = when {
                                    task.recurrence.kind == RecurrenceKind.custom -> recurrencePreset
                                    recurrencePreset != task.recurrence.preset -> recurrencePreset
                                    else -> null
                                },
                                existingChecklistItems = task.checklistItems,
                                existingDrafts = existingChecklistDrafts,
                                deletedChildIds = deletedChecklistChildIds,
                                checklistDrafts = checklistDrafts
                            )
                        )
                    }
                },
                onCancel = {
                    repairingTask = null
                    errorText = null
                },
                modifier = Modifier.fillMaxWidth()
            )
            ActivityLogSection(task)
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

@Composable
private fun ReceiptText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

fun TaskSnapshotItem.initialRepairStartDraft(): DateTimeDraftSelection {
    val instantText = startAt ?: dueDatetime
    return instantText?.toDateTimeDraftSelection()
        ?: dueDate?.toDateTimeDraftSelection()
        ?: DateTimeDraftSelection.Empty
}

private fun String.toDateTimeDraftSelection(): DateTimeDraftSelection {
    runCatching {
        val local = Instant.parse(this).atZone(ZoneId.systemDefault()).toLocalDateTime()
        return DateTimeDraftSelection(
            date = local.toLocalDate(),
            hour = local.hour,
            minute = local.minute
        )
    }
    runCatching {
        val local = LocalDateTime.parse(this)
        return DateTimeDraftSelection(
            date = local.toLocalDate(),
            hour = local.hour,
            minute = local.minute
        )
    }
    runCatching {
        return DateTimeDraftSelection(
            date = LocalDate.parse(this),
            hour = null,
            minute = null
        )
    }
    return DateTimeDraftSelection.Empty
}
