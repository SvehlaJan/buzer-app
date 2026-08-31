package app.buzer.android.ui.taskeditor

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskRecurrence
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskEditorUpdateScreenStateTest {

    @Test
    fun initialDraftPopulatesSupportedTaskProperties() {
        val state = initialTaskEditorUpdateState(task = task(), choices = readyChoices())

        assertEquals("Test task", state.draft.title)
        assertEquals("Initial details", state.draft.description)
        assertEquals(sharedProject.id, state.draft.projectId)
        assertEquals(me.id, state.draft.assignee?.id)
        assertEquals(RecurrencePreset.daily, state.draft.recurrencePreset)
        assertTrue(state.editingEnabled)
        assertTrue(state.canSubmit)
    }

    @Test
    fun warningResultRetainsDraft() {
        val original = initialTaskEditorUpdateState(task = task(), choices = readyChoices())
        val changed = original.withDraft(original.draft.copy(title = "Updated title"))

        val transition = reduceTaskEditorUpdateResult(
            changed.beginSubmission(),
            CoordinatorActionResult.Warning("busy", "Server busy", null)
        )

        assertEquals("Updated title", transition.state.draft.title)
        assertEquals("Server busy", transition.state.resultMessage)
        assertFalse(transition.state.submitting)
        assertTrue(transition.state.canSubmit)
        assertFalse(transition.shouldInvokeSuccess)
    }

    @Test
    fun conflictResultRetainsDraft() {
        val original = initialTaskEditorUpdateState(task = task(), choices = readyChoices())
        val changed = original.withDraft(original.draft.copy(title = "Updated title"))

        val transition = reduceTaskEditorUpdateResult(
            changed.beginSubmission(),
            CoordinatorActionResult.Conflict("conflict", "Task changed remotely", snapshot())
        )

        assertEquals("Updated title", transition.state.draft.title)
        assertEquals("Task changed remotely", transition.state.resultMessage)
        assertFalse(transition.state.submitting)
        assertFalse(transition.shouldInvokeSuccess)
    }

    @Test
    fun refreshedMembershipReplacesOnlyAnAssigneeProvenStale() {
        val taskAssignedToWife = task().copy(assigneeId = wife.id, isAssignedToUser = false)
        val onlyMe = sharedProject.copy(collaborators = listOf(me))
        val cached = initialTaskEditorUpdateState(
            task = taskAssignedToWife,
            choices = TaskEditorUpdateChoices(projects = listOf(onlyMe))
        )

        val refreshed = cached.withChoices(
            TaskEditorUpdateChoices(
                projects = listOf(onlyMe),
                membershipWasRefreshed = true
            )
        )

        assertEquals(wife.id, cached.draft.assignee?.id)
        assertEquals(me.id, refreshed.draft.assignee?.id)
    }

    @Test
    fun confirmedResultInvokesSuccessOnlyOnce() {
        val initial = initialTaskEditorUpdateState(task = task(), choices = readyChoices())
        val submitting = initial.beginSubmission()

        val first = reduceTaskEditorUpdateResult(submitting, CoordinatorActionResult.Success)
        val repeated = reduceTaskEditorUpdateResult(first.state, CoordinatorActionResult.Success)

        assertTrue(first.shouldInvokeSuccess)
        assertFalse(repeated.shouldInvokeSuccess)
        assertTrue(repeated.state.successDelivered)
    }

    @Test
    fun warningAndFailedResultsRetainDraft() {
        val initial = initialTaskEditorUpdateState(task = task(), choices = readyChoices())
            .withDraft(initialTaskEditorUpdateDraft(task()).copy(title = "Keep this edit"))

        val warning = reduceTaskEditorUpdateResult(
            initial.beginSubmission(),
            CoordinatorActionResult.Warning("error", "Error message", null)
        )
        val failed = failTaskEditorUpdateSubmission(warning.state.beginSubmission())

        assertEquals("Keep this edit", warning.state.draft.title)
        assertEquals("Error message", warning.state.resultMessage)
        assertEquals("Keep this edit", failed.draft.title)
        assertEquals(GENERIC_TASK_EDITOR_UPDATE_ERROR, failed.resultMessage)
        assertFalse(failed.submitting)
        assertFalse(failed.pendingConfirmation)
    }

    @Test
    fun unsupportedHierarchyDisablesChecklistEditing() {
        val unsupported = task().copy(hierarchyStatus = HierarchyStatus.unsupported)
        val state = initialTaskEditorUpdateState(unsupported, readyChoices())

        val checklist = taskEditorUpdateChecklistAccess(
            task = unsupported,
            hostEnabled = state.editingEnabled
        )

        assertTrue(state.editingEnabled)
        assertFalse(checklist.enabled)
        assertEquals("This task has unsupported nested checklist items", checklist.warningMessage)
    }

    private fun readyChoices() = TaskEditorUpdateChoices(projects = listOf(sharedProject))

    private fun snapshot() = TasksSnapshotResponse(
        scheduleVersion = 1,
        serverTime = "2026-07-23T10:00:00Z",
        tasks = listOf(task()),
        health = emptyList(),
        snapshotGeneration = 1
    )

    private fun task() = TaskSnapshotItem(
        id = "task-1",
        todoistTaskId = "todoist-1",
        title = "Test task",
        description = "Initial details",
        projectId = sharedProject.id,
        assigneeId = me.id,
        creatorId = "creator",
        dueDatetime = "2026-07-23T10:00:00Z",
        durationMinutes = 30,
        startAt = null,
        state = CommitmentState.planned,
        isAssignedToUser = true,
        isCreatedByUser = true,
        isCompleted = false,
        isDeleted = false,
        validationErrors = emptyList(),
        startedAt = null,
        startDismissCount = 0,
        deadlineExtensionCount = 0,
        escalationSentAt = null,
        updatedAt = "2026-07-23T09:00:00Z",
        occurrenceKey = "occ-1",
        taskRevision = "rev-1",
        recurrence = TaskRecurrence(kind = RecurrenceKind.supported, preset = RecurrencePreset.daily),
        checklistItems = listOf(
            ChecklistItem(id = "child-1", title = "Check item", description = null, checked = false, order = 1, childRevision = "cr-1")
        ),
        checkedCount = 0,
        totalCount = 1,
        hierarchyStatus = HierarchyStatus.valid
    )

    private companion object {
        val me = ProjectCollaborator(id = "user-me", displayName = "Me", isCurrentUser = true)
        val wife = ProjectCollaborator(id = "user-wife", displayName = "Wife", isCurrentUser = false)
        val sharedProject = ProjectItem(
            id = "proj-shared",
            name = "Shared",
            canAssignTasks = true,
            shared = true,
            isInboxProject = false,
            collaborators = listOf(me, wife),
            collaboratorsReady = true
        )
    }
}
