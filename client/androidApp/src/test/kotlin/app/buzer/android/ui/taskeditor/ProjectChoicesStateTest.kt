package app.buzer.android.ui.taskeditor

import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectChoicesStateTest {
    @Test
    fun updateEditorKeepsCachedProjectsSelectableAndExposesErrorMessageOnRefreshFailure() {
        val loadingInitial = beginProjectChoicesLoad()
        assertTrue(loadingInitial.loading)
        assertNull(loadingInitial.errorMessage)

        val loaded = reduceProjectChoicesLoad(
            previous = loadingInitial,
            result = Result.success(listOf(sharedProject)),
            isRefresh = false,
            requireNonEmptyProjects = false
        )
        assertEquals(listOf(sharedProject), loaded.projects)
        assertFalse(loaded.loading)
        assertNull(loaded.errorMessage)
        assertFalse(loaded.membershipWasRefreshed)

        val refreshing = beginProjectChoicesLoad(loaded)
        assertTrue(refreshing.loading)
        assertEquals(listOf(sharedProject), refreshing.projects)
        assertNull(refreshing.errorMessage)

        val failedRefresh = reduceProjectChoicesLoad(
            previous = refreshing,
            result = Result.failure(IllegalStateException("Backend 503")),
            isRefresh = true,
            requireNonEmptyProjects = false
        )
        val draft = TaskEditorDraft(title = "Task", projectId = sharedProject.id, assignee = me)

        assertEquals(listOf(sharedProject), failedRefresh.projects)
        assertTrue(failedRefresh.projectChoicesAvailable())
        assertTrue(failedRefresh.collaboratorsAvailable(draft))
        assertFalse(failedRefresh.loading)
        assertEquals("Backend 503", failedRefresh.errorMessage)
        assertFalse(failedRefresh.membershipWasRefreshed)
    }

    @Test
    fun updateEditorDoesNotShowWarningWhenProjectsListIsEmptyWithoutError() {
        val loadedEmpty = reduceProjectChoicesLoad(
            previous = beginProjectChoicesLoad(),
            result = Result.success(emptyList()),
            isRefresh = false,
            requireNonEmptyProjects = false
        )

        assertTrue(loadedEmpty.projects.isEmpty())
        assertFalse(loadedEmpty.loading)
        assertNull(loadedEmpty.errorMessage)
    }

    @Test
    fun addTaskEditorShowsNoProjectsAvailableMessageWhenLoadedEmpty() {
        val loadedEmpty = reduceProjectChoicesLoad(
            previous = beginProjectChoicesLoad(),
            result = Result.success(emptyList()),
            isRefresh = false,
            requireNonEmptyProjects = true
        )

        assertTrue(loadedEmpty.projects.isEmpty())
        assertFalse(loadedEmpty.loading)
        assertEquals(
            "No projects available. Check Tailscale/backend sync, then retry.",
            loadedEmpty.errorMessage
        )
    }

    @Test
    fun successfulRefreshMarksMembershipRefreshedAndClearsPreviousError() {
        val failedInitial = reduceProjectChoicesLoad(
            previous = beginProjectChoicesLoad(),
            result = Result.failure(IllegalStateException("Offline")),
            isRefresh = false,
            requireNonEmptyProjects = true
        )
        assertEquals("Offline", failedInitial.errorMessage)

        val recovered = reduceProjectChoicesLoad(
            previous = beginProjectChoicesLoad(failedInitial),
            result = Result.success(listOf(sharedProject)),
            isRefresh = true,
            requireNonEmptyProjects = true
        )

        assertEquals(listOf(sharedProject), recovered.projects)
        assertFalse(recovered.loading)
        assertNull(recovered.errorMessage)
        assertTrue(recovered.membershipWasRefreshed)
    }

    private companion object {
        val me = ProjectCollaborator(id = "user-me", displayName = "Me", isCurrentUser = true)
        val wife = ProjectCollaborator(id = "user-wife", displayName = "Wife", isCurrentUser = false)
        val sharedProject = ProjectItem(
            id = "project-shared",
            name = "Shared",
            canAssignTasks = true,
            shared = true,
            isInboxProject = false,
            collaborators = listOf(me, wife),
            collaboratorsReady = true
        )
    }
}
