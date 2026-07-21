package app.buzer.android.ui.screens

import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import kotlin.test.Test
import kotlin.test.assertEquals

class AddTaskAssigneePresentationTest {
    @Test
    fun personalProjectUsesLockedCurrentUserWithClearHint() {
        val presentation = personalProjectAssigneePresentation(
            selectedProject = ProjectItem(
                id = "project-personal",
                name = "Personal",
                canAssignTasks = false,
                shared = false,
                isInboxProject = false,
                collaborators = listOf(ProjectCollaborator("user-me", "Me", isCurrentUser = true))
            ),
            selectedAssignee = ProjectCollaborator("user-me", "Me", isCurrentUser = true),
            assigneeOptions = emptyList()
        )

        assertEquals(
            PersonalProjectAssigneePresentation(
                displayName = "Me",
                hint = "Personal project · assigned to you"
            ),
            presentation
        )
    }

    @Test
    fun sharedProjectDoesNotUsePersonalAssigneePresentation() {
        assertEquals(
            null,
            personalProjectAssigneePresentation(
                selectedProject = ProjectItem(
                    id = "project-shared",
                    name = "Shared",
                    canAssignTasks = true,
                    shared = true,
                    isInboxProject = false
                ),
                selectedAssignee = null,
                assigneeOptions = emptyList()
            )
        )
    }
}
