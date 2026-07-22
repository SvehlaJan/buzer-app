package app.buzer.android.ui.addtask

import app.buzer.android.ui.taskeditor.TaskEditorDraft
import app.buzer.android.ui.taskeditor.TaskEditorScaffoldState
import app.buzer.android.ui.taskeditor.selectProject
import app.buzer.shared.model.ProjectCollaborator
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

    /** A failed refresh never discards a previously complete project/member cache. */
    fun canUseCachedProjects(
        projects: List<ProjectItem>,
        projectLoadError: String?
    ): Boolean = projects.isNotEmpty() && projectLoadError != null

    fun assigneeUnavailable(project: ProjectItem?): Boolean =
        project?.canAssignTasks == true && !project.collaboratorsReady

    fun blocksSaveForCollaborators(project: ProjectItem?): Boolean = assigneeUnavailable(project)
}

/**
 * Applies the editor-wide selection rule for Add: retain an assignee that is valid in
 * the newly selected project, otherwise restore the project-valid current user.
 */
internal fun selectAddTaskProject(
    draft: TaskEditorDraft,
    project: ProjectItem,
    currentUser: ProjectCollaborator
): TaskEditorDraft = selectProject(
    state = TaskEditorScaffoldState(),
    draft = draft,
    project = project,
    currentUser = currentUser
).draft
