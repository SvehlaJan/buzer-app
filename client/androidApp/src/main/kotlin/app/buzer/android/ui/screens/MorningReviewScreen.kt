package app.buzer.android.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.components.SessionWarningBanner
import app.buzer.android.ui.taskeditor.TaskEditorChoices
import app.buzer.android.ui.taskeditor.TaskEditorPresentation
import app.buzer.android.ui.taskeditor.TaskEditorUpdateScreen
import app.buzer.android.ui.taskeditor.rememberTaskEditorChoices
import app.buzer.android.ui.taskeditor.rememberTaskEditorProjectRepository
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.UpdateTaskRequest
import java.time.LocalDateTime

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
    val projectRepository = rememberTaskEditorProjectRepository(
        loadProjects = loadProjects,
        refreshProjects = refreshProjects
    )
    val choicesHandle = rememberTaskEditorChoices(
        repository = projectRepository,
        requireNonEmptyProjects = false
    )
    var repairingTask by remember { mutableStateOf<TaskSnapshotItem?>(null) }
    var editorPresentation by remember { mutableStateOf(morningReviewRepairPresentation) }

    // A successful repair updates the authoritative invalid-task list in the hosting activity.
    // Do not leave an editor open for a task that no longer requires repair.
    val currentEditingTask = repairingTask?.let { taskLookup(it.id) }
    LaunchedEffect(repairingTask?.id, currentEditingTask?.occurrenceKey, currentEditingTask?.taskRevision) {
        if (shouldCloseMorningReviewEditor(repairingTask, currentEditingTask)) {
            repairingTask = null
        }
    }

    repairingTask?.let { openedTask ->
        val liveTask = currentEditingTask?.takeIf {
            !shouldCloseMorningReviewEditor(openedTask, it)
        } ?: openedTask
        MorningReviewRepairEditor(
            task = liveTask,
            choices = choicesHandle.choices,
            onRetryChoices = choicesHandle.retry,
            onRepair = onRepair,
            onRefreshWarning = onRefreshRepairWarning,
            presentation = editorPresentation,
            onSuccess = {
                repairingTask = completeMorningReviewEditorSelection()
                onRepairSuccess(openedTask.id)
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
        contentPadding = PaddingValues(vertical = Spacing.lg),
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
                SessionWarningBanner(
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
                    Text(card.problemText)
                    card.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    when (card) {
                        is MorningReviewBlockingCard.Repair -> {
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
                        is MorningReviewBlockingCard.UnsupportedHierarchy -> {
                            TextButton(
                                onClick = onOpenTodoist,
                                enabled = !screenState.actionInFlight,
                                modifier = Modifier
                                    .testTag("morning-open-todoist-${card.taskId}")
                                    .semantics { contentDescription = "Open Todoist for ${card.title}" }
                            ) { Text("Open Todoist") }
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
    choices: TaskEditorChoices,
    onRetryChoices: () -> Unit,
    onRepair: suspend (String, UpdateTaskRequest) -> CoordinatorActionResult,
    onRefreshWarning: suspend () -> Unit,
    presentation: TaskEditorPresentation,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
    nowProvider: () -> LocalDateTime = { LocalDateTime.now() }
) {
    val now = remember(task.id, nowProvider) { nowProvider() }

    TaskEditorUpdateScreen(
        task = task,
        choices = choices,
        presentation = presentation,
        now = now,
        onRetryChoices = onRetryChoices,
        onRefreshWarning = onRefreshWarning,
        onUpdate = { request -> onRepair(task.id, request) },
        onSuccess = onSuccess,
        onCancel = onCancel,
        footerContent = { ActivityLogSection(task) },
        modifier = Modifier.fillMaxSize()
    )
}
