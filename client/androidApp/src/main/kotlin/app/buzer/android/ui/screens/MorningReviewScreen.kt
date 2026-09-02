package app.buzer.android.ui.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.tasks.AddTaskDependencies
import app.buzer.android.ui.components.ChecklistEditor
import app.buzer.android.ui.components.TaskDetailsBlock
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.status.WarningStatus
import app.buzer.android.ui.taskeditor.TaskEditorDraft
import app.buzer.android.ui.taskeditor.TaskEditorPresentation
import app.buzer.android.ui.taskeditor.TaskEditorProjectLoadStatus
import app.buzer.android.ui.taskeditor.TaskEditorUpdateChoices
import app.buzer.android.ui.taskeditor.TaskEditorUpdateContentValidation
import app.buzer.android.ui.taskeditor.TaskEditorUpdateScreen
import app.buzer.android.ui.taskeditor.initialTaskEditorUpdateDraft
import app.buzer.android.ui.taskeditor.recurrencePresetForUpdate
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


internal val morningReviewRepairPresentation = TaskEditorPresentation(
    screenTitle = "Repair task",
    primaryVerb = "Repair"
)

internal val morningReviewOverduePresentation = TaskEditorPresentation(
    screenTitle = "Edit overdue task",
    primaryVerb = "Save"
)


@Composable
fun MorningReviewScreen(
    screenState: MorningReviewScreenState,
    taskLookup: (String) -> TaskSnapshotItem?,
    onBack: () -> Unit,
    onRepair: suspend (String, UpdateTaskRequest) -> CoordinatorActionResult = { _, _ ->
        CoordinatorActionResult.Warning("unavailable", "Repair is unavailable", null)
    },
    onRepairSuccess: (String) -> Unit = {},
    onRefreshRepairWarning: suspend () -> Unit = {},
    onStart: () -> Unit = {},
    onSnooze: () -> Unit = {},
    onOverdueDone: (String) -> Unit = {},
    onOverdueEdit: (String) -> Unit = {},
    onRetryRefresh: () -> Unit = {},
    onOpenTodoist: () -> Unit = {},
    loadProjects: (suspend () -> List<ProjectItem>)? = null,
    refreshProjects: (suspend () -> List<ProjectItem>)? = null,
    nowProvider: () -> LocalDateTime = { LocalDateTime.now() }
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
    var editorPresentation by remember { mutableStateOf(morningReviewRepairPresentation) }

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
    val currentEditingTask = repairingTask?.let { taskLookup(it.id) }
    LaunchedEffect(repairingTask?.id, currentEditingTask?.occurrenceKey, currentEditingTask?.taskRevision) {
        if (shouldCloseMorningReviewEditor(repairingTask, currentEditingTask)) {
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
            onRefreshWarning = onRefreshRepairWarning,
            presentation = editorPresentation,
            onSuccess = {
                repairingTask = completeMorningReviewEditorSelection()
                onRepairSuccess(task.id)
            },
            onCancel = { repairingTask = null },
            nowProvider = nowProvider
        )
        return
    }

    val headingFocusRequester = remember { FocusRequester() }
    LaunchedEffect(screenState.phase) {
        if (screenState.phase == MorningReviewPhase.Blocking || screenState.phase == MorningReviewPhase.Summary) {
            headingFocusRequester.requestFocus()
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item("heading") {
            Text(
                screenState.headerText,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }.focusRequester(headingFocusRequester).focusable()
            )
        }
        item("date") { Text(screenState.absoluteDateLabel, modifier = Modifier.semantics { heading() }) }
        if (screenState.refreshing) item("refreshing") { Text("Refreshing…") }
        screenState.warning?.let { warning ->
            item("warning") {
                app.buzer.android.ui.components.SessionWarningBanner(
                    message = warning,
                    onRefresh = onRetryRefresh,
                    onOpenTodoist = onOpenTodoist,
                    refreshing = screenState.refreshing
                )
            }
        }
        if (screenState.phase == MorningReviewPhase.Loading) item("loading") { Text("Loading morning review…") }
        items(screenState.blockingCards, key = { "blocking-${it.taskId}" }) { card ->
            Card(modifier = Modifier.fillMaxWidth().testTag("morning-blocking-${card.taskId}")) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(card.title, style = MaterialTheme.typography.titleMedium)
                    when (card) {
                        is MorningReviewBlockingCard.Repair -> {
                            Text(card.problemText)
                            card.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            TextButton(
                                onClick = {
                                    repairingTask = taskLookup(card.taskId)
                                    editorPresentation = morningReviewRepairPresentation
                                },
                                enabled = !screenState.actionInFlight,
                                modifier = Modifier
                                    .testTag("morning-repair-${card.taskId}")
                                    .semantics { contentDescription = "Repair ${card.title}" }
                            ) { Text("Repair") }
                        }
                    }
                }
            }
        }
        if (screenState.overdueRows.isNotEmpty()) {
            item("overdue-heading") { Text("Overdue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }.testTag("morning-overdue-heading")) }
            items(screenState.overdueRows, key = { "overdue-${it.taskId}" }) { row -> AgendaRow(row, !screenState.actionInFlight, onOverdueDone, onOverdueEdit, Modifier.testTag("morning-overdue-${row.taskId}")) {
                repairingTask = taskLookup(row.taskId)
                editorPresentation = morningReviewOverduePresentation
            } }
        }
        if (screenState.todayRows.isNotEmpty()) {
            item("today-heading") { Text("Today", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }.testTag("morning-today-heading")) }
            items(screenState.todayRows, key = { "today-${it.taskId}" }) { row -> InformationalAgendaRow(row, Modifier.testTag("morning-today-${row.taskId}")) }
        }
        item("start-day") { Button(onClick = onStart, enabled = screenState.startDayEnabled && !screenState.actionInFlight, modifier = Modifier.fillMaxWidth().testTag("morning-start-day")) { Text(screenState.startDayLabel) } }
        item("snooze") { TextButton(onClick = onSnooze, enabled = screenState.snoozeEnabled && !screenState.actionInFlight, modifier = Modifier.fillMaxWidth().testTag("morning-snooze")) { Text("Snooze 5 min") } }
        if (screenState.showBack) item("back") { TextButton(onClick = onBack, enabled = !screenState.actionInFlight, modifier = Modifier.fillMaxWidth()) { Text("Back") } }
    }
}

@Composable
private fun AgendaRow(
    row: MorningReviewAgendaRow,
    actionsEnabled: Boolean,
    onDone: (String) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier,
    openEditor: () -> Unit
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium)
            Text(row.timeLabel, style = MaterialTheme.typography.bodyMedium)
            Text(row.subline, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(
            onClick = { onDone(row.taskId) },
            enabled = row.doneEnabled && actionsEnabled,
            modifier = Modifier.semantics { contentDescription = "Mark ${row.title} done" }
        ) { Text("Done") }
        TextButton(
            onClick = { onEdit(row.taskId); openEditor() },
            enabled = actionsEnabled,
            modifier = Modifier.semantics { contentDescription = "Edit ${row.title}" }
        ) { Text("Edit") }
    }
}

@Composable
private fun InformationalAgendaRow(row: MorningReviewAgendaRow, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(row.title, style = MaterialTheme.typography.titleMedium)
        Text(row.timeLabel, style = MaterialTheme.typography.bodyMedium)
        Text(row.subline, style = MaterialTheme.typography.bodySmall)
    }
}

internal fun completeMorningReviewEditorSelection(): TaskSnapshotItem? = null

internal fun shouldCloseMorningReviewEditor(opened: TaskSnapshotItem?, current: TaskSnapshotItem?): Boolean =
    opened != null && (current == null || opened.occurrenceKey != current.occurrenceKey || opened.taskRevision != current.taskRevision)

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
    onRefreshWarning: suspend () -> Unit,
    presentation: TaskEditorPresentation,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    nowProvider: () -> LocalDateTime = { LocalDateTime.now() }
) {
    var existingDrafts by remember(task.id) {
        mutableStateOf(task.checklistItems.sortedBy { it.order }.map(ChecklistItem::toEditorDraft))
    }
    var deletedChildren by remember(task.id) { mutableStateOf<Set<String>>(emptySet()) }
    var addedDrafts by remember(task.id) { mutableStateOf<List<ChecklistEditorDraft>>(emptyList()) }
    var capturedUpdate by remember(task.id) { mutableStateOf<EditTaskUpdateCapture?>(null) }
    val now = remember(task.id, nowProvider) { nowProvider() }
    val initialDraft = remember(task.id) { initialTaskEditorUpdateDraft(task) }
    val initialFingerprint = remember(task.id, choices) {
        editTaskDraftFingerprint(
            title = initialDraft.title,
            description = initialDraft.description,
            projectId = initialDraft.projectId,
            assigneeId = choices.assigneeIdForFingerprint(initialDraft),
            start = DateTimeDraftSelection(
                initialDraft.schedule.date,
                initialDraft.schedule.hour,
                initialDraft.schedule.minute
            ),
            durationMinutes = initialDraft.schedule.durationMinutes,
            recurrencePreset = initialDraft.recurrencePresetForUpdate(),
            existingChildren = task.checklistItems.sortedBy { it.order }.map {
                ChecklistDraftFingerprint(it.id, it.title, it.description, it.checked)
            },
            deletedChildIds = emptySet(),
            addedChildren = emptyList()
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
            presentation = presentation,
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
            onRefreshWarning = onRefreshWarning,
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
                onRepair(task.id, nextCapture.request)
            },
            onSuccess = onSuccess,
            onCancel = onCancel,
            modifier = Modifier.fillMaxSize()
        )
        TaskEditorProjectLoadStatus(
            loading = choices.loading,
            message = choices.errorMessage,
            onRetry = onRetryChoices
        )
    }
}
