package app.buzer.android.tasks

import app.buzer.shared.api.BuzerApiClient
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.ProjectItem

class AddTaskProjectRepository(
    private val apiClient: BuzerApiClient
) {
    // ProjectItem is the complete server projection: names, assignment capability,
    // collaborator choices, and collaborator-readiness must survive a failed refresh.
    private var cachedProjects: List<ProjectItem>? = null

    suspend fun loadProjectsOnce(): List<ProjectItem> {
        cachedProjects?.let { return it }
        AppLog.info("add_task projects load_start")
        val projects = apiClient.getProjects().projects
        cachedProjects = projects.toList()
        AppLog.info("add_task projects load_success count=${projects.size}")
        return projects
    }

    suspend fun refreshProjects(): List<ProjectItem> {
        AppLog.info("add_task projects refresh_start")
        val projects = apiClient.getProjects().projects
        cachedProjects = projects.toList()
        AppLog.info("add_task projects refresh_success count=${projects.size}")
        return projects
    }
}
