package app.buzer.android.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.taskeditor.TaskEditorPresentation
import app.buzer.android.ui.taskeditor.TaskEditorUpdateScreen
import app.buzer.android.ui.taskeditor.rememberTaskEditorChoices
import app.buzer.android.ui.taskeditor.rememberTaskEditorProjectRepository
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.ui.taskeditor.ChecklistEditorDraft
import java.time.LocalDateTime

@Composable
fun EditTaskScreen(
    task: TaskSnapshotItem,
    initialAddedDrafts: List<ChecklistEditorDraft> = emptyList(),
    onBack: () -> Unit,
    onUpdate: suspend (String, UpdateTaskRequest) -> CoordinatorActionResult,
    onRefreshWarning: suspend () -> Unit = {},
    loadProjects: (suspend () -> List<ProjectItem>)? = null,
    refreshProjects: (suspend () -> List<ProjectItem>)? = null,
    nowProvider: () -> LocalDateTime = { LocalDateTime.now() },
    modifier: Modifier = Modifier
) {
    val projectRepository = rememberTaskEditorProjectRepository(
        loadProjects = loadProjects,
        refreshProjects = refreshProjects
    )
    val choicesHandle = rememberTaskEditorChoices(
        repository = projectRepository,
        key = task.id,
        requireNonEmptyProjects = false
    )
    val now = remember(nowProvider) { nowProvider() }

    TaskEditorUpdateScreen(
        task = task,
        choices = choicesHandle.choices,
        presentation = TaskEditorPresentation(
            screenTitle = "Edit task",
            primaryVerb = "Save"
        ),
        now = now,
        initialAddedDrafts = initialAddedDrafts,
        onRetryChoices = choicesHandle.retry,
        onRefreshWarning = onRefreshWarning,
        onUpdate = { request -> onUpdate(task.id, request) },
        onSuccess = onBack,
        onCancel = onBack,
        footerContent = { ActivityLogSection(task) },
        modifier = modifier.fillMaxSize()
    )
}
