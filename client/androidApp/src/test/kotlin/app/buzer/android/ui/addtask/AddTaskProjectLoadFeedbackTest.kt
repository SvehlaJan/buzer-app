package app.buzer.android.ui.addtask

import app.buzer.shared.model.ProjectItem
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
