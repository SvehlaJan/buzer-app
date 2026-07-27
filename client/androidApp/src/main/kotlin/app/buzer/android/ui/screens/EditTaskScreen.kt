package app.buzer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import app.buzer.android.ui.addtask.AddTaskProjectLoadFeedback
import app.buzer.android.ui.components.ChecklistEditor
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.status.WarningStatus
import app.buzer.android.ui.taskeditor.TaskEditorDraft
import app.buzer.android.ui.taskeditor.TaskEditorPresentation
import app.buzer.android.ui.taskeditor.TaskEditorSchedule
import app.buzer.android.ui.taskeditor.TaskEditorUpdateChoices
import app.buzer.android.ui.taskeditor.TaskEditorUpdateContentValidation
import app.buzer.android.ui.taskeditor.TaskEditorUpdateScreen
import app.buzer.android.ui.taskeditor.initialTaskEditorUpdateDraft
import app.buzer.android.ui.taskeditor.recurrencePresetForUpdate
import app.buzer.android.ui.taskeditor.taskEditorUpdateChecklistAccess
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
import app.buzer.shared.ui.taskeditor.ChecklistEditorDraft
import app.buzer.shared.ui.taskeditor.validateChecklistDrafts
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

/** The Update host may replay this exact request while reconciliation is pending. */
internal data class EditTaskUpdateCapture(
    val fingerprint: EditTaskDraftFingerprint,
    val request: UpdateTaskRequest
)

internal fun captureEditTaskUpdate(
    captured: EditTaskUpdateCapture?,
    fingerprint: EditTaskDraftFingerprint,
    createRequest: () -> UpdateTaskRequest
): EditTaskUpdateCapture = captured?.takeIf { it.fingerprint == fingerprint }
    ?: EditTaskUpdateCapture(fingerprint = fingerprint, request = createRequest())

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
    var choices by remember(task.id) { mutableStateOf(TaskEditorUpdateChoices(loading = true)) }
    var projectLoadDialogMessage by remember(task.id) { mutableStateOf<String?>(null) }
    var projectFeedbackDismissed by remember(task.id) { mutableStateOf(false) }
    var existingDrafts by remember(task.id) {
        mutableStateOf(task.checklistItems.sortedBy { it.order }.map(ChecklistItem::toEditorDraft))
    }
    var deletedChildren by remember(task.id) { mutableStateOf<Set<String>>(emptySet()) }
    var addedDrafts by remember(task.id) { mutableStateOf(initialAddedDrafts) }
    var capturedUpdate by remember(task.id) { mutableStateOf<EditTaskUpdateCapture?>(null) }
    val now = remember(nowProvider) { nowProvider() }
    val initialDraft = remember(task.id) { initialTaskEditorUpdateDraft(task) }
    val initialFingerprint = remember(task.id, initialAddedDrafts, choices) {
        editTaskDraftFingerprint(
            title = initialDraft.title,
            description = initialDraft.description,
            projectId = initialDraft.projectId,
            assigneeId = choices.assigneeIdForFingerprint(initialDraft),
            start = DateTimeDraftSelection(initialDraft.schedule.date, initialDraft.schedule.hour, initialDraft.schedule.minute),
            durationMinutes = initialDraft.schedule.durationMinutes,
            recurrencePreset = initialDraft.recurrencePresetForUpdate(),
            existingChildren = task.checklistItems.sortedBy { it.order }.map { it.toEditorDraft().toFingerprint() },
            deletedChildIds = emptySet(),
            addedChildren = initialAddedDrafts.map { it.toFingerprint() }
        )
    }
    val checklistMutationsAllowed = taskEditorUpdateChecklistAccess(task, hostEnabled = true).enabled

    fun currentFingerprint(draft: TaskEditorDraft): EditTaskDraftFingerprint {
        return editTaskDraftFingerprint(
            title = draft.title,
            description = draft.description,
            projectId = draft.projectId,
            assigneeId = choices.assigneeIdForFingerprint(draft),
            start = DateTimeDraftSelection(draft.schedule.date, draft.schedule.hour, draft.schedule.minute),
            durationMinutes = draft.schedule.durationMinutes,
            recurrencePreset = draft.recurrencePresetForUpdate(),
            existingChildren = existingDrafts.map { it.toFingerprint() },
            deletedChildIds = deletedChildren,
            addedChildren = addedDrafts.map { it.toFingerprint() }
        )
    }

    fun retryProjects() {
        scope.launch {
            choices = choices.copy(loading = true, errorMessage = null)
            projectLoadDialogMessage = null
            projectFeedbackDismissed = false
            try {
                choices = TaskEditorUpdateChoices(
                    projects = refreshProjectList(),
                    membershipWasRefreshed = true
                )
            } catch (error: Exception) {
                AppLog.error(
                    "edit_task projects refresh_failed type=${error::class.simpleName} message=${error.message}",
                    error
                )
                val message = error.message ?: "Project loading failed"
                choices = choices.copy(loading = false, errorMessage = message)
                projectLoadDialogMessage = message
            }
        }
    }

    LaunchedEffect(task.id) {
        choices = TaskEditorUpdateChoices(loading = true)
        projectLoadDialogMessage = null
        projectFeedbackDismissed = false
        try {
            choices = TaskEditorUpdateChoices(projects = loadProjectList())
        } catch (error: Exception) {
            AppLog.error("edit_task projects load_failed type=${error::class.simpleName} message=${error.message}", error)
            val message = error.message ?: "Project loading failed"
            choices = TaskEditorUpdateChoices(errorMessage = message)
            projectLoadDialogMessage = message
        }
    }

    val projectFeedbackMessage = if (projectLoadDialogMessage != null || projectFeedbackDismissed) {
        null
    } else {
        AddTaskProjectLoadFeedback.message(
            projectsLoading = choices.loading,
            projects = choices.projects,
            projectLoadError = choices.errorMessage.takeUnless { choices.projects.isNotEmpty() }
        )
    }

    androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxSize()) {
        TaskEditorUpdateScreen(
            task = task,
            choices = choices,
            presentation = TaskEditorPresentation(
                screenTitle = "Edit task",
                primaryVerb = "Save",
                showUpdateFooter = true
            ),
            now = now,
            checklistDoneCount = existingDrafts.count { it.checked && it.id !in deletedChildren },
            checklistTotalCount = existingDrafts.count { it.id !in deletedChildren } + addedDrafts.size,
            checklistContent = { checklistEnabled, checklistError ->
                val checklistAccess = taskEditorUpdateChecklistAccess(task, checklistEnabled)
                checklistAccess.warningMessage?.let { WarningStatus(it) }
                checklistError?.let { Text(it) }
                ExistingChecklistEditor(
                    drafts = existingDrafts.filterNot { it.id in deletedChildren },
                    onDraftsChange = { next -> existingDrafts = next },
                    onDelete = { childId -> deletedChildren = deletedChildren + childId },
                    enabled = checklistAccess.enabled
                )
                ChecklistEditor(
                    drafts = addedDrafts,
                    onDraftsChange = { next -> addedDrafts = next },
                    nextClientMutationId = { UUID.randomUUID().toString() },
                    enabled = checklistAccess.enabled,
                    maxItems = (30 - existingDrafts.count { it.id !in deletedChildren }).coerceAtLeast(0)
                )
            },
            footerContent = { ActivityLogSection(task) },
            onRetryChoices = ::retryProjects,
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
                val nextCapture = captureEditTaskUpdate(
                    captured = capturedUpdate,
                    fingerprint = fingerprint
                ) {
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
                onUpdate(task.id, nextCapture.request)
            },
            onSuccess = onBack,
            onCancel = onBack,
            modifier = Modifier.fillMaxSize()
        )
        if (choices.loading) InlineLoadingStatus("Loading projects...")
    }

    projectFeedbackMessage?.let { message ->
        ErrorAlertDialog(
            title = "Projects unavailable",
            message = message,
            onDismiss = { projectFeedbackDismissed = true },
            retryLabel = "Retry",
            onRetry = ::retryProjects
        )
    }
    projectLoadDialogMessage?.let { message ->
        ErrorAlertDialog(
            title = "Projects did not load",
            message = message,
            onDismiss = { projectLoadDialogMessage = null },
            retryLabel = "Retry",
            onRetry = ::retryProjects
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

internal fun assigneeIdForProject(
    project: ProjectItem?,
    assignee: ProjectCollaborator?
): String? = assignee?.id?.takeIf { project?.canAssignTasks == true && it.isNotBlank() }

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
