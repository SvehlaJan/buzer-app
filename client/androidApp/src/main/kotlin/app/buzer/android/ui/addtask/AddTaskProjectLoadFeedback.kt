package app.buzer.android.ui.addtask

import app.buzer.shared.model.ProjectItem

object AddTaskProjectLoadFeedback {
    fun message(
        projectsLoading: Boolean,
        projects: List<ProjectItem>,
        projectLoadError: String?
    ): String? =
        when {
            projectsLoading -> null
            projectLoadError != null -> projectLoadError
            projects.isEmpty() -> "No projects available. Check Tailscale/backend sync, then retry."
            else -> null
        }
}
