package app.buzer.android.ui.screens

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.taskeditor.ExistingChecklistDraft
import app.buzer.android.ui.taskeditor.GENERIC_TASK_EDITOR_UPDATE_ERROR
import app.buzer.android.ui.taskeditor.TaskEditorUpdateCancelRequest
import app.buzer.android.ui.taskeditor.TaskEditorUpdateChoices
import app.buzer.android.ui.taskeditor.buildUpdateTaskRequest
import app.buzer.android.ui.taskeditor.editTaskDraftFingerprint
import app.buzer.android.ui.taskeditor.initialTaskEditorUpdateState
import app.buzer.android.ui.taskeditor.primaryActionLabel
import app.buzer.android.ui.taskeditor.reduceTaskEditorUpdateResult
import app.buzer.android.ui.taskeditor.taskEditorUpdateCancelRequest
import app.buzer.android.ui.taskeditor.taskEditorUpdateChecklistAccess
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.ChecklistMutation
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskRecurrence
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.ui.taskeditor.ChecklistEditorDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeadlineBlockerUpdateStateTest {
    @Test
    fun currentCompleteSnapshotOpensFullRescheduleEditorWithEntryValidationAndActivityFooter() {
        val task = task().copy(
            title = " ",
            dueDatetime = null,
            durationMinutes = null,
            activityLogEntries = listOf(activityEntry())
        )

        val recovery = deadlineBlockerRecoveryState(
            task = task,
            expectedOccurrenceKey = task.occurrenceKey,
            rescheduleRequested = true
        )
        val editor = assertIs<DeadlineBlockerRecoveryState.Editor>(recovery)
        val state = initialTaskEditorUpdateState(editor.task, readyChoices())

        assertEquals("Reschedule", deadlineBlockerUpdatePresentation.primaryVerb)
        assertEquals(task.id, editor.task.id)
        assertEquals("Title is required", state.visibleValidation.titleError)
        assertEquals("Start time is required", state.visibleValidation.scheduleError)
        assertEquals("Fix the highlighted fields", state.visibleValidation.bannerMessage)
    }

    @Test
    fun fullEditorRetainsEveryRootFieldAndPermitsDirectChecklistMutations() {
        val task = task().copy(
            checklistItems = listOf(checklistItem()),
            description = "Original notes",
            recurrence = TaskRecurrence(RecurrenceKind.supported, RecurrencePreset.daily, null)
        )

        val state = initialTaskEditorUpdateState(task, readyChoices())
        val changed = state.withDraft(
            state.draft.copy(
                title = "Updated title",
                description = "Updated notes",
                projectId = otherProject.id,
                assignee = wife,
                recurrencePreset = RecurrencePreset.weekly
            )
        )

        assertEquals("Updated title", changed.draft.title)
        assertEquals("Updated notes", changed.draft.description)
        assertEquals(otherProject.id, changed.draft.projectId)
        assertEquals(wife.id, changed.draft.assignee?.id)
        assertEquals(RecurrencePreset.weekly, changed.draft.recurrencePreset)
        assertEquals(
            "Reschedule ${changed.draft.schedule.hour.toString().padStart(2, '0')}:" +
                "${changed.draft.schedule.minute.toString().padStart(2, '0')}–" +
                "${changed.draft.schedule.hour.toString().padStart(2, '0')}:30",
            primaryActionLabel("Reschedule", changed.draft)
        )
        assertTrue(taskEditorUpdateChecklistAccess(task, changed.editingEnabled).enabled)
        assertEquals("Checklist item", task.checklistItems.single().title)

        val request = buildUpdateTaskRequest(
            task = task,
            title = changed.draft.title,
            description = changed.draft.description,
            project = otherProject,
            selectedAssignee = wife,
            schedule = changed.draft.schedule,
            recurrencePreset = changed.draft.recurrencePreset,
            existingDrafts = listOf(
                ExistingChecklistDraft(
                    id = "child-1",
                    childRevision = "child-r1",
                    title = "Edited checklist item",
                    description = "Child notes",
                    checked = true
                )
            ),
            deletedChildren = emptySet(),
            addedDrafts = listOf(
                ChecklistEditorDraft(
                    clientMutationId = "new-child",
                    title = "New checklist item",
                    description = null
                )
            )
        )

        assertEquals(3, request.childMutations.size)
        assertIs<ChecklistMutation.Edit>(request.childMutations[0])
        assertIs<ChecklistMutation.Check>(request.childMutations[1])
        assertIs<ChecklistMutation.Add>(request.childMutations[2])
    }

    @Test
    fun missingOrStaleSnapshotRequiresRefreshInsteadOfOpeningPartialForm() {
        val task = task()

        assertEquals(
            DeadlineBlockerRecoveryState.RefreshRequired,
            deadlineBlockerRecoveryState(
                task = null,
                expectedOccurrenceKey = task.occurrenceKey,
                rescheduleRequested = true
            )
        )
        assertEquals(
            DeadlineBlockerRecoveryState.RefreshRequired,
            deadlineBlockerRecoveryState(
                task = task,
                expectedOccurrenceKey = "stale-occurrence",
                rescheduleRequested = true
            )
        )
        assertEquals(
            DeadlineBlockerRecoveryState.Actions,
            deadlineBlockerRecoveryState(
                task = null,
                expectedOccurrenceKey = task.occurrenceKey,
                rescheduleRequested = false
            )
        )
        assertEquals(
            DeadlineBlockerRecoveryState.RefreshRequired,
            deadlineBlockerRecoveryState(
                task = task,
                expectedOccurrenceKey = task.occurrenceKey,
                rescheduleRequested = true,
                snapshotConfirmed = false
            )
        )
    }

    @Test
    fun unavailableChoicesKeepRescheduleFormVisibleButDisableSubmit() {
        val recovery = deadlineBlockerRecoveryState(
            task = task(),
            expectedOccurrenceKey = task().occurrenceKey,
            rescheduleRequested = true
        )
        val editor = assertIs<DeadlineBlockerRecoveryState.Editor>(recovery)
        val loading = initialTaskEditorUpdateState(
            editor.task,
            TaskEditorUpdateChoices(loading = true)
        )

        assertEquals(editor.task.title, loading.draft.title)
        assertFalse(loading.canSubmit)
        assertTrue(loading.visibleValidation.unavailableFields.isNotEmpty())
    }

    @Test
    fun warningAndFailurePreserveTheSharedHostLifecycle() {
        val initial = initialTaskEditorUpdateState(task(), readyChoices())
        val submitted = initial.withDraft(initial.draft.copy(title = "Keep this edit")).beginSubmission()
        val warning = reduceTaskEditorUpdateResult(submitted, CoordinatorActionResult.Warning("busy", "Server busy", null)).state
        val failed = reduceTaskEditorUpdateResult(
            initial.withDraft(initial.draft.copy(title = "Keep this edit")).beginSubmission(),
            CoordinatorActionResult.Warning("failed", "backend failed", null)
        ).state

        assertEquals("Server busy", warning.resultMessage)
        assertTrue(warning.canSubmit)
        assertEquals("Keep this edit", failed.draft.title)
        assertEquals("backend failed", failed.resultMessage)
    }

    @Test
    fun topAndBottomCancelUseTheSameDiscardDecisionWhileSystemBackIsConsumed() {
        val initial = initialTaskEditorUpdateState(task(), readyChoices())
        val dirty = initial.withDraft(initial.draft.copy(title = "Changed"))

        assertEquals(
            TaskEditorUpdateCancelRequest.ConfirmDiscard,
            deadlineBlockerCancelRequest(dirty, isDirty = true)
        )
        assertEquals(
            TaskEditorUpdateCancelRequest.ConfirmDiscard,
            deadlineBlockerCancelRequest(dirty, isDirty = true)
        )
        assertEquals(TaskEditorUpdateCancelRequest.Cancel, deadlineBlockerCancelRequest(initial, isDirty = false))
        assertTrue(deadlineBlockerConsumesSystemBack(showBack = false))
        assertFalse(deadlineBlockerConsumesSystemBack(showBack = true))
    }

    @Test
    fun personalProjectSelfAssigneeStaysCleanAfterChoicesLoadAndExitsWithoutDiscardPrompt() {
        val personalProject = sharedProject.copy(
            id = "project-personal",
            name = "Personal",
            canAssignTasks = false,
            shared = false,
            collaborators = listOf(me)
        )
        val task = task().copy(projectId = personalProject.id, assigneeId = me.id)
        val loadedChoices = TaskEditorUpdateChoices(projects = listOf(personalProject))
        val draft = initialTaskEditorUpdateState(
            task,
            loadedChoices
        ).draft
        val beforeChoicesLoad = editTaskDraftFingerprint(
            draft = draft,
            choices = TaskEditorUpdateChoices(loading = true),
            existingDrafts = emptyList(),
            deletedChildren = emptySet(),
            addedDrafts = emptyList()
        )
        val baseline = editTaskDraftFingerprint(
            draft = draft,
            choices = loadedChoices,
            existingDrafts = emptyList(),
            deletedChildren = emptySet(),
            addedDrafts = emptyList()
        )
        val current = editTaskDraftFingerprint(
            draft = draft,
            choices = loadedChoices,
            existingDrafts = emptyList(),
            deletedChildren = emptySet(),
            addedDrafts = emptyList()
        )
        val state = initialTaskEditorUpdateState(
            task,
            loadedChoices
        )

        assertEquals(me.id, beforeChoicesLoad.assigneeId)
        assertNull(baseline.assigneeId)
        assertEquals(baseline, current)
        assertEquals(
            TaskEditorUpdateCancelRequest.Cancel,
            deadlineBlockerCancelRequest(state, isDirty = current != baseline),
            "top Back must return directly to blocker actions"
        )
        assertEquals(
            TaskEditorUpdateCancelRequest.Cancel,
            deadlineBlockerCancelRequest(state, isDirty = current != baseline),
            "bottom Cancel must return directly to blocker actions"
        )

        val changed = editTaskDraftFingerprint(
            draft = draft.copy(title = "Changed title"),
            choices = loadedChoices,
            existingDrafts = emptyList(),
            deletedChildren = emptySet(),
            addedDrafts = emptyList()
        )
        assertTrue(changed != baseline)
        assertEquals(
            TaskEditorUpdateCancelRequest.ConfirmDiscard,
            deadlineBlockerCancelRequest(state, isDirty = changed != baseline)
        )
    }

    @Test
    fun nestedChecklistIsReadOnlyWithoutLockingRootRescheduleFields() {
        val task = task().copy(
            hierarchyStatus = HierarchyStatus.unsupported,
            hierarchyViolation = "Nested checklist items cannot be edited"
        )
        val state = initialTaskEditorUpdateState(task, readyChoices())
        val checklist = taskEditorUpdateChecklistAccess(task, state.editingEnabled)

        assertTrue(state.editingEnabled)
        assertFalse(checklist.enabled)
        assertEquals("Nested checklist items cannot be edited", checklist.warningMessage)
        assertNull(state.visibleValidation.titleError)
    }

    private fun readyChoices() = TaskEditorUpdateChoices(projects = listOf(sharedProject, otherProject))

    private fun activityEntry() = app.buzer.shared.model.ActivityLogEntry(
        timestamp = "2026-07-23T09:00:00Z",
        timeZone = "UTC",
        message = "Task was scheduled"
    )

    private fun checklistItem() = ChecklistItem(
        id = "child-1",
        title = "Checklist item",
        description = null,
        checked = false,
        order = 1,
        childRevision = "child-r1"
    )

    private fun task() = TaskSnapshotItem(
        id = "task-1",
        todoistTaskId = "todoist-task-1",
        title = "Review finances",
        description = "Original notes",
        projectId = sharedProject.id,
        assigneeId = me.id,
        creatorId = null,
        dueDatetime = "2026-07-24T09:00:00Z",
        dueDate = null,
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
        updatedAt = "2026-07-23T08:00:00Z",
        occurrenceKey = "occurrence-1",
        taskRevision = "task-r1",
        recurrence = TaskRecurrence(RecurrenceKind.supported, RecurrencePreset.never, null)
    )

    private companion object {
        val me = ProjectCollaborator(id = "me", displayName = "Me", isCurrentUser = true)
        val wife = ProjectCollaborator(id = "wife", displayName = "Wife", isCurrentUser = false)
        val sharedProject = ProjectItem(
            id = "project-1",
            name = "Shared",
            canAssignTasks = true,
            shared = true,
            isInboxProject = false,
            collaborators = listOf(me, wife),
            collaboratorsReady = true
        )
        val otherProject = sharedProject.copy(id = "project-2", name = "Personal")
    }
}
