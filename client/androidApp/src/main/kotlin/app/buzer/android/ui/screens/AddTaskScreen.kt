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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.buzer.android.tasks.AddTaskDependencies
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.ui.addtask.AddTaskFailurePresentation
import app.buzer.android.ui.addtask.AddTaskFormResult
import app.buzer.android.ui.addtask.AddTaskFormValidator
import app.buzer.android.ui.addtask.AddTaskInvalidField
import app.buzer.android.ui.addtask.AddTaskProjectLoadFeedback
import app.buzer.android.ui.addtask.AddTaskSubmitState
import app.buzer.android.ui.addtask.selectAddTaskProject
import app.buzer.android.ui.addtask.runSuccessfulAddTaskCreate
import app.buzer.android.ui.components.ChecklistEditor
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.taskeditor.TaskEditorDraft
import app.buzer.android.ui.taskeditor.TaskEditorScaffold
import app.buzer.android.ui.taskeditor.TaskEditorScheduleValidation
import app.buzer.android.ui.taskeditor.setNoSchedule
import app.buzer.android.ui.taskeditor.validateScheduleForSave
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ChecklistDraft
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.ui.commitment.ChecklistEditorDraft
import app.buzer.shared.ui.commitment.validateChecklistDrafts
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskScreen(
    onBack: () -> Unit,
    onTaskCreated: (TasksSnapshotResponse) -> Unit = {},
    createTask: suspend (CreateTaskRequest) -> app.buzer.shared.model.MutationResponse = AddTaskDependencies.createTaskAction(),
    loadProjects: (suspend () -> List<ProjectItem>)? = null,
    refreshProjects: (suspend () -> List<ProjectItem>)? = null,
    initialSelectedProjectId: String? = null,
    persistSelectedProjectId: ((String) -> Unit)? = null,
    nowProvider: () -> LocalDateTime = { LocalDateTime.now() }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projectRepository = remember(loadProjects, refreshProjects) {
        if (loadProjects == null || refreshProjects == null) {
            AddTaskDependencies.projectRepository()
        } else {
            null
        }
    }
    val projectSelectionStore = remember(context, initialSelectedProjectId, persistSelectedProjectId) {
        if (initialSelectedProjectId == null || persistSelectedProjectId == null) {
            AddTaskDependencies.projectSelectionStore(context)
        } else {
            null
        }
    }
    val loadProjectList = loadProjects ?: projectRepository!!::loadProjectsOnce
    val refreshProjectList = refreshProjects ?: projectRepository!!::refreshProjects
    val persistedProjectId = remember(initialSelectedProjectId, projectSelectionStore) {
        initialSelectedProjectId ?: projectSelectionStore?.getSelectedProjectId()
    }
    val persistProjectId: (String) -> Unit = persistSelectedProjectId ?: { projectId ->
        projectSelectionStore?.setSelectedProjectId(projectId)
    }
    var draft by remember { mutableStateOf(TaskEditorDraft(title = "")) }
    var projects by remember { mutableStateOf<List<ProjectItem>>(emptyList()) }
    var projectsLoading by remember { mutableStateOf(true) }
    var projectLoadError by remember { mutableStateOf<String?>(null) }
    var projectLoadDialogMessage by remember { mutableStateOf<String?>(null) }
    var projectFeedbackDismissed by remember { mutableStateOf(false) }
    var invalidField by remember { mutableStateOf<AddTaskInvalidField?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var submitDialogMessage by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var pendingCreateRequest by remember { mutableStateOf<CreateTaskRequest?>(null) }
    var checklistDrafts by remember { mutableStateOf<List<ChecklistEditorDraft>>(emptyList()) }
    var scheduleValidation by remember { mutableStateOf(TaskEditorScheduleValidation.valid()) }
    val now = remember(nowProvider) { nowProvider() }
    val deviceId = remember(context) {
        EnforcementDependencies.deviceIdentityStore(context.applicationContext).deviceId()
    }
    val hasCachedProjects = projects.isNotEmpty()
    val projectFeedbackMessage = if (projectLoadDialogMessage != null || projectFeedbackDismissed) {
        null
    } else {
        AddTaskProjectLoadFeedback.message(
            projectsLoading = projectsLoading,
            projects = projects,
            projectLoadError = projectLoadError.takeUnless { hasCachedProjects }
        )
    }
    val submitState = AddTaskSubmitState(
        submitting = submitting,
        projectsLoading = projectsLoading,
        projectLoadError = projectLoadError
    )

    val selectedProject = projects.firstOrNull { it.id == draft.projectId }
    val currentUser = selectedProject?.collaborators?.firstOrNull { it.isCurrentUser }
        ?: projects.asSequence()
            .flatMap { it.collaborators.asSequence() }
            .firstOrNull { it.isCurrentUser }
        // The backend normally provides Me with every project. This fallback is not a
        // selectable membership; validation rejects it until a real project record loads.
        ?: ProjectCollaborator(id = "", displayName = "Me", isCurrentUser = true)
    val collaboratorsBlocked = AddTaskProjectLoadFeedback.blocksSaveForCollaborators(selectedProject)
    val saveEnabled = !submitting &&
        !projectsLoading &&
        hasCachedProjects &&
        (projectLoadError == null || AddTaskProjectLoadFeedback.canUseCachedProjects(projects, projectLoadError)) &&
        !collaboratorsBlocked

    fun updateDraft(next: TaskEditorDraft) {
        if (draft == next) return
        draft = next
        pendingCreateRequest = null
        invalidField = null
        errorText = null
        scheduleValidation = TaskEditorScheduleValidation.valid()
    }

    fun applyLoadedProjects(loaded: List<ProjectItem>) {
        projects = loaded
        val preferredProjectId = draft.projectId ?: persistedProjectId
        val resolvedProject = loaded.firstOrNull { it.id == preferredProjectId }
        if (resolvedProject != null) {
            val resolvedCurrentUser = resolvedProject.collaborators.firstOrNull { it.isCurrentUser }
                ?: loaded.asSequence()
                    .flatMap { it.collaborators.asSequence() }
                    .firstOrNull { it.isCurrentUser }
                ?: return
            updateDraft(selectAddTaskProject(draft, resolvedProject, resolvedCurrentUser))
            persistProjectId(resolvedProject.id)
        }
    }

    fun retryProjects() {
        scope.launch {
            projectsLoading = true
            projectLoadError = null
            projectLoadDialogMessage = null
            projectFeedbackDismissed = false
            try {
                applyLoadedProjects(refreshProjectList())
            } catch (error: Exception) {
                AppLog.error(
                    "add_task projects refresh_failed type=${error::class.simpleName} message=${error.message}",
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

    fun submitRequest(request: CreateTaskRequest) {
        if (submitting) return
        submitting = true
        errorText = null
        submitDialogMessage = null
        scope.launch {
            try {
                AppLog.info(
                    "add_task create_start titleLength=${request.title.length} " +
                        "project=${request.projectId} assignee=${request.assigneeId} " +
                        "due=${request.dueDatetime} duration=${request.durationMinutes}"
                )
                runSuccessfulAddTaskCreate(
                    request = request,
                    createTask = createTask,
                    onTaskCreated = onTaskCreated,
                    onBack = onBack
                )
                AppLog.info("add_task create_success")
            } catch (error: Exception) {
                AppLog.error(
                    "add_task create_failed type=${error::class.simpleName} message=${error.message}",
                    error
                )
                val message = error.message ?: "Task creation failed"
                submitDialogMessage = message
            } finally {
                AppLog.debug("add_task create_finished submitting=false")
                submitting = false
            }
        }
    }

    fun validateAndSubmit() {
        if (submitting) return
        val checklistValidation = validateChecklistDrafts(checklistDrafts)
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
        val schedule = draft.schedule
        val deadline = schedule.startDateTime()?.let { start ->
            DateTimeSelection(start.toLocalDate(), start.hour, start.minute)
        }
        AppLog.info(
            "add_task submit_clicked titleLength=${draft.title.length} project=${selectedProject?.id} assignee=${draft.assignee?.id} " +
                "due=${deadline != null} durationInput=${schedule.durationMinutes}"
        )
        when (
            val result = AddTaskFormValidator.validate(
                draft.title,
                selectedProject,
                draft.assignee,
                deadline,
                schedule.durationMinutes,
                description = draft.description,
                recurrencePreset = draft.recurrencePreset,
                timeZone = java.time.ZoneId.systemDefault().id,
                checklistDrafts = checklistDrafts.map { draft ->
                    ChecklistDraft(draft.clientMutationId, draft.title.trim(), draft.description)
                }
            )
        ) {
            is AddTaskFormResult.Invalid -> {
                AppLog.warn("add_task validation_failed message=${result.message}")
                invalidField = result.field
                if (result.field == AddTaskInvalidField.Start || result.field == AddTaskInvalidField.Duration) {
                    scheduleValidation = validateScheduleForSave(draft)
                    errorText = null
                } else {
                    errorText = result.message
                }
            }
            is AddTaskFormResult.Valid -> {
                invalidField = null
                val request = pendingCreateRequest ?: result.request.copy(
                    actionId = UUID.randomUUID().toString(),
                    deviceId = deviceId
                ).also { pendingCreateRequest = it }
                submitRequest(request)
            }
        }
    }

    LaunchedEffect(Unit) {
        projectsLoading = true
        projectLoadError = null
        projectLoadDialogMessage = null
        projectFeedbackDismissed = false
        try {
            applyLoadedProjects(loadProjectList())
        } catch (error: Exception) {
            AppLog.error("add_task projects load_failed type=${error::class.simpleName} message=${error.message}", error)
            val message = error.message ?: "Project loading failed"
            projectLoadError = message
            projectLoadDialogMessage = message
        } finally {
            projectsLoading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TaskEditorScaffold(
            screenTitle = "Add task",
            title = draft.title,
            onTitleChange = { updateDraft(draft.copy(title = it)) },
            draft = draft,
            now = now,
            projects = projects,
            currentUser = currentUser,
            checklistDoneCount = 0,
            checklistTotalCount = checklistDrafts.size,
            checklistContent = {
                ChecklistEditor(
                    drafts = checklistDrafts,
                    onDraftsChange = { nextDrafts ->
                        if (checklistDrafts != nextDrafts) {
                            checklistDrafts = nextDrafts
                            pendingCreateRequest = null
                            errorText = null
                        }
                    },
                    nextClientMutationId = { UUID.randomUUID().toString() },
                    enabled = !submitState.formDisabled
                )
            },
            onScheduleChange = { schedule -> updateDraft(draft.copy(schedule = schedule)) },
            onNoSchedule = { updateDraft(setNoSchedule(draft)) },
            onProjectSelected = { project ->
                val nextDraft = selectAddTaskProject(draft, project, currentUser)
                updateDraft(nextDraft)
                persistProjectId(project.id)
            },
            onAssigneeSelected = { assignee -> updateDraft(draft.copy(assignee = assignee)) },
            onRecurrencePresetSelected = { preset -> updateDraft(draft.copy(recurrencePreset = preset)) },
            onDescriptionChange = { description -> updateDraft(draft.copy(description = description)) },
            onRetryCollaborators = { retryProjects() },
            onSave = { validateAndSubmit() },
            onBack = onBack,
            onCancel = onBack,
            scheduleValidation = scheduleValidation,
            enabled = !submitState.formDisabled,
            saveEnabled = saveEnabled,
            saving = submitting,
            titleModifier = Modifier.testTag("titleInput"),
            titleError = errorText.takeIf { invalidField == AddTaskInvalidField.Title },
            projectError = errorText.takeIf { invalidField == AddTaskInvalidField.Project },
            assigneeError = errorText.takeIf { invalidField == AddTaskInvalidField.Assignee },
            checklistError = errorText.takeIf { invalidField == null },
            modifier = Modifier.fillMaxSize()
        )

        if (projectsLoading) {
            InlineLoadingStatus("Loading projects...")
        }
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

    submitDialogMessage?.let { message ->
        val presentation = AddTaskFailurePresentation.fromSaveFailure(message)
        ErrorAlertDialog(
            title = presentation.dialogTitle,
            message = presentation.dialogMessage,
            onDismiss = { submitDialogMessage = null },
            retryLabel = presentation.retryLabel,
            onRetry = {
                submitDialogMessage = null
                validateAndSubmit()
            }
        )
    }
}

@Composable
fun ProjectSelector(
    selectedProject: ProjectItem?,
    projects: List<ProjectItem>,
    projectsLoading: Boolean,
    projectMenuExpanded: Boolean,
    onProjectMenuExpandedChange: (Boolean) -> Unit,
    onProjectSelected: (ProjectItem) -> Unit,
    enabled: Boolean
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { onProjectMenuExpandedChange(true) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Project picker" }
        ) {
            Text(
                when {
                    projectsLoading -> "Loading projects..."
                    selectedProject != null -> selectedProject.name
                    else -> "Select project"
                }
            )
        }
        DropdownMenu(
            expanded = projectMenuExpanded,
            onDismissRequest = { onProjectMenuExpandedChange(false) }
        ) {
            projects.forEach { project ->
                DropdownMenuItem(
                    text = {
                        Text(if (project.canAssignTasks) project.name else "${project.name} (personal)")
                    },
                    onClick = { onProjectSelected(project) },
                    enabled = enabled
                )
            }
        }
    }
}

internal data class PersonalProjectAssigneePresentation(
    val displayName: String,
    val hint: String
)

internal fun personalProjectAssigneePresentation(
    selectedProject: ProjectItem?,
    selectedAssignee: ProjectCollaborator?,
    assigneeOptions: List<ProjectCollaborator>
): PersonalProjectAssigneePresentation? {
    if (selectedProject?.canAssignTasks != false) return null
    val currentUser = selectedAssignee?.takeIf { it.isCurrentUser }
        ?: selectedProject.collaborators.firstOrNull { it.isCurrentUser }
        ?: assigneeOptions.firstOrNull { it.isCurrentUser }
    return PersonalProjectAssigneePresentation(
        displayName = currentUser?.displayName ?: "Me",
        hint = "Personal project · assigned to you"
    )
}

@Composable
fun AssigneeSelector(
    selectedProject: ProjectItem?,
    assigneeOptions: List<ProjectCollaborator>,
    selectedAssignee: ProjectCollaborator?,
    onAssigneeSelected: (ProjectCollaborator) -> Unit,
    enabled: Boolean
) {
    personalProjectAssigneePresentation(selectedProject, selectedAssignee, assigneeOptions)?.let { presentation ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(presentation.displayName)
            Text(presentation.hint)
        }
        return
    }
    if (selectedProject == null) {
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        assigneeOptions.forEach { option ->
            val selected = selectedAssignee == option
            val label = if (selected) "${option.displayName} selected" else option.displayName
            if (selected) {
                Button(
                    onClick = { onAssigneeSelected(option) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(label)
                }
            } else {
                OutlinedButton(
                    onClick = { onAssigneeSelected(option) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(label)
                }
            }
        }
    }
}
