package app.buzer.android.ui.taskeditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.buzer.android.enforcement.FailureClassifier
import app.buzer.android.tasks.AddTaskDependencies
import app.buzer.android.ui.addtask.AddTaskProjectLoadFeedback
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

interface TaskEditorProjectRepository {
    suspend fun loadProjectsOnce(): List<ProjectItem>
    suspend fun refreshProjects(): List<ProjectItem>
}

internal data class TaskEditorChoices(
    val projects: List<ProjectItem> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val membershipWasRefreshed: Boolean = false
) {
    fun selectedProject(draft: TaskEditorDraft): ProjectItem? =
        projects.firstOrNull { it.id == draft.projectId }

    fun currentUser(draft: TaskEditorDraft): ProjectCollaborator =
        selectedProject(draft)?.collaborators?.firstOrNull { it.isCurrentUser }
            ?: projects.asSequence().flatMap { it.collaborators.asSequence() }.firstOrNull { it.isCurrentUser }
            ?: draft.assignee?.takeIf { it.isCurrentUser }
            ?: ProjectCollaborator(id = "", displayName = "Me", isCurrentUser = true)

    /** Loaded choices keep Project repairable even when this snapshot's selection is missing. */
    fun projectChoicesAvailable(): Boolean = projects.isNotEmpty()

    fun collaboratorsAvailable(draft: TaskEditorDraft): Boolean = selectedProject(draft)?.let { project ->
        !project.canAssignTasks || project.collaboratorsReady
    } == true

    /** Mirrors the assignee projection sent by an update request for dirty-draft comparisons. */
    fun assigneeIdForFingerprint(draft: TaskEditorDraft): String? {
        val selectedProject = selectedProject(draft)
        return draft.assignee?.id?.takeIf {
            selectedProject?.canAssignTasks == true && it.isNotBlank()
        } ?: draft.assignee?.id?.takeIf { selectedProject == null && it.isNotBlank() }
    }
}

internal typealias TaskEditorUpdateChoices = TaskEditorChoices

internal data class TaskEditorChoicesHandle(
    val choices: TaskEditorChoices,
    val feedbackMessage: String? = choices.errorMessage,
    val retry: () -> Unit
)

internal fun beginProjectChoicesLoad(
    previous: TaskEditorChoices = TaskEditorChoices()
): TaskEditorChoices = previous.copy(
    loading = true,
    errorMessage = null
)

internal fun reduceProjectChoicesLoad(
    previous: TaskEditorChoices,
    result: Result<List<ProjectItem>>,
    isRefresh: Boolean,
    requireNonEmptyProjects: Boolean = false
): TaskEditorChoices = result.fold(
    onSuccess = { loaded ->
        val projects = loaded.toList()
        val errorMessage = if (requireNonEmptyProjects) {
            AddTaskProjectLoadFeedback.message(
                projectsLoading = false,
                projects = projects,
                projectLoadError = null
            )
        } else {
            null
        }
        TaskEditorChoices(
            projects = projects,
            loading = false,
            errorMessage = errorMessage,
            membershipWasRefreshed = isRefresh || previous.membershipWasRefreshed
        )
    },
    onFailure = { error ->
        val rawError = error.message ?: "Project loading failed"
        val errorMessage = if (requireNonEmptyProjects) {
            AddTaskProjectLoadFeedback.message(
                projectsLoading = false,
                projects = previous.projects,
                projectLoadError = rawError
            )
        } else {
            rawError
        }
        previous.copy(
            loading = false,
            errorMessage = errorMessage
        )
    }
)

@Composable
internal fun rememberTaskEditorProjectRepository(
    loadProjects: (suspend () -> List<ProjectItem>)? = null,
    refreshProjects: (suspend () -> List<ProjectItem>)? = null
): TaskEditorProjectRepository {
    val context = LocalContext.current
    val appContext = context.applicationContext
    return remember(appContext, loadProjects, refreshProjects) {
        if (loadProjects == null && refreshProjects == null) {
            AddTaskDependencies.projectRepository(appContext)
        } else {
            val fallback by lazy { AddTaskDependencies.projectRepository(appContext) }
            LambdaTaskEditorProjectRepository(
                loadOverride = loadProjects,
                refreshOverride = refreshProjects,
                fallback = { fallback }
            )
        }
    }
}

@Composable
internal fun rememberTaskEditorChoices(
    repository: TaskEditorProjectRepository,
    key: Any? = Unit,
    requireNonEmptyProjects: Boolean = false,
    onProjectsLoaded: (List<ProjectItem>) -> Unit = {}
): TaskEditorChoicesHandle {
    val scope = rememberCoroutineScope()
    val currentRepository by rememberUpdatedState(repository)
    val currentOnProjectsLoaded by rememberUpdatedState(onProjectsLoaded)
    var choices by remember(key) { mutableStateOf(beginProjectChoicesLoad()) }
    var loadGeneration by remember(key) { mutableIntStateOf(0) }

    LaunchedEffect(key) {
        val generation = ++loadGeneration
        choices = beginProjectChoicesLoad()
        val result = try {
            Result.success(currentRepository.loadProjectsOnce())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
        if (generation == loadGeneration) {
            choices = reduceProjectChoicesLoad(
                previous = choices,
                result = result,
                isRefresh = false,
                requireNonEmptyProjects = requireNonEmptyProjects
            )
            result.getOrNull()?.let(currentOnProjectsLoaded)
        }
    }

    val retry: () -> Unit = {
        scope.launch {
            val generation = ++loadGeneration
            choices = beginProjectChoicesLoad(choices)
            val result = try {
                Result.success(currentRepository.refreshProjects())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (generation == loadGeneration) {
                choices = reduceProjectChoicesLoad(
                    previous = choices,
                    result = result,
                    isRefresh = true,
                    requireNonEmptyProjects = requireNonEmptyProjects
                )
                result.getOrNull()?.let(currentOnProjectsLoaded)
            }
        }
    }

    return TaskEditorChoicesHandle(choices = choices, retry = retry)
}

/**
 * Custom test/host lambdas do not pass through [app.buzer.android.tasks.AddTaskProjectRepository],
 * so only those overrides log here while repository-backed loads avoid duplicate log lines.
 */
private class LambdaTaskEditorProjectRepository(
    private val loadOverride: (suspend () -> List<ProjectItem>)?,
    private val refreshOverride: (suspend () -> List<ProjectItem>)?,
    private val fallback: () -> TaskEditorProjectRepository
) : TaskEditorProjectRepository {
    override suspend fun loadProjectsOnce(): List<ProjectItem> {
        val override = loadOverride ?: return fallback().loadProjectsOnce()
        return runCustom("load", override)
    }

    override suspend fun refreshProjects(): List<ProjectItem> {
        val override = refreshOverride ?: return fallback().refreshProjects()
        return runCustom("refresh", override)
    }

    private suspend fun runCustom(
        operation: String,
        call: suspend () -> List<ProjectItem>
    ): List<ProjectItem> = try {
        call()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        val message = "task_editor projects ${operation}_failed type=${error::class.simpleName} message=${error.message}"
        if (FailureClassifier.isOperationalFailure(error)) {
            AppLog.warn(message, error)
        } else {
            AppLog.error(message, error)
        }
        throw error
    }
}
