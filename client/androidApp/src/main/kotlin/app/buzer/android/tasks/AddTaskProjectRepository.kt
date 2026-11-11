package app.buzer.android.tasks

import app.buzer.android.enforcement.FailureClassifier
import app.buzer.android.ui.taskeditor.TaskEditorProjectRepository
import app.buzer.shared.api.BuzerApiClient
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.ProjectItem
import kotlinx.coroutines.CancellationException

class AddTaskProjectRepository(
    private val apiClient: BuzerApiClient
) : TaskEditorProjectRepository {
    // ProjectItem is the complete server projection: names, assignment capability,
    // collaborator choices, and collaborator-readiness must survive a failed refresh.
    private var cachedProjects: List<ProjectItem>? = null

    override suspend fun loadProjectsOnce(): List<ProjectItem> {
        cachedProjects?.let { return it }
        AppLog.info("add_task projects load_start")
        val projects = fetchProjects("load")
        cachedProjects = projects.toList()
        AppLog.info("add_task projects load_success count=${projects.size}")
        return projects
    }

    override suspend fun refreshProjects(): List<ProjectItem> {
        AppLog.info("add_task projects refresh_start")
        val projects = fetchProjects("refresh")
        cachedProjects = projects.toList()
        AppLog.info("add_task projects refresh_success count=${projects.size}")
        return projects
    }

    private suspend fun fetchProjects(operation: String): List<ProjectItem> =
        try {
            apiClient.getProjects().projects
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = "add_task projects ${operation}_failed type=${error::class.simpleName} message=${error.message}"
            if (FailureClassifier.isOperationalFailure(error)) {
                AppLog.warn(message, error)
            } else {
                AppLog.error(message, error)
            }
            throw error
        }
}
