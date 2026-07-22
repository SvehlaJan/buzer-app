package app.buzer.android.ui.addtask

import app.buzer.android.ui.taskeditor.TaskEditorDraft
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddTaskProjectLoadFeedbackTest {
    @Test
    fun showsRetryableMessageWhenProjectListIsEmptyAfterLoading() {
        val message = AddTaskProjectLoadFeedback.message(
            projectsLoading = false,
            projects = emptyList(),
            projectLoadError = null
        )

        assertEquals("No projects available. Check Tailscale/backend sync, then retry.", message)
    }

    @Test
    fun keepsMessageHiddenWhileProjectsAreLoadingOrAvailable() {
        assertEquals(
            null,
            AddTaskProjectLoadFeedback.message(
                projectsLoading = true,
                projects = emptyList(),
                projectLoadError = null
            )
        )
        assertEquals(
            null,
            AddTaskProjectLoadFeedback.message(
                projectsLoading = false,
                projects = listOf(ProjectItem("project", "Project", canAssignTasks = true, shared = true, isInboxProject = false)),
                projectLoadError = null
            )
        )
    }

    @Test
    fun showsLoadErrorWhenPresent() {
        assertEquals(
            "Network unavailable",
            AddTaskProjectLoadFeedback.message(
                projectsLoading = false,
                projects = emptyList(),
                projectLoadError = "Network unavailable"
            )
        )
    }

    @Test
    fun retainsCachedProjectsAfterRefreshFailureSoTheirCollaboratorsRemainUsable() {
        val cachedSharedProject = sharedProject()

        assertTrue(
            AddTaskProjectLoadFeedback.canUseCachedProjects(
                projects = listOf(cachedSharedProject),
                projectLoadError = "Network unavailable"
            )
        )
        assertFalse(
            AddTaskProjectLoadFeedback.canUseCachedProjects(
                projects = emptyList(),
                projectLoadError = "Network unavailable"
            )
        )
    }

    @Test
    fun sharedProjectWithoutReadyMembershipDisablesAssigneeAndBlocksSave() {
        val project = sharedProject(collaboratorsReady = false)

        assertTrue(AddTaskProjectLoadFeedback.assigneeUnavailable(project))
        assertTrue(AddTaskProjectLoadFeedback.blocksSaveForCollaborators(project))
    }

    @Test
    fun sharedProjectSelectionRestoresMeWhenWifeIsNotValidForTheNewProject() {
        val me = ProjectCollaborator(id = "user-me", displayName = "Me", isCurrentUser = true)
        val wife = ProjectCollaborator(id = "user-wife", displayName = "Wife", isCurrentUser = false)
        val nextProject = sharedProject(collaborators = listOf(me))

        val selection = selectAddTaskProject(
            draft = TaskEditorDraft(title = "Task", projectId = "previous", assignee = wife),
            project = nextProject,
            currentUser = me
        )

        assertEquals(nextProject.id, selection.projectId)
        assertEquals(me, selection.assignee)
    }

    @Test
    fun sharedProjectSelectionDefaultsToMeAndRetainsAValidWifeSelection() {
        val me = ProjectCollaborator(id = "user-me", displayName = "Me", isCurrentUser = true)
        val wife = ProjectCollaborator(id = "user-wife", displayName = "Wife", isCurrentUser = false)
        val project = sharedProject(collaborators = listOf(me, wife))

        val initialSelection = selectAddTaskProject(
            draft = TaskEditorDraft(title = "Task"),
            project = project,
            currentUser = me
        )
        val retainedSelection = selectAddTaskProject(
            draft = initialSelection.copy(assignee = wife),
            project = project,
            currentUser = me
        )

        assertEquals(me, initialSelection.assignee)
        assertEquals(wife, retainedSelection.assignee)
    }

    @Test
    fun personalProjectSelectionShowsLockedMeForAnUnassignedRequest() {
        val me = ProjectCollaborator(id = "user-me", displayName = "Me", isCurrentUser = true)
        val personalProject = ProjectItem(
            id = "project-personal",
            name = "Personal",
            canAssignTasks = false,
            shared = false,
            isInboxProject = false,
            collaborators = listOf(me),
            collaboratorsReady = true
        )

        val selection = selectAddTaskProject(
            draft = TaskEditorDraft(title = "Task"),
            project = personalProject,
            currentUser = me
        )

        assertEquals(me, selection.assignee)
    }

    private fun sharedProject(
        collaboratorsReady: Boolean = true,
        collaborators: List<ProjectCollaborator> = listOf(
            ProjectCollaborator(id = "user-me", displayName = "Me", isCurrentUser = true),
            ProjectCollaborator(id = "user-wife", displayName = "Wife", isCurrentUser = false)
        )
    ) = ProjectItem(
        id = "project-shared",
        name = "Shared",
        canAssignTasks = true,
        shared = true,
        isInboxProject = false,
        collaborators = collaborators,
        collaboratorsReady = collaboratorsReady
    )
}
