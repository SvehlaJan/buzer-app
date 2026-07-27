package app.buzer.android.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.buzer.android.tasks.AddTaskDependencies
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.ui.addtask.AddTaskFailurePresentation
import app.buzer.android.ui.addtask.AddTaskProjectLoadFeedback
import app.buzer.android.ui.addtask.selectAddTaskProject
import app.buzer.android.ui.addtask.runSuccessfulAddTaskCreate
import app.buzer.android.ui.components.ChecklistEditor
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.taskeditor.TaskEditorDraft
import app.buzer.android.ui.taskeditor.TaskEditorField
import app.buzer.android.ui.taskeditor.TaskEditorPresentation
import app.buzer.android.ui.taskeditor.TaskEditorProjectLoadStatus
import app.buzer.android.ui.taskeditor.TaskEditorScaffold
import app.buzer.android.ui.taskeditor.TaskEditorValidation
import app.buzer.android.ui.taskeditor.ValidationExposure
import app.buzer.android.ui.taskeditor.VisibleTaskEditorValidation
import app.buzer.android.ui.taskeditor.primaryActionLabel
import app.buzer.android.ui.taskeditor.validateTaskEditor
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ChecklistDraft
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.ui.taskeditor.ChecklistEditorDraft
import app.buzer.shared.ui.taskeditor.validateChecklistDrafts
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.launch

internal fun addTaskEditorFingerprint(
    draft: TaskEditorDraft,
    selectedProject: ProjectItem?,
    checklistDrafts: List<ChecklistEditorDraft>
): AddTaskDraftFingerprint = addTaskDraftFingerprint(
    title = draft.title,
    description = draft.description,
    projectId = draft.projectId,
    assigneeId = draft.assignee?.id?.takeIf { selectedProject?.canAssignTasks == true },
    start = DateTimeDraftSelection(draft.schedule.date, draft.schedule.hour, draft.schedule.minute),
    durationMinutes = draft.schedule.durationMinutes,
    recurrencePreset = draft.recurrencePreset,
    checklistItems = checklistDrafts.map { draftItem ->
        ChecklistDraftFingerprint(
            stableId = draftItem.clientMutationId,
            title = draftItem.title,
            description = draftItem.description,
            checked = false
        )
    }
)

/** The Add host's interaction-only projection over the common editor validation. */
internal data class AddTaskEditorState(
    val draft: TaskEditorDraft,
    val selectedProject: ProjectItem?,
    val currentUser: ProjectCollaborator,
    val validation: TaskEditorValidation,
    val visibleValidation: VisibleTaskEditorValidation,
    val editorVisible: Boolean,
    val editingEnabled: Boolean,
    val canSubmit: Boolean,
    val primaryActionEnabled: Boolean,
    val primaryActionLabel: String
)

internal fun addTaskEditorState(
    draft: TaskEditorDraft,
    projects: List<ProjectItem>,
    projectsLoading: Boolean,
    projectLoadError: String?,
    submitting: Boolean,
    touchedFields: Set<TaskEditorField>,
    submitAttempted: Boolean
): AddTaskEditorState {
    val selectedProject = projects.firstOrNull { it.id == draft.projectId }
    val currentUser = selectedProject?.collaborators?.firstOrNull { it.isCurrentUser }
        ?: projects.asSequence()
            .flatMap { it.collaborators.asSequence() }
            .firstOrNull { it.isCurrentUser }
        ?: ProjectCollaborator(id = "", displayName = "Me", isCurrentUser = true)
    val projectChoicesAvailable = projects.isNotEmpty()
    val collaboratorsAvailable = selectedProject?.let { project ->
        !project.canAssignTasks || project.collaboratorsReady
    } ?: true
    val validationDraft = if (draft.projectId != null && selectedProject == null) {
        draft.copy(projectId = null)
    } else {
        draft
    }
    val validation = validateTaskEditor(
        draft = validationDraft,
        selectedProject = selectedProject,
        projectAvailable = projectChoicesAvailable,
        collaboratorsAvailable = collaboratorsAvailable
    )
    val visibleValidation = validation.visible(
        exposure = ValidationExposure.OnInteraction,
        touchedFields = touchedFields,
        submitAttempted = submitAttempted
    )
    val choicesReady = !projectsLoading &&
        projectChoicesAvailable &&
        (projectLoadError == null || AddTaskProjectLoadFeedback.canUseCachedProjects(projects, projectLoadError)) &&
        !AddTaskProjectLoadFeedback.blocksSaveForCollaborators(selectedProject)
    val canSubmit = choicesReady && validation.canSubmit
    val primaryActionEnabled = !submitting && choicesReady && (
        validation.canSubmit || (!submitAttempted && visibleValidation.errors.isEmpty())
    )

    return AddTaskEditorState(
        draft = draft,
        selectedProject = selectedProject,
        currentUser = currentUser,
        validation = validation,
        visibleValidation = visibleValidation.copy(canSubmit = primaryActionEnabled),
        editorVisible = true,
        editingEnabled = !submitting,
        canSubmit = canSubmit,
        primaryActionEnabled = primaryActionEnabled,
        primaryActionLabel = primaryActionLabel("Add", draft)
    )
}

internal fun addTaskChangedFields(
    previous: TaskEditorDraft,
    next: TaskEditorDraft
): Set<TaskEditorField> = buildSet {
    if (previous.title != next.title) add(TaskEditorField.Title)
    if (previous.projectId != next.projectId) add(TaskEditorField.Project)
    if (previous.assignee != next.assignee) add(TaskEditorField.Assignee)
    if (previous.schedule != next.schedule) add(TaskEditorField.Schedule)
    if (previous.recurrencePreset != next.recurrencePreset ||
        previous.repeatReplacementSelected != next.repeatReplacementSelected
    ) {
        add(TaskEditorField.Repeat)
    }
}

internal fun addTaskTouchedFields(
    previous: TaskEditorDraft,
    next: TaskEditorDraft,
    userInitiated: Boolean
): Set<TaskEditorField> = if (userInitiated) addTaskChangedFields(previous, next) else emptySet()

/** Mirrors the established Create payload exactly; callers invoke it only after shared validation. */
internal fun addTaskCreateRequest(
    draft: TaskEditorDraft,
    selectedProject: ProjectItem,
    checklistDrafts: List<ChecklistDraft>,
    actionId: String,
    deviceId: String,
    zoneId: ZoneId = ZoneId.systemDefault()
): CreateTaskRequest {
    val deadline = draft.schedule.startDateTime()?.let { start ->
        DateTimeSelection(start.toLocalDate(), start.hour, start.minute)
    }
    return CreateTaskRequest(
        title = draft.title.trim(),
        description = draft.description.trim().takeIf { it.isNotEmpty() },
        projectId = selectedProject.id,
        assigneeId = draft.assignee?.id?.takeIf { selectedProject.canAssignTasks },
        dueDatetime = deadline?.toIsoInstantString(zoneId),
        durationMinutes = draft.schedule.durationMinutes,
        actionId = actionId,
        deviceId = deviceId,
        recurrencePreset = draft.recurrencePreset,
        timeZone = zoneId.id.takeIf {
            draft.schedule.isComplete() && draft.recurrencePreset != app.buzer.shared.model.RecurrencePreset.never
        },
        checklistDrafts = checklistDrafts
    )
}

internal fun retainAddTaskCreateRequest(
    pendingRequest: CreateTaskRequest?,
    create: () -> CreateTaskRequest
): CreateTaskRequest = pendingRequest ?: create()

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
    val focusManager = LocalFocusManager.current
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
    var touchedFields by remember { mutableStateOf<Set<TaskEditorField>>(emptySet()) }
    var submitAttempted by remember { mutableStateOf(false) }
    var submitDialogMessage by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var pendingCreateRequest by remember { mutableStateOf<CreateTaskRequest?>(null) }
    var checklistDrafts by remember { mutableStateOf<List<ChecklistEditorDraft>>(emptyList()) }
    var discardConfirmationVisible by remember { mutableStateOf(false) }
    var initialFingerprint by remember { mutableStateOf(AddTaskDraftFingerprint.empty(projectId = null)) }
    var initialProjectBaselineCaptured by remember { mutableStateOf(false) }
    val now = remember(nowProvider) { nowProvider() }
    val deviceId = remember(context) {
        EnforcementDependencies.deviceIdentityStore(context.applicationContext).deviceId()
    }
    val projectLoadStatusMessage = AddTaskProjectLoadFeedback.message(
        projectsLoading = projectsLoading,
        projects = projects,
        projectLoadError = projectLoadError
    )
    val editorState = addTaskEditorState(
        draft = draft,
        projects = projects,
        projectsLoading = projectsLoading,
        projectLoadError = projectLoadError,
        submitting = submitting,
        touchedFields = touchedFields,
        submitAttempted = submitAttempted
    )
    val selectedProject = editorState.selectedProject
    val currentUser = editorState.currentUser

    fun updateDraft(next: TaskEditorDraft, userInitiated: Boolean = true) {
        if (draft == next) return
        touchedFields += addTaskTouchedFields(draft, next, userInitiated)
        draft = next
        pendingCreateRequest = null
    }

    fun applyLoadedProjects(loaded: List<ProjectItem>) {
        projects = loaded
        val preferredProjectId = draft.projectId ?: persistedProjectId
        val resolvedProject = loaded.firstOrNull { it.id == preferredProjectId }
        var baselineDraft = draft
        var automaticPersistedProject: ProjectItem? = null
        if (resolvedProject != null) {
            val resolvedCurrentUser = resolvedProject.collaborators.firstOrNull { it.isCurrentUser }
                ?: loaded.asSequence()
                    .flatMap { it.collaborators.asSequence() }
                    .firstOrNull { it.isCurrentUser }
            if (resolvedCurrentUser != null) {
                baselineDraft = selectAddTaskProject(draft, resolvedProject, resolvedCurrentUser)
                automaticPersistedProject = resolvedProject.takeIf {
                    draft.projectId == null && it.id == persistedProjectId
                }
                updateDraft(baselineDraft, userInitiated = false)
                persistProjectId(resolvedProject.id)
            }
        }
        val baselineCapture = captureAddTaskProjectBaseline(
            initialFingerprint = initialFingerprint,
            alreadyCaptured = initialProjectBaselineCaptured,
            automaticPersistedProjectId = automaticPersistedProject?.id,
            automaticPersistedAssigneeId = baselineDraft.assignee?.id?.takeIf {
                automaticPersistedProject?.canAssignTasks == true
            },
            deferNoProjectBaseline = shouldDeferNoProjectBaseline(
                persistedProjectId = persistedProjectId,
                draftProjectId = draft.projectId,
                automaticPersistedProjectId = automaticPersistedProject?.id
            )
        )
        initialFingerprint = baselineCapture.fingerprint
        initialProjectBaselineCaptured = baselineCapture.captured
    }

    fun retryProjects() {
        scope.launch {
            projectsLoading = true
            projectLoadError = null
            try {
                applyLoadedProjects(refreshProjectList())
            } catch (error: Exception) {
                AppLog.error(
                    "add_task projects refresh_failed type=${error::class.simpleName} message=${error.message}",
                    error
                )
                val message = error.message ?: "Project loading failed"
                projectLoadError = message
            } finally {
                projectsLoading = false
            }
        }
    }

    fun submitRequest(request: CreateTaskRequest) {
        if (submitting) return
        submitting = true
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
        submitAttempted = true
        val checklistValidation = validateChecklistDrafts(checklistDrafts)
        val submissionState = addTaskEditorState(
            draft = draft,
            projects = projects,
            projectsLoading = projectsLoading,
            projectLoadError = projectLoadError,
            submitting = submitting,
            touchedFields = touchedFields,
            submitAttempted = true
        )
        if (!submissionState.canSubmit ||
            checklistValidation.bannerMessage != null ||
            checklistValidation.invalidDraftIds.isNotEmpty()
        ) {
            AppLog.warn("add_task validation_failed")
            return
        }
        AppLog.info(
            "add_task submit_clicked titleLength=${draft.title.length} project=${selectedProject?.id} assignee=${draft.assignee?.id} " +
                "due=${draft.schedule.startDateTime() != null} durationInput=${draft.schedule.durationMinutes}"
        )
        val request = retainAddTaskCreateRequest(pendingCreateRequest) {
            addTaskCreateRequest(
                draft = draft,
                selectedProject = submissionState.selectedProject!!,
                checklistDrafts = checklistDrafts.map { item ->
                    ChecklistDraft(item.clientMutationId, item.title.trim(), item.description)
                },
                actionId = UUID.randomUUID().toString(),
                deviceId = deviceId
            )
        }.also { pendingCreateRequest = it }
        submitRequest(request)
    }

    fun currentFingerprint() = addTaskEditorFingerprint(
        draft = draft,
        selectedProject = selectedProject,
        checklistDrafts = checklistDrafts
    )

    fun requestExit() {
        when (decideDraftExit(initialFingerprint, currentFingerprint(), submitting)) {
            DraftExitDecision.NavigateHome -> onBack()
            DraftExitDecision.ConfirmDiscard -> {
                focusManager.clearFocus(force = true)
                discardConfirmationVisible = true
            }
            DraftExitDecision.Ignore -> Unit
        }
    }

    BackHandler(enabled = discardConfirmationVisible) {
        discardConfirmationVisible = false
    }
    BackHandler(enabled = !discardConfirmationVisible) {
        requestExit()
    }

    LaunchedEffect(Unit) {
        projectsLoading = true
        projectLoadError = null
        try {
            applyLoadedProjects(loadProjectList())
        } catch (error: Exception) {
            AppLog.error("add_task projects load_failed type=${error::class.simpleName} message=${error.message}", error)
            val message = error.message ?: "Project loading failed"
            projectLoadError = message
        } finally {
            projectsLoading = false
        }
    }

    val checklistValidation = validateChecklistDrafts(checklistDrafts)
    val checklistError = checklistValidation.bannerMessage
        ?: "Each checklist item needs a title".takeIf { checklistValidation.invalidDraftIds.isNotEmpty() }
    val visibleValidation = editorState.visibleValidation.copy(
        canSubmit = editorState.primaryActionEnabled && (!submitAttempted || checklistError == null),
        bannerMessage = editorState.visibleValidation.bannerMessage
            ?: checklistError?.takeIf { submitAttempted }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        TaskEditorScaffold(
            presentation = TaskEditorPresentation(
                screenTitle = "Add task",
                primaryVerb = "Add"
            ),
            draft = draft,
            onDraftChange = { next ->
                if (next.projectId != draft.projectId) {
                    projects.firstOrNull { it.id == next.projectId }?.let { project ->
                        persistProjectId(project.id)
                    }
                }
                updateDraft(next)
            },
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
                        }
                    },
                    nextClientMutationId = { UUID.randomUUID().toString() },
                    enabled = editorState.editingEnabled
                )
            },
            onRetryCollaborators = { retryProjects() },
            onPrimaryAction = ::validateAndSubmit,
            onCancel = ::requestExit,
            validation = visibleValidation,
            enabled = editorState.editingEnabled,
            primaryActionInProgress = submitting,
            titleModifier = Modifier.testTag("titleInput"),
            modifier = Modifier.fillMaxSize()
        )

        TaskEditorProjectLoadStatus(
            loading = projectsLoading,
            message = projectLoadStatusMessage,
            onRetry = ::retryProjects
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
    if (discardConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { discardConfirmationVisible = false },
            title = { Text("Discard changes?") },
            text = { Text("Your new task has not been saved.") },
            confirmButton = {
                TextButton(onClick = onBack) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { discardConfirmationVisible = false }) { Text("Keep editing") }
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
