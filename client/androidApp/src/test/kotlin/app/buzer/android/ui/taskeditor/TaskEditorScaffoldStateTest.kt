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

    @Test
    fun scheduleValidationFocusExpandsScheduleEvenWhenAnotherSectionWasOpen() {
        val validation = validateScheduleForSave(
            TaskEditorDraft(title = "Review", projectId = "shared", assignee = me)
        )

        assertEquals(
            EditorSection.Schedule,
            resolvedExpandedSection(
                state = TaskEditorScaffoldState(expandedSection = EditorSection.Notes),
                validation = validation
            )
        )
    }

    @Test
    fun scheduleValidationFocusPersistsAfterTheValidationClears() {
        val focusedState = persistValidationFocus(
            state = TaskEditorScaffoldState(expandedSection = EditorSection.Notes),
            validation = validateScheduleForSave(
                TaskEditorDraft(title = "Review", projectId = "shared", assignee = me)
            )
        )

        assertEquals(EditorSection.Schedule, focusedState.expandedSection)
        assertEquals(
            EditorSection.Schedule,
            resolvedExpandedSection(focusedState, TaskEditorScheduleValidation.valid())
        )
    }

    @Test
    fun scheduleValidationFocusIgnoresOtherAccordionTapsUntilTheErrorClears() {
        val validation = validateScheduleForSave(
            TaskEditorDraft(title = "Review", projectId = "shared", assignee = me)
        )
        val scheduleState = TaskEditorScaffoldState(expandedSection = EditorSection.Schedule)

        assertEquals(
            scheduleState,
            reduceSectionTap(scheduleState, EditorSection.Notes, validation)
        )
        assertEquals(
            scheduleState,
            reduceSectionTap(scheduleState, EditorSection.Checklist, validation)
        )
    }

    @Test
    fun savingDisablesDismissActions() {
        assertEquals(false, editorDismissActionsEnabled(saving = true))
        assertEquals(true, editorDismissActionsEnabled(saving = false))
    }

    @Test
    fun scheduleValidationProvidesConciseScheduleLevelErrorCopy() {
        val validation = validateScheduleForSave(
            TaskEditorDraft(title = "Review", projectId = "shared", assignee = me)
        )

        assertEquals(
            "Set date, start time, and duration to save this task.",
            scheduleValidationMessage(validation)
        )
    }

    @Test
    fun visibleScheduleErrorExpandsScheduleAndUsesTheSharedFieldMessage() {
        val visibleValidation = VisibleTaskEditorValidation(
            errors = mapOf(TaskEditorField.Schedule to "Start time is required"),
            unavailableFields = emptySet(),
            canSubmit = false,
            bannerMessage = "Fix the highlighted fields"
        )

        val validation = scheduleValidationForVisibleErrors(
            draft = TaskEditorDraft(title = "Review", projectId = "shared", assignee = me),
            visibleValidation = visibleValidation
        )

        assertEquals(EditorSection.Schedule, validation.focusSection)
        assertEquals("Start time is required", scheduleValidationMessage(validation))
    }

    @Test
    fun fieldErrorOverridesTheNormalRowSupportingText() {
        assertEquals(
            "Project is required",
            selectionRowSupportingText(
                normalSupportingText = "Personal project",
                fieldError = "Project is required"
            )
        )
        assertEquals(
            "Personal project",
            selectionRowSupportingText(
                normalSupportingText = "Personal project",
                fieldError = null
            )
        )
    }

    @Test
    fun titleAndChecklistValidationErrorsKeepConciseInlineCopy() {
        assertEquals("Title is required", conciseInlineError("  Title is required  "))
        assertEquals("Each checklist item needs a title", conciseInlineError("Each checklist item needs a title"))
        assertEquals(null, conciseInlineError("   "))
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
