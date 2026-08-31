package app.buzer.android.ui.screens

import app.buzer.android.ui.taskeditor.TaskEditorDraft
import app.buzer.android.ui.taskeditor.TaskEditorField
import app.buzer.android.ui.taskeditor.TaskEditorSchedule
import app.buzer.shared.model.ChecklistDraft
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AddTaskScreenStateTest {
    @Test
    fun initialAddIsQuietUntilTheUserInteracts() {
        val state = addTaskEditorState(
            draft = TaskEditorDraft(title = ""),
            projects = listOf(sharedProject()),
            projectsLoading = false,
            projectLoadError = null,
            submitting = false,
            touchedFields = emptySet(),
            submitAttempted = false
        )

        assertNull(state.visibleValidation.bannerMessage)
        assertTrue(state.visibleValidation.errors.isEmpty())
        assertEquals("Add", state.primaryActionLabel)
        assertTrue(state.primaryActionEnabled)
    }

    @Test
    fun automaticDefaultProjectHydrationDoesNotTouchValidationFields() {
        val hydrated = validDraft().copy(title = "")

        assertEquals(
            emptySet(),
            addTaskTouchedFields(
                previous = TaskEditorDraft(title = ""),
                next = hydrated,
                userInitiated = false
            )
        )
        assertEquals(
            setOf(TaskEditorField.Project, TaskEditorField.Assignee, TaskEditorField.Schedule),
            addTaskTouchedFields(
                previous = TaskEditorDraft(title = ""),
                next = hydrated,
                userInitiated = true
            )
        )
    }

    @Test
    fun touchedFieldShowsOnlyItsOwnErrorUntilAddIsPressed() {
        val touched = addTaskEditorState(
            draft = TaskEditorDraft(title = " "),
            projects = listOf(sharedProject()),
            projectsLoading = false,
            projectLoadError = null,
            submitting = false,
            touchedFields = setOf(TaskEditorField.Title),
            submitAttempted = false
        )
        val submitted = addTaskEditorState(
            draft = touched.draft,
            projects = listOf(sharedProject()),
            projectsLoading = false,
            projectLoadError = null,
            submitting = false,
            touchedFields = setOf(TaskEditorField.Title),
            submitAttempted = true
        )

        assertEquals(setOf(TaskEditorField.Title), touched.visibleValidation.errors.keys)
        assertEquals("Fix the highlighted fields", touched.visibleValidation.bannerMessage)
        assertEquals(
            setOf(TaskEditorField.Title, TaskEditorField.Project, TaskEditorField.Assignee, TaskEditorField.Schedule),
            submitted.visibleValidation.errors.keys
        )
        assertEquals("Fix the highlighted fields", submitted.visibleValidation.bannerMessage)
        assertFalse(submitted.primaryActionEnabled)
    }

    @Test
    fun validScheduleUsesTheAddRangeActionLabel() {
        val state = addTaskEditorState(
            draft = validDraft(),
            projects = listOf(sharedProject()),
            projectsLoading = false,
            projectLoadError = null,
            submitting = false,
            touchedFields = emptySet(),
            submitAttempted = false
        )

        assertEquals("Add 10:00–10:30", state.primaryActionLabel)
        assertTrue(state.primaryActionEnabled)
    }

    @Test
    fun loadingChoicesDisablesAddWithoutHidingTheEditor() {
        val state = addTaskEditorState(
            draft = validDraft(),
            projects = listOf(sharedProject()),
            projectsLoading = true,
            projectLoadError = null,
            submitting = false,
            touchedFields = emptySet(),
            submitAttempted = false
        )

        assertTrue(state.editorVisible)
        assertTrue(state.editingEnabled)
        assertFalse(state.primaryActionEnabled)
    }

    @Test
    fun customRecurrenceScheduleChangeRequiresExplicitReplacementImmediately() {
        val originalSchedule = TaskEditorSchedule(
            date = LocalDate.of(2026, 7, 23),
            hour = 10,
            minute = 0,
            durationMinutes = 30
        )
        val state = addTaskEditorState(
            draft = validDraft().copy(
                schedule = originalSchedule.copy(hour = 11),
                recurrenceKind = RecurrenceKind.custom,
                recurrencePreset = RecurrencePreset.weekly,
                originalSchedule = originalSchedule,
                repeatReplacementSelected = false
            ),
            projects = listOf(sharedProject()),
            projectsLoading = false,
            projectLoadError = null,
            submitting = false,
            touchedFields = setOf(TaskEditorField.Schedule),
            submitAttempted = false
        )

        assertEquals(
            "Choose a supported repeat before saving",
            state.visibleValidation.repeatError
        )
        assertFalse(state.primaryActionEnabled)
    }

    @Test
    fun createRequestAndRetryKeepTheExistingPayloadAndActionIdentity() {
        val request = addTaskCreateRequest(
            draft = validDraft().copy(
                title = "  Buy milk  ",
                description = "  Gate code: 2468  ",
                recurrencePreset = RecurrencePreset.weekly
            ),
            selectedProject = sharedProject(),
            checklistDrafts = listOf(ChecklistDraft("check-1", "Bring bags", null)),
            zoneId = ZoneId.of("Europe/Warsaw")
        )
        val retried = retainAddTaskCreateRequest(request) {
            error("A retry must reuse the captured Create request")
        }

        assertSame(request, retried)
        assertEquals("Buy milk", request.title)
        assertEquals("Gate code: 2468", request.description)
        assertEquals("project-family", request.projectId)
        assertEquals("user-me", request.assigneeId)
        assertEquals("2026-07-23T08:00:00Z", request.dueDatetime)
        assertEquals(30, request.durationMinutes)
        assertEquals(RecurrencePreset.weekly, request.recurrencePreset)
        assertEquals("Europe/Warsaw", request.timeZone)
        assertEquals(listOf(ChecklistDraft("check-1", "Bring bags", null)), request.checklistDrafts)
    }

    @Test
    fun dirtyAddExitStillRequiresDiscardConfirmation() {
        val project = sharedProject()
        val initial = AddTaskDraftFingerprint.empty(project.id)
        val current = addTaskEditorFingerprint(
            draft = validDraft(),
            selectedProject = project,
            checklistDrafts = emptyList()
        )

        assertEquals(DraftExitDecision.ConfirmDiscard, decideDraftExit(initial, current, submitting = false))
    }

    private fun validDraft() = TaskEditorDraft(
        title = "Buy milk",
        projectId = "project-family",
        assignee = me,
        schedule = TaskEditorSchedule(
            date = LocalDate.of(2026, 7, 23),
            hour = 10,
            minute = 0,
            durationMinutes = 30
        )
    )

    private fun sharedProject() = ProjectItem(
        id = "project-family",
        name = "Family",
        canAssignTasks = true,
        shared = true,
        isInboxProject = false,
        collaborators = listOf(me, wife),
        collaboratorsReady = true
    )

    private companion object {
        val me = ProjectCollaborator(id = "user-me", displayName = "Me", isCurrentUser = true)
        val wife = ProjectCollaborator(id = "user-wife", displayName = "Wife", isCurrentUser = false)
    }
}
