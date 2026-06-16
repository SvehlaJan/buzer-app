package app.buzer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.buzer.android.tasks.AddTaskDependencies
import app.buzer.android.ui.addtask.AddTaskAssigneeOption
import app.buzer.android.ui.addtask.AddTaskFormResult
import app.buzer.android.ui.addtask.AddTaskFormValidator
import app.buzer.android.ui.addtask.AddTaskProjectLoadFeedback
import app.buzer.android.ui.addtask.AddTaskSubmitState
import app.buzer.android.ui.addtask.runSuccessfulAddTaskCreate
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.datetime.datePickerMillisToLocalDate
import app.buzer.android.ui.datetime.localDateToDatePickerMillis
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.status.LoadingActionButton
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ProjectItem
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskScreen(
    onBack: () -> Unit,
    onTaskCreated: () -> Unit = {},
    createTask: suspend (CreateTaskRequest) -> Unit = AddTaskDependencies.createTaskAction(),
    assigneeOptions: List<AddTaskAssigneeOption> = AddTaskDependencies.assigneeOptions(),
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
    var selectedAssignee by remember { mutableStateOf<AddTaskAssigneeOption?>(null) }
    var deadline by remember { mutableStateOf<DateTimeSelection?>(null) }
    var durationMinutes by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var submitDialogMessage by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
    val defaultDateTime = remember { LocalDateTime.now().plusHours(1) }
    val projectFeedbackMessage = AddTaskProjectLoadFeedback.message(
        projectsLoading = projectsLoading,
        projects = projects,
        projectLoadError = projectLoadError
    )
    val submitState = AddTaskSubmitState(
        submitting = submitting,
        projectsLoading = projectsLoading,
        projectLoadError = projectLoadError
    )

    fun applyLoadedProjects(loaded: List<ProjectItem>) {
        projects = loaded
        val preferredProjectId = selectedProject?.id ?: persistedProjectId
        selectedProject = loaded.firstOrNull { it.id == preferredProjectId }
        if (selectedProject?.canAssignTasks != true) {
            selectedAssignee = null
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
                errorText = message
                submitDialogMessage = message
            } finally {
                AppLog.debug("add_task create_finished submitting=false")
                submitting = false
            }
        }
    }

    fun validateAndSubmit() {
        if (submitting) return
        AppLog.info(
            "add_task submit_clicked titleLength=${title.length} project=${selectedProject?.id} assignee=${selectedAssignee?.id} " +
                "deadline=${deadline?.formatForDisplay()} durationInput=$durationMinutes"
        )
        when (
            val result = AddTaskFormValidator.validate(
                title,
                selectedProject,
                selectedAssignee,
                deadline,
                durationMinutes
            )
        ) {
            is AddTaskFormResult.Invalid -> {
                AppLog.warn("add_task validation_failed message=${result.message}")
                errorText = result.message
            }
            is AddTaskFormResult.Valid -> submitRequest(result.request)
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
        Text("Add committed task")
        OutlinedTextField(
            value = title,
            onValueChange = {
                title = it
                errorText = null
            },
            enabled = !submitState.formDisabled,
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Title input" }
        )

        if (projectsLoading) {
            InlineLoadingStatus("Loading projects...")
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { projectMenuExpanded = true },
                enabled = !submitState.formDisabled && !projectsLoading && projects.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Project picker" }
            ) {
                Text(
                    when {
                        projectsLoading -> "Loading projects..."
                        selectedProject != null -> selectedProject!!.name
                        else -> "Select project"
                    }
                )
            }
            DropdownMenu(
                expanded = projectMenuExpanded,
                onDismissRequest = { projectMenuExpanded = false }
            ) {
                projects.forEach { project ->
                    DropdownMenuItem(
                        text = {
                            Text(if (project.canAssignTasks) project.name else "${project.name} (personal)")
                        },
                        onClick = {
                            selectedProject = project
                            selectedAssignee = null
                            persistProjectId(project.id)
                            projectMenuExpanded = false
                            errorText = null
                        },
                        enabled = !submitState.formDisabled
                    )
                }
            }
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

        if (selectedProject?.canAssignTasks == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                assigneeOptions.forEach { option ->
                    val selected = selectedAssignee == option
                    val label = if (selected) "${option.label} selected" else option.label
                    if (selected) {
                        Button(
                            onClick = { selectedAssignee = option },
                            enabled = !submitState.formDisabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                selectedAssignee = option
                                errorText = null
                            },
                            enabled = !submitState.formDisabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                showDatePicker = true
                errorText = null
            },
            enabled = !submitState.formDisabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Deadline picker" }
        ) {
            Text(deadline?.formatForDisplay() ?: "No deadline selected")
        }

        OutlinedTextField(
            value = durationMinutes,
            onValueChange = {
                durationMinutes = it
                errorText = null
            },
            enabled = !submitState.formDisabled,
            label = { Text("Duration minutes") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Duration minutes input" }
        )

        errorText?.let { Text(it) }

        LoadingActionButton(
            label = "Save task",
            loadingLabel = "Saving...",
            loading = submitting,
            onClick = { validateAndSubmit() },
            enabled = submitState.saveEnabled,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = onBack,
            enabled = !submitState.formDisabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }
    }

    val initialDate = deadline?.date ?: LocalDate.now()
    val initialHour = deadline?.hour ?: defaultDateTime.hour
    val initialMinute = deadline?.minute ?: 0

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = localDateToDatePickerMillis(initialDate)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedMillis ->
                            pendingDate = datePickerMillisToLocalDate(selectedMillis)
                            showDatePicker = false
                            showTimePicker = true
                        }
                    },
                    enabled = !submitState.formDisabled && datePickerState.selectedDateMillis != null
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = initialHour,
            initialMinute = initialMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select deadline time") },
            text = {
                TimePicker(state = timePickerState)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deadline = DateTimeSelection(
                            date = pendingDate ?: initialDate,
                            hour = timePickerState.hour,
                            minute = timePickerState.minute
                        )
                        showTimePicker = false
                        pendingDate = null
                        errorText = null
                    },
                    enabled = !submitState.formDisabled
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTimePicker = false
                        pendingDate = null
                    }
                ) {
                    Text("Cancel")
                }
            }
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
        ErrorAlertDialog(
            title = "Task was not saved",
            message = message,
            onDismiss = { submitDialogMessage = null },
            retryLabel = "Retry Save",
            onRetry = {
                submitDialogMessage = null
                validateAndSubmit()
            }
        )
    }
}
