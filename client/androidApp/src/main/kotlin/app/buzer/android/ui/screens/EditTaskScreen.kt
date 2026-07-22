package app.buzer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.tasks.AddTaskDependencies
import app.buzer.android.ui.addtask.AddTaskFormResult
import app.buzer.android.ui.addtask.AddTaskFormValidator
import app.buzer.android.ui.addtask.AddTaskInvalidField
import app.buzer.android.ui.addtask.AddTaskProjectLoadFeedback
import app.buzer.android.ui.addtask.selectAddTaskProject
import app.buzer.android.ui.components.ChecklistEditor
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.taskeditor.TaskEditorDraft
import app.buzer.android.ui.taskeditor.TaskEditorSchedule
import app.buzer.android.ui.taskeditor.TaskEditorScaffold
import app.buzer.android.ui.taskeditor.TaskEditorScheduleValidation
import app.buzer.android.ui.taskeditor.recurrenceLabel
import app.buzer.android.ui.taskeditor.setNoSchedule
import app.buzer.android.ui.taskeditor.validateScheduleForSave
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.ChecklistMutation
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.NullablePatch
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.RootTaskPatch
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.ui.commitment.ChecklistEditorDraft
import app.buzer.shared.ui.commitment.validateChecklistDrafts
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.launch

internal data class ExistingChecklistDraft(
    val id: String,
    val childRevision: String,
    val title: String,
    val description: String?,
    val checked: Boolean
)

internal fun ChecklistItem.toEditorDraft() = ExistingChecklistDraft(
    id = id,
    childRevision = childRevision,
    title = title,
    description = description,
    checked = checked
)

internal fun initialEditTaskDraft(task: TaskSnapshotItem): TaskEditorDraft {
    val schedule = task.dueDatetime?.toTaskEditorSchedule()
        ?: task.dueDate?.toTaskEditorDateSchedule()
        ?: TaskEditorSchedule()
    val fallbackAssignee = task.assigneeId?.let { assigneeId ->
        ProjectCollaborator(
            id = assigneeId,
            displayName = if (task.isAssignedToUser) "Me" else assigneeId,
            isCurrentUser = task.isAssignedToUser
        )
    } ?: ProjectCollaborator(
        id = "",
        displayName = "Me",
        isCurrentUser = task.isAssignedToUser
    )
    return TaskEditorDraft(
        title = task.title,
        description = task.description.orEmpty(),
        projectId = task.projectId,
        assignee = fallbackAssignee,
        schedule = schedule.copy(durationMinutes = task.durationMinutes),
        recurrencePreset = task.recurrence.preset ?: RecurrencePreset.never
    )
}

/**
 * Cached projects populate display values but never override the task snapshot's assignee.
 * A refreshed ready membership is authoritative enough to apply the normal project rule.
 */
internal fun reconcileEditProjectMembership(
    draft: TaskEditorDraft,
    projects: List<ProjectItem>,
    currentUser: ProjectCollaborator,
    membershipWasRefreshed: Boolean
): TaskEditorDraft {
    val project = projects.firstOrNull { it.id == draft.projectId } ?: return draft
    if (!project.canAssignTasks) {
        return draft.copy(assignee = currentUser)
    }
    if (!membershipWasRefreshed || (project.canAssignTasks && !project.collaboratorsReady)) {
        return draft.copy(
            assignee = project.collaborators.firstOrNull { it.id == draft.assignee?.id } ?: draft.assignee
        )
    }
    return selectAddTaskProject(draft, project, currentUser)
}

internal fun buildEditUpdateRequestWhenScheduleValid(
    task: TaskSnapshotItem,
    draft: TaskEditorDraft,
    project: ProjectItem,
    recurrencePreset: RecurrencePreset?,
    existingDrafts: List<ExistingChecklistDraft>,
    deletedChildren: Set<String>,
    addedDrafts: List<ChecklistEditorDraft>,
    deviceId: String
): UpdateTaskRequest? = if (validateScheduleForSave(draft).isValid) {
    updateRequest(
        task = task,
        title = draft.title,
        description = draft.description,
        project = project,
        selectedAssignee = draft.assignee,
        schedule = draft.schedule,
        recurrencePreset = recurrencePreset,
        existingDrafts = existingDrafts,
        deletedChildren = deletedChildren,
        addedDrafts = addedDrafts,
        deviceId = deviceId
    )
} else {
    null
}

internal data class EditRecurrencePresentation(
    val rowValue: String,
    val selectedPreset: RecurrencePreset?
)

internal fun editRecurrencePresentation(
    task: TaskSnapshotItem,
    customRecurrenceUntouched: Boolean,
    draftPreset: RecurrencePreset
): EditRecurrencePresentation = if (customRecurrenceUntouched) {
    val customRule = task.recurrence.rule?.trim().takeUnless { it.isNullOrEmpty() }
    EditRecurrencePresentation(
        rowValue = customRule?.let { "Custom: $it" } ?: "Custom recurrence",
        selectedPreset = null
    )
} else {
    EditRecurrencePresentation(
        rowValue = recurrenceLabel(draftPreset),
        selectedPreset = draftPreset
    )
}

@Composable
fun EditTaskScreen(
    task: TaskSnapshotItem,
    deviceId: String,
    initialAddedDrafts: List<ChecklistEditorDraft> = emptyList(),
    onBack: () -> Unit,
    onUpdate: suspend (String, UpdateTaskRequest) -> CoordinatorActionResult,
    loadProjects: (suspend () -> List<ProjectItem>)? = null,
    refreshProjects: (suspend () -> List<ProjectItem>)? = null,
    nowProvider: () -> LocalDateTime = { LocalDateTime.now() },
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val projectRepository = remember(loadProjects, refreshProjects) {
        if (loadProjects == null || refreshProjects == null) AddTaskDependencies.projectRepository() else null
    }
    val loadProjectList = loadProjects ?: projectRepository!!::loadProjectsOnce
    val refreshProjectList = refreshProjects ?: projectRepository!!::refreshProjects
    val initialDraft = remember(task.id) { initialEditTaskDraft(task) }
    var draft by remember(task.id) { mutableStateOf(initialDraft) }
    var projects by remember(task.id) { mutableStateOf<List<ProjectItem>>(emptyList()) }
    var projectsLoading by remember(task.id) { mutableStateOf(true) }
    var projectLoadError by remember(task.id) { mutableStateOf<String?>(null) }
    var projectLoadDialogMessage by remember(task.id) { mutableStateOf<String?>(null) }
    var projectFeedbackDismissed by remember(task.id) { mutableStateOf(false) }
    var existingDrafts by remember(task.id) {
        mutableStateOf(task.checklistItems.sortedBy { it.order }.map(ChecklistItem::toEditorDraft))
    }
    var deletedChildren by remember(task.id) { mutableStateOf<Set<String>>(emptySet()) }
    var addedDrafts by remember(task.id) { mutableStateOf(initialAddedDrafts) }
    var pendingRequest by remember(task.id) { mutableStateOf<UpdateTaskRequest?>(null) }
    var submitting by remember(task.id) { mutableStateOf(false) }
    var invalidField by remember(task.id) { mutableStateOf<AddTaskInvalidField?>(null) }
    var errorText by remember(task.id) { mutableStateOf<String?>(null) }
    var resultMessage by remember(task.id) { mutableStateOf<String?>(null) }
    var scheduleValidation by remember(task.id) { mutableStateOf(TaskEditorScheduleValidation.valid()) }
    var customRecurrenceUntouched by remember(task.id) {
        mutableStateOf(task.recurrence.kind == RecurrenceKind.custom)
    }
    var discardConfirmationVisible by remember(task.id) { mutableStateOf(false) }
    val now = remember(nowProvider) { nowProvider() }
    val selectedProject = projects.firstOrNull { it.id == draft.projectId }
    val currentUser = selectedProject?.collaborators?.firstOrNull { it.isCurrentUser }
        ?: projects.asSequence().flatMap { it.collaborators.asSequence() }.firstOrNull { it.isCurrentUser }
        ?: draft.assignee?.takeIf { it.isCurrentUser }
        ?: ProjectCollaborator(id = "", displayName = "Me", isCurrentUser = true)
    val collaboratorsBlocked = AddTaskProjectLoadFeedback.blocksSaveForCollaborators(selectedProject)
    val hasCachedProjects = projects.isNotEmpty()
    val saveEnabled = !submitting &&
        !projectsLoading &&
        hasCachedProjects &&
        (projectLoadError == null || AddTaskProjectLoadFeedback.canUseCachedProjects(projects, projectLoadError)) &&
        !collaboratorsBlocked
    val projectFeedbackMessage = if (projectLoadDialogMessage != null || projectFeedbackDismissed) {
        null
    } else {
        AddTaskProjectLoadFeedback.message(
            projectsLoading = projectsLoading,
            projects = projects,
            projectLoadError = projectLoadError.takeUnless { hasCachedProjects }
        )
    }
    val recurrencePresentation = editRecurrencePresentation(
        task = task,
        customRecurrenceUntouched = customRecurrenceUntouched,
        draftPreset = draft.recurrencePreset
    )

    fun updateDraft(next: TaskEditorDraft) {
        if (draft == next) return
        draft = next
        pendingRequest = null
        invalidField = null
        errorText = null
        resultMessage = null
        scheduleValidation = TaskEditorScheduleValidation.valid()
    }

    fun applyLoadedProjects(loaded: List<ProjectItem>, membershipWasRefreshed: Boolean) {
        projects = loaded
        val reconciledDraft = reconcileEditProjectMembership(
            draft = draft,
            projects = loaded,
            currentUser = loaded.firstOrNull { project -> project.collaborators.any { it.isCurrentUser } }
                ?.collaborators?.first { it.isCurrentUser }
                ?: currentUser,
            membershipWasRefreshed = membershipWasRefreshed
        )
        if (reconciledDraft == draft) return
        val loadedProject = loaded.firstOrNull { it.id == reconciledDraft.projectId }
        val changedSavedSelection = draft.projectId != reconciledDraft.projectId ||
            assigneeIdForProject(loadedProject, draft.assignee) !=
                assigneeIdForProject(loadedProject, reconciledDraft.assignee)
        draft = reconciledDraft
        if (changedSavedSelection) {
            pendingRequest = null
            invalidField = null
            errorText = null
            resultMessage = null
            scheduleValidation = TaskEditorScheduleValidation.valid()
        }
    }

    fun retryProjects() {
        scope.launch {
            projectsLoading = true
            projectLoadError = null
            projectLoadDialogMessage = null
            projectFeedbackDismissed = false
            try {
                applyLoadedProjects(refreshProjectList(), membershipWasRefreshed = true)
            } catch (error: Exception) {
                AppLog.error(
                    "edit_task projects refresh_failed type=${error::class.simpleName} message=${error.message}",
                    error
                )
                val message = error.message ?: "Project loading failed"
                projectLoadError = message
                projectLoadDialogMessage = message
            } finally {
                projectsLoading = false
            }
        }
    }

    fun submitRequest(request: UpdateTaskRequest) {
        if (submitting) return
        submitting = true
        resultMessage = null
        scope.launch {
            when (val result = onUpdate(task.id, request)) {
                CoordinatorActionResult.Success -> onBack()
                is CoordinatorActionResult.Stale -> resultMessage = "Task changed elsewhere. Your draft is still here; reapply it after review."
                is CoordinatorActionResult.Pending -> resultMessage = "Saved; waiting for Todoist confirmation"
                is CoordinatorActionResult.RetryAfter -> resultMessage = "Retry in ${result.retryAfterSeconds} seconds"
                is CoordinatorActionResult.LocalReconciliationFailed -> resultMessage = result.message
                is CoordinatorActionResult.FailedAndHidden -> resultMessage = result.message
                CoordinatorActionResult.ChildActionInFlight -> resultMessage = "Checklist update is already in progress"
            }
            submitting = false
        }
    }

    fun validateAndSubmit() {
        if (submitting) return
        val checklistValidation = validateChecklistDrafts(addedDrafts)
        if (checklistValidation.bannerMessage != null || checklistValidation.invalidDraftIds.isNotEmpty()) {
            invalidField = null
            errorText = checklistValidation.bannerMessage ?: "Each checklist item needs a title"
            return
        }
        if (AddTaskProjectLoadFeedback.blocksSaveForCollaborators(selectedProject)) {
            invalidField = AddTaskInvalidField.Assignee
            errorText = "Collaborators are unavailable. Retry before saving."
            return
        }
        val project = selectedProject
        if (project == null) {
            invalidField = AddTaskInvalidField.Project
            errorText = "Project is required"
            return
        }
        val latestScheduleValidation = validateScheduleForSave(draft)
        if (!latestScheduleValidation.isValid) {
            invalidField = null
            errorText = null
            scheduleValidation = latestScheduleValidation
            return
        }
        val deadline = draft.schedule.startDateTime()?.let { start ->
            DateTimeSelection(start.toLocalDate(), start.hour, start.minute)
        }
        when (
            val formResult = AddTaskFormValidator.validate(
                title = draft.title,
                selectedProject = project,
                selectedAssignee = draft.assignee,
                deadline = deadline,
                durationMinutes = draft.schedule.durationMinutes,
                description = draft.description,
                recurrencePreset = draft.recurrencePreset,
                timeZone = ZoneId.systemDefault().id,
                actionId = "edit-validation",
                deviceId = deviceId
            )
        ) {
            is AddTaskFormResult.Invalid -> {
                invalidField = formResult.field
                if (formResult.field == AddTaskInvalidField.Start || formResult.field == AddTaskInvalidField.Duration) {
                    scheduleValidation = validateScheduleForSave(draft)
                    errorText = null
                } else {
                    errorText = formResult.message
                }
            }
            is AddTaskFormResult.Valid -> {
                invalidField = null
                val request = pendingRequest ?: updateRequest(
                    task = task,
                    title = draft.title,
                    description = draft.description,
                    project = project,
                    selectedAssignee = draft.assignee,
                    schedule = draft.schedule,
                    recurrencePreset = draft.recurrencePreset.takeUnless { customRecurrenceUntouched },
                    existingDrafts = existingDrafts,
                    deletedChildren = deletedChildren,
                    addedDrafts = addedDrafts,
                    deviceId = deviceId
                ).also { pendingRequest = it }
                submitRequest(request)
            }
        }
    }

    fun currentFingerprint() = editTaskDraftFingerprint(
        title = draft.title,
        description = draft.description,
        projectId = draft.projectId,
        assigneeId = assigneeIdForProject(selectedProject, draft.assignee)
            ?: draft.assignee?.id?.takeIf { selectedProject == null && it.isNotBlank() },
        start = DateTimeDraftSelection(draft.schedule.date, draft.schedule.hour, draft.schedule.minute),
        durationMinutes = draft.schedule.durationMinutes,
        recurrencePreset = draft.recurrencePreset.takeUnless { customRecurrenceUntouched },
        existingChildren = existingDrafts.map { it.toFingerprint() },
        deletedChildIds = deletedChildren,
        addedChildren = addedDrafts.map { it.toFingerprint() }
    )
    val initialFingerprint = remember(task.id, initialAddedDrafts) {
        editTaskDraftFingerprint(
            title = initialDraft.title,
            description = initialDraft.description,
            projectId = task.projectId,
            assigneeId = task.assigneeId,
            start = DateTimeDraftSelection(initialDraft.schedule.date, initialDraft.schedule.hour, initialDraft.schedule.minute),
            durationMinutes = initialDraft.schedule.durationMinutes,
            recurrencePreset = task.recurrence.preset,
            existingChildren = task.checklistItems.sortedBy { it.order }.map { it.toEditorDraft().toFingerprint() },
            deletedChildIds = emptySet(),
            addedChildren = initialAddedDrafts.map { it.toFingerprint() }
        )
    }
    fun requestExit() {
        when (decideDraftExit(initialFingerprint, currentFingerprint(), submitting)) {
            DraftExitDecision.NavigateHome -> onBack()
            DraftExitDecision.ConfirmDiscard -> discardConfirmationVisible = true
            DraftExitDecision.Ignore -> Unit
        }
    }

    LaunchedEffect(task.id) {
        projectsLoading = true
        projectLoadError = null
        projectLoadDialogMessage = null
        projectFeedbackDismissed = false
        try {
            applyLoadedProjects(loadProjectList(), membershipWasRefreshed = false)
        } catch (error: Exception) {
            AppLog.error("edit_task projects load_failed type=${error::class.simpleName} message=${error.message}", error)
            val message = error.message ?: "Project loading failed"
            projectLoadError = message
            projectLoadDialogMessage = message
        } finally {
            projectsLoading = false
        }
    }

    androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxSize()) {
        TaskEditorScaffold(
            screenTitle = "Edit task",
            title = draft.title,
            onTitleChange = { updateDraft(draft.copy(title = it)) },
            draft = draft,
            now = now,
            projects = projects,
            currentUser = currentUser,
            checklistDoneCount = existingDrafts.count { it.checked && it.id !in deletedChildren },
            checklistTotalCount = existingDrafts.count { it.id !in deletedChildren } + addedDrafts.size,
            checklistContent = {
                ExistingChecklistEditor(
                    drafts = existingDrafts.filterNot { it.id in deletedChildren },
                    onDraftsChange = { next ->
                        if (existingDrafts != next) {
                            existingDrafts = next
                            pendingRequest = null
                            errorText = null
                        }
                    },
                    onDelete = { childId ->
                        deletedChildren = deletedChildren + childId
                        pendingRequest = null
                        errorText = null
                    },
                    enabled = !submitting
                )
                ChecklistEditor(
                    drafts = addedDrafts,
                    onDraftsChange = { next ->
                        if (addedDrafts != next) {
                            addedDrafts = next
                            pendingRequest = null
                            errorText = null
                        }
                    },
                    nextClientMutationId = { UUID.randomUUID().toString() },
                    enabled = !submitting,
                    maxItems = (30 - existingDrafts.count { it.id !in deletedChildren }).coerceAtLeast(0)
                )
            },
            afterChecklistContent = { ActivityLogSection(task) },
            onScheduleChange = { schedule -> updateDraft(draft.copy(schedule = schedule)) },
            onNoSchedule = { updateDraft(setNoSchedule(draft)) },
            onProjectSelected = { project ->
                updateDraft(selectAddTaskProject(draft, project, currentUser))
            },
            onAssigneeSelected = { assignee -> updateDraft(draft.copy(assignee = assignee)) },
            onRecurrencePresetSelected = { preset ->
                val replacesCustomRecurrence = customRecurrenceUntouched
                customRecurrenceUntouched = false
                if (replacesCustomRecurrence && draft.recurrencePreset == preset) {
                    pendingRequest = null
                    errorText = null
                    resultMessage = null
                } else {
                    updateDraft(draft.copy(recurrencePreset = preset))
                }
            },
            onDescriptionChange = { description -> updateDraft(draft.copy(description = description)) },
            onRetryCollaborators = { retryProjects() },
            onSave = { validateAndSubmit() },
            onBack = { requestExit() },
            onCancel = { requestExit() },
            scheduleValidation = scheduleValidation,
            enabled = !submitting,
            saveEnabled = saveEnabled,
            saving = submitting,
            titleError = errorText.takeIf { invalidField == AddTaskInvalidField.Title },
            projectError = errorText.takeIf { invalidField == AddTaskInvalidField.Project },
            assigneeError = errorText.takeIf { invalidField == AddTaskInvalidField.Assignee },
            checklistError = errorText.takeIf { invalidField == null },
            recurrenceValue = recurrencePresentation.rowValue,
            selectedRecurrencePreset = recurrencePresentation.selectedPreset,
            modifier = Modifier.fillMaxSize()
        )
        if (projectsLoading) InlineLoadingStatus("Loading projects...")
    }

    projectFeedbackMessage?.let { message ->
        ErrorAlertDialog(
            title = "Projects unavailable",
            message = message,
            onDismiss = { projectFeedbackDismissed = true },
            retryLabel = "Retry",
            onRetry = { retryProjects() }
        )
    }
    projectLoadDialogMessage?.let { message ->
        ErrorAlertDialog(
            title = "Projects did not load",
            message = message,
            onDismiss = { projectLoadDialogMessage = null },
            retryLabel = "Retry",
            onRetry = { retryProjects() }
        )
    }
    resultMessage?.let { message ->
        ErrorAlertDialog(
            title = "Task update",
            message = message,
            onDismiss = { resultMessage = null },
            retryLabel = "Retry".takeUnless { message == "Saved; waiting for Todoist confirmation" },
            onRetry = {
                resultMessage = null
                validateAndSubmit()
            }
        )
    }
    if (discardConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { discardConfirmationVisible = false },
            title = { Text("Discard changes?") },
            text = { Text("Your task edits have not been saved.") },
            confirmButton = {
                TextButton(onClick = onBack) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { discardConfirmationVisible = false }) { Text("Keep editing") }
            }
        )
    }
}

internal fun updateRequest(
    task: TaskSnapshotItem,
    title: String,
    description: String,
    project: ProjectItem,
    selectedAssignee: ProjectCollaborator?,
    schedule: TaskEditorSchedule,
    recurrencePreset: RecurrencePreset?,
    existingDrafts: List<ExistingChecklistDraft>,
    deletedChildren: Set<String>,
    addedDrafts: List<ChecklistEditorDraft>,
    deviceId: String
): UpdateTaskRequest {
    check(schedule.isEmpty() || schedule.isComplete()) { "Update requests require a complete or empty schedule" }
    val originalById = task.checklistItems.associateBy { it.id }
    val changedExisting = existingDrafts.filterNot { it.id in deletedChildren }.flatMap { draft ->
        val original = originalById.getValue(draft.id)
        buildList {
            if (draft.title.trim() != original.title || draft.description != original.description) {
                add(ChecklistMutation.Edit(UUID.randomUUID().toString(), draft.id, draft.childRevision, draft.title.trim(), draft.description))
            }
            if (draft.checked != original.checked) {
                add(
                    if (draft.checked) ChecklistMutation.Check(UUID.randomUUID().toString(), draft.id, draft.childRevision)
                    else ChecklistMutation.Uncheck(UUID.randomUUID().toString(), draft.id, draft.childRevision)
                )
            }
        }
    }
    val deletions = task.checklistItems.filter { it.id in deletedChildren }.map {
        ChecklistMutation.Delete(UUID.randomUUID().toString(), it.id, it.childRevision)
    }
    val scheduleIsEmpty = schedule.isEmpty()
    val selectedStart = schedule.startDateTime()?.let { DateTimeSelection(it.toLocalDate(), it.hour, it.minute) }
    val requestedRecurrence = if (scheduleIsEmpty) RecurrencePreset.never else recurrencePreset
    val recurrenceChange = when {
        scheduleIsEmpty -> RecurrencePreset.never
        task.recurrence.kind == RecurrenceKind.custom -> requestedRecurrence
        requestedRecurrence != task.recurrence.preset -> requestedRecurrence
        else -> null
    }
    return UpdateTaskRequest(
        actionId = UUID.randomUUID().toString(),
        deviceId = deviceId,
        expectedOccurrenceKey = task.occurrenceKey,
        rootPatch = RootTaskPatch(
            expectedTaskRevision = task.taskRevision,
            title = title.trim(),
            description = NullablePatch.Value(description.trim().takeIf { it.isNotEmpty() }),
            projectId = project.id,
            assigneeId = NullablePatch.Value(assigneeIdForProject(project, selectedAssignee)),
            dueDatetime = NullablePatch.Value(selectedStart?.toIsoInstantString(ZoneId.systemDefault())),
            durationMinutes = NullablePatch.Value(schedule.durationMinutes),
            recurrencePreset = recurrenceChange,
            timeZone = ZoneId.systemDefault().id.takeIf {
                selectedStart != null && recurrenceChange != null && recurrenceChange != RecurrencePreset.never
            }
        ),
        childMutations = changedExisting + deletions + addedDrafts.map { draft ->
            ChecklistMutation.Add(draft.clientMutationId, draft.title.trim(), draft.description)
        }
    )
}

private fun assigneeIdForProject(
    project: ProjectItem?,
    assignee: ProjectCollaborator?
): String? = assignee?.id?.takeIf { project?.canAssignTasks == true && it.isNotBlank() }

private fun String.toTaskEditorSchedule(): TaskEditorSchedule? {
    val local = runCatching { Instant.parse(this).atZone(ZoneId.systemDefault()).toLocalDateTime() }
        .recoverCatching { LocalDateTime.parse(this) }
        .getOrNull()
        ?: return null
    return TaskEditorSchedule(date = local.toLocalDate(), hour = local.hour, minute = local.minute)
}

private fun String.toTaskEditorDateSchedule(): TaskEditorSchedule? =
    runCatching { java.time.LocalDate.parse(this) }
        .getOrNull()
        ?.let { TaskEditorSchedule(date = it) }

private fun ExistingChecklistDraft.toFingerprint() = ChecklistDraftFingerprint(id, title, description, checked)

private fun ChecklistEditorDraft.toFingerprint() = ChecklistDraftFingerprint(clientMutationId, title, description, false)

@Composable
internal fun ExistingChecklistEditor(
    drafts: List<ExistingChecklistDraft>,
    onDraftsChange: (List<ExistingChecklistDraft>) -> Unit,
    onDelete: (String) -> Unit,
    enabled: Boolean
) {
    if (drafts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(app.buzer.android.ui.theme.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
        Text("Existing checklist")
        drafts.forEach { draft ->
            Column(verticalArrangement = Arrangement.spacedBy(app.buzer.android.ui.theme.Spacing.xs)) {
                Checkbox(
                    checked = draft.checked,
                    onCheckedChange = { checked -> onDraftsChange(drafts.map { if (it.id == draft.id) it.copy(checked = checked) else it }) },
                    enabled = enabled
                )
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { title -> onDraftsChange(drafts.map { if (it.id == draft.id) it.copy(title = title) else it }) },
                    label = { Text("Checklist item") }, enabled = enabled, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.description.orEmpty(),
                    onValueChange = { description -> onDraftsChange(drafts.map { if (it.id == draft.id) it.copy(description = description.takeIf(String::isNotBlank)) else it }) },
                    label = { Text("Item details (optional)") }, enabled = enabled, modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { onDelete(draft.id) }, enabled = enabled) { Text("Remove item") }
            }
        }
    }
}
