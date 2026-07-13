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
import app.buzer.android.tasks.AddTaskDependencies
import app.buzer.android.ui.addtask.AddTaskInvalidField
import app.buzer.android.ui.components.BuzerPanel
import app.buzer.android.ui.components.CommitmentEditor
import app.buzer.android.ui.components.TaskDetailsBlock
import app.buzer.android.ui.components.commitmentSaveLabel
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.addtask.AddTaskAssigneeOption
import app.buzer.android.ui.status.BlockingErrorState
import app.buzer.android.ui.status.BlockingLoadingState
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.status.LoadingActionButton
import app.buzer.android.ui.status.WarningStatus
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.ui.commitment.CommitmentEditorMode
import app.buzer.shared.ui.commitment.CommitmentEditorValidation
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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
    assigneeOptions: List<AddTaskAssigneeOption> = emptyList()
): MorningReviewRepairEditorState =
    MorningReviewRepairEditorState(
        mode = CommitmentEditorMode.Repair,
        titleEditable = true,
        title = task.title,
        description = task.description.orEmpty(),
        projectLabel = projects.firstOrNull { it.id == task.projectId }?.name ?: task.projectId,
        assigneeLabel = assigneeOptions.firstOrNull { it.id == task.assigneeId }?.label ?: task.assigneeId,
        start = start,
        durationMinutes = durationMinutes,
        submitLabel = commitmentSaveLabel(start.toSelection(), durationMinutes)
    )

fun buildMorningReviewRepairValidation(
    title: String,
    selectedProject: ProjectItem?,
    selectedAssignee: AddTaskAssigneeOption?,
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

fun updateTaskRequestFromEditorStart(
    title: String,
    description: String,
    selectedProject: ProjectItem,
    selectedAssignee: AddTaskAssigneeOption?,
    start: DateTimeSelection,
    durationMinutes: Int,
    zoneId: ZoneId = ZoneId.systemDefault()
): UpdateTaskRequest {
    val dueDatetime = start.date.atTime(start.hour, start.minute)
        .atZone(zoneId)
        .toInstant()
        .toString()
    return UpdateTaskRequest(
        title = title.trim(),
        description = description.trim().takeIf { it.isNotEmpty() },
        projectId = selectedProject.id,
        assigneeId = selectedAssignee?.id?.takeIf { selectedProject.canAssignTasks },
        dueDatetime = dueDatetime,
        durationMinutes = durationMinutes
    )
}

private fun plainLanguageProblem(validationErrors: List<String>): String {
    val errors = validationErrors.toSet()
    return when {
        "missing_due_datetime" in errors && "missing_duration" in errors -> "No start time or duration set"
        "missing_due_datetime" in errors -> "No start time set"
        "missing_duration" in errors -> "No duration set"
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
    assigneeOptions: List<AddTaskAssigneeOption> = AddTaskDependencies.assigneeOptions(),
    loadProjects: (suspend () -> List<ProjectItem>)? = null,
    refreshProjects: (suspend () -> List<ProjectItem>)? = null
) {
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
        mutableStateOf<AddTaskAssigneeOption?>(null)
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
        selectedAssignee = assigneeOptions.firstOrNull { it.id == task.assigneeId }
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
            projectLoadError?.let { WarningStatus("Projects did not load: $it", onRetry = { retryProjects() }) }
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
                            selectedAssignee = null
                            projectMenuExpanded = false
                            errorText = null
                        },
                        enabled = !repairing && !snoozing && !projectsLoading && projects.isNotEmpty()
                    )
                },
                assigneeContent = {
                    AssigneeSelector(
                        selectedProject = selectedProject,
                        assigneeOptions = assigneeOptions,
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
                    } else if (project != null && selectedStart != null) {
                        onRepair(
                            task.id,
                            updateTaskRequestFromEditorStart(
                                title = title,
                                description = description,
                                selectedProject = project,
                                selectedAssignee = selectedAssignee,
                                start = selectedStart,
                                durationMinutes = durationMinutes
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
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onSnooze,
                    enabled = !snoozing,
                    modifier = Modifier.weight(1f)
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
