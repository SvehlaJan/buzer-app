package app.buzer.android.ui.taskeditor

import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskEditorScaffoldStateTest {
    @Test
    fun scheduleStartsCollapsed() {
        assertEquals(EditorSection.None, TaskEditorScaffoldState().expandedSection)
    }

    @Test
    fun openingNotesCollapsesSchedule() {
        assertEquals(
            EditorSection.Notes,
            reduceSection(EditorSection.Schedule, EditorSection.Notes)
        )
    }

    @Test
    fun projectSelectionUpdatesTheDraftAndDismissesTheSheet() {
        val sharedProject = project(
            id = "shared",
            collaborators = listOf(me, wife)
        )
        val result = selectProject(
            state = TaskEditorScaffoldState(openSheet = EditorSheet.Project),
            draft = TaskEditorDraft(title = "Review", projectId = "old", assignee = wife),
            project = sharedProject,
            currentUser = me
        )

        assertEquals("shared", result.draft.projectId)
        assertEquals(wife, result.draft.assignee)
        assertEquals(EditorSheet.None, result.state.openSheet)
    }

    @Test
    fun assigneeSelectionUpdatesTheDraftAndDismissesTheSheet() {
        val result = selectAssignee(
            state = TaskEditorScaffoldState(openSheet = EditorSheet.Assignee),
            draft = TaskEditorDraft(title = "Review", projectId = "shared", assignee = me),
            assignee = wife
        )

        assertEquals(wife, result.draft.assignee)
        assertEquals(EditorSheet.None, result.state.openSheet)
    }

    @Test
    fun staleAssigneeChangesToMeWhenTheNewProjectPermitsMe() {
        val result = selectProject(
            state = TaskEditorScaffoldState(),
            draft = TaskEditorDraft(title = "Review", projectId = "old", assignee = wife),
            project = project(id = "new", collaborators = listOf(me)),
            currentUser = me
        )

        assertEquals(me, result.draft.assignee)
    }

    @Test
    fun staleAssigneeClearsWhenTheNewProjectHasNoValidMe() {
        val result = selectProject(
            state = TaskEditorScaffoldState(),
            draft = TaskEditorDraft(title = "Review", projectId = "old", assignee = wife),
            project = project(id = "new", collaborators = emptyList()),
            currentUser = me
        )

        assertEquals(null, result.draft.assignee)
    }

    @Test
    fun selfWithoutAScheduleReturnsScheduleFocusAndHighlightsEveryMissingControl() {
        val validation = validateScheduleForSave(
            TaskEditorDraft(title = "Review", projectId = "shared", assignee = me)
        )

        assertFalse(validation.isValid)
        assertEquals(EditorSection.Schedule, validation.focusSection)
        assertTrue(validation.highlightDate)
        assertTrue(validation.highlightStartTime)
        assertTrue(validation.highlightDuration)
    }

    private fun project(
        id: String,
        collaborators: List<ProjectCollaborator>
    ) = ProjectItem(
        id = id,
        name = id,
        canAssignTasks = true,
        shared = true,
        isInboxProject = false,
        collaborators = collaborators,
        collaboratorsReady = true
    )

    private companion object {
        val me = ProjectCollaborator(id = "me", displayName = "Me", isCurrentUser = true)
        val wife = ProjectCollaborator(id = "wife", displayName = "Wife", isCurrentUser = false)
    }
}
