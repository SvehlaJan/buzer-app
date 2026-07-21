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
import app.buzer.android.ui.addtask.runSuccessfulAddTaskCreate
import app.buzer.android.ui.components.CommitmentEditor
import app.buzer.android.ui.components.ChecklistEditor
import app.buzer.android.ui.components.commitmentSaveLabel
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ChecklistDraft
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.ui.commitment.ChecklistEditorDraft
import app.buzer.shared.ui.commitment.validateChecklistDrafts
import app.buzer.shared.ui.commitment.CommitmentEditorMode
import app.buzer.shared.ui.commitment.CommitmentEditorValidation
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskScreen(
    onBack: () -> Unit,
    onTaskCreated: (TasksSnapshotResponse) -> Unit = {},
    createTask: suspend (CreateTaskRequest) -> app.buzer.shared.model.MutationResponse = AddTaskDependencies.createTaskAction(),
    assigneeOptions: List<ProjectCollaborator> = emptyList(),
    loadProjects: (suspend () -> List<ProjectItem>)? = null,
    refreshProjects: (suspend () -> List<ProjectItem>)? = null,
    initialSelectedProjectId: String? = null,
    persistSelectedProjectId: ((String) -> Unit)? = null
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
    var title by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf<List<ProjectItem>>(emptyList()) }
    var projectsLoading by remember { mutableStateOf(true) }
    var projectLoadError by remember { mutableStateOf<String?>(null) }
    var projectLoadDialogMessage by remember { mutableStateOf<String?>(null) }
    var selectedProject by remember { mutableStateOf<ProjectItem?>(null) }
    var projectMenuExpanded by remember { mutableStateOf(false) }
    var selectedAssignee by remember { mutableStateOf<ProjectCollaborator?>(null) }
    var deadline by remember { mutableStateOf(DateTimeDraftSelection.Empty) }
    var durationMinutes by remember { mutableStateOf(30) }
    var description by remember { mutableStateOf("") }
    var descriptionExpanded by remember { mutableStateOf(false) }
    var invalidField by remember { mutableStateOf<AddTaskInvalidField?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var submitDialogMessage by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var pendingCreateRequest by remember { mutableStateOf<CreateTaskRequest?>(null) }
    var recurrencePreset by remember { mutableStateOf(RecurrencePreset.never) }
    var checklistDrafts by remember { mutableStateOf<List<ChecklistEditorDraft>>(emptyList()) }
    val deviceId = remember(context) {
        EnforcementDependencies.deviceIdentityStore(context.applicationContext).deviceId()
    }
    val projectFeedbackMessage = if (projectLoadDialogMessage != null) {
        null
    } else {
        AddTaskProjectLoadFeedback.message(
            projectsLoading = projectsLoading,
            projects = projects,
            projectLoadError = null
        )
    }
    val submitState = AddTaskSubmitState(
        submitting = submitting,
        projectsLoading = projectsLoading,
        projectLoadError = projectLoadError
    )

    fun applyLoadedProjects(loaded: List<ProjectItem>) {
        projects = loaded
        val preferredProjectId = selectedProject?.id ?: persistedProjectId
        selectedProject = loaded.firstOrNull { it.id == preferredProjectId }
        selectedAssignee = selectedProject?.let { project ->
            project.collaborators.firstOrNull { it.id == selectedAssignee?.id }
                ?: project.collaborators.firstOrNull { it.isCurrentUser && !project.canAssignTasks }
        }
        selectedProject?.let { persistProjectId(it.id) }
    }

    fun retryProjects() {
        scope.launch {
            projectsLoading = true
            projectLoadError = null
            projectLoadDialogMessage = null
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
            errorText = checklistValidation.bannerMessage ?: "Each checklist item needs a title"
            return
        }
        AppLog.info(
            "add_task submit_clicked titleLength=${title.length} project=${selectedProject?.id} assignee=${selectedAssignee?.id} " +
                "deadline=${deadline.receiptLabel} durationInput=$durationMinutes"
        )
        when (
            val result = AddTaskFormValidator.validate(
                title,
                selectedProject,
                selectedAssignee,
                deadline.toSelection(),
                durationMinutes,
                description = description,
                recurrencePreset = recurrencePreset,
                timeZone = java.time.ZoneId.systemDefault().id,
                checklistDrafts = checklistDrafts.map { draft ->
                    ChecklistDraft(draft.clientMutationId, draft.title.trim(), draft.description)
                }
            )
        ) {
            is AddTaskFormResult.Invalid -> {
                AppLog.warn("add_task validation_failed message=${result.message}")
                errorText = if (result.field == AddTaskInvalidField.Assignee) {
                    "Assignee is required before saving"
                } else {
                    result.message
                }
                invalidField = result.field
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Add task")

        if (projectsLoading) {
            InlineLoadingStatus("Loading projects...")
        }

        projectFeedbackMessage?.let { message ->
            Text(message)
            OutlinedButton(
                onClick = { retryProjects() },
                enabled = !submitState.formDisabled && !projectsLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Retry")
            }
        }

        CommitmentEditor(
            mode = CommitmentEditorMode.AddTask,
            title = title,
            onTitleChange = {
                title = it
                pendingCreateRequest = null
                errorText = null
                invalidField = null
            },
            titleEditable = true,
            description = description,
            onDescriptionChange = {
                description = it
                pendingCreateRequest = null
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
                        selectedAssignee = project.collaborators.firstOrNull { it.isCurrentUser && !project.canAssignTasks }
                        pendingCreateRequest = null
                        persistProjectId(project.id)
                        projectMenuExpanded = false
                        errorText = null
                        invalidField = null
                    },
                    enabled = !submitState.formDisabled && !projectsLoading && projects.isNotEmpty()
                )
            },
            assigneeContent = {
                AssigneeSelector(
                    selectedProject = selectedProject,
                    assigneeOptions = selectedProject?.collaborators?.ifEmpty { assigneeOptions } ?: assigneeOptions,
                    selectedAssignee = selectedAssignee,
                    onAssigneeSelected = { option ->
                        selectedAssignee = option
                        pendingCreateRequest = null
                        errorText = null
                        invalidField = null
                    },
                    enabled = !submitState.formDisabled
                )
            },
            start = deadline,
            onStartChange = {
                deadline = it
                pendingCreateRequest = null
                errorText = null
                invalidField = null
            },
            durationMinutes = durationMinutes,
            onDurationChange = {
                durationMinutes = it
                pendingCreateRequest = null
                errorText = null
                invalidField = null
            },
            recurrencePreset = recurrencePreset,
            onRecurrencePresetChange = {
                recurrencePreset = it
                pendingCreateRequest = null
            },
            checklistContent = {
                ChecklistEditor(
                    drafts = checklistDrafts,
                    onDraftsChange = {
                        checklistDrafts = it
                        pendingCreateRequest = null
                    },
                    nextClientMutationId = { UUID.randomUUID().toString() },
                    enabled = !submitState.formDisabled
                )
            },
            validation = CommitmentEditorValidation(
                bannerMessage = errorText,
                titleError = "Required".takeIf { invalidField == AddTaskInvalidField.Title },
                projectError = "Required".takeIf { invalidField == AddTaskInvalidField.Project },
                assigneeError = "Required".takeIf { invalidField == AddTaskInvalidField.Assignee },
                startError = "Required".takeIf { invalidField == AddTaskInvalidField.Start },
                durationError = "Required".takeIf { invalidField == AddTaskInvalidField.Duration }
            ),
            submitting = submitting,
            enabled = !submitState.formDisabled,
            submitEnabled = submitState.saveEnabled,
            submitLabel = commitmentSaveLabel(deadline.toSelection(), durationMinutes),
            loadingLabel = "Saving...",
            onSubmit = { validateAndSubmit() },
            onCancel = onBack,
            titleModifier = Modifier.testTag("titleInput"),
            modifier = Modifier.fillMaxWidth()
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
