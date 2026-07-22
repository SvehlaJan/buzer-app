package app.buzer.android.ui.taskeditor

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.MutationOutcome
import app.buzer.shared.model.MutationResponse
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
    fun invalidSnapshotShowsAllEntryValidationBeforeInteraction() {
        val state = initialTaskEditorUpdateState(
            task = task().copy(
                title = " ",
                projectId = "",
                assigneeId = null,
                dueDatetime = null,
                durationMinutes = null
            ),
            choices = readyChoices()
        )

        assertEquals("Fix the highlighted fields", state.visibleValidation.bannerMessage)
        assertEquals("Title is required", state.visibleValidation.titleError)
        assertEquals("Project is required", state.visibleValidation.projectError)
        assertEquals("Me assignee ID is not configured", state.visibleValidation.assigneeError)
        assertEquals("Start time is required", state.visibleValidation.scheduleError)
        assertFalse(TaskEditorField.Project in state.visibleValidation.unavailableFields)
    }

    @Test
    fun unavailableChoicesKeepTheEditorVisibleAndDisableSubmitUntilReady() {
        val loading = initialTaskEditorUpdateState(
            task = task(),
            choices = TaskEditorUpdateChoices(loading = true)
        )

        val ready = loading.withChoices(readyChoices())

        assertEquals("Review finances", loading.draft.title)
        assertFalse(loading.canSubmit)
        assertTrue(TaskEditorField.Project in loading.visibleValidation.unavailableFields)
        assertTrue(TaskEditorField.Assignee in loading.visibleValidation.unavailableFields)
        assertEquals("Review finances", ready.draft.title)
        assertTrue(ready.canSubmit)
    }

    @Test
    fun refreshingCachedChoicesStillTemporarilyDisablesSubmit() {
        val refreshing = initialTaskEditorUpdateState(
            task = task(),
            choices = readyChoices().copy(loading = true)
        )

        assertEquals("Review finances", refreshing.draft.title)
        assertFalse(refreshing.canSubmit)
    }

    @Test
    fun hostPresentationRetainsTheOptionalFooterSlot() {
        val presentation = TaskEditorPresentation(
            screenTitle = "Edit task",
            primaryVerb = "Save",
            showUpdateFooter = true
        )

        assertTrue(shouldRenderTaskEditorUpdateFooter(presentation))
        assertFalse(
            shouldRenderTaskEditorUpdateFooter(
                presentation.copy(showUpdateFooter = false)
            )
        )
    }

    @Test
    fun invalidChecklistContentDisablesSubmitAndStaysInlineWithTheEditor() {
        val state = initialTaskEditorUpdateState(task = task(), choices = readyChoices())

        val validation = effectiveTaskEditorUpdateValidation(
            state = state,
            contentValidation = TaskEditorUpdateContentValidation(
                canSubmit = false,
                message = "Each checklist item needs a title"
            )
        )

        assertFalse(validation.canSubmit)
        assertEquals("Each checklist item needs a title", validation.bannerMessage)
    }

    @Test
    fun pendingResultRetainsDraftAndBlocksDuplicateSubmission() {
        val original = initialTaskEditorUpdateState(task = task(), choices = readyChoices())
        val changed = original.withDraft(original.draft.copy(title = "Updated title"))

        val transition = reduceTaskEditorUpdateResult(changed.beginSubmission(), pendingResult())

        assertEquals("Updated title", transition.state.draft.title)
        assertEquals("Saved; waiting for confirmation", transition.state.resultMessage)
        assertTrue(transition.state.pendingConfirmation)
        assertEquals(1, transition.state.confirmationAttempt)
        assertFalse(transition.state.canSubmit)
        assertFalse(transition.shouldInvokeSuccess)
    }

    @Test
    fun pendingResultConsumesCancelAndBackWithoutOpeningDiscardConfirmation() {
        val initial = initialTaskEditorUpdateState(task = task(), choices = readyChoices())
        val pending = reduceTaskEditorUpdateResult(
            initial.withDraft(initial.draft.copy(title = "Updated title")).beginSubmission(),
            pendingResult()
        ).state

        assertEquals(
            TaskEditorUpdateCancelRequest.Ignore,
            taskEditorUpdateCancelRequest(state = pending, isDirty = true)
        )
        assertFalse(pending.discardConfirmationVisible)
        assertFalse(pending.canSubmit)
    }

    @Test
    fun pendingConfirmationRetriesItsCapturedActionUntilAuthorityConfirmsIt() {
        val initial = initialTaskEditorUpdateState(task = task(), choices = readyChoices())
        val pending = reduceTaskEditorUpdateResult(initial.beginSubmission(), pendingResult()).state

        val retried = reduceTaskEditorUpdateResult(pending, pendingResult()).state
        val confirmed = reduceTaskEditorUpdateResult(retried, CoordinatorActionResult.Success)

        assertEquals(2, retried.confirmationAttempt)
        assertTrue(retried.pendingConfirmation)
        assertTrue(confirmed.shouldInvokeSuccess)
        assertFalse(confirmed.state.pendingConfirmation)
    }

    @Test
    fun pendingResultSurvivesChoiceRefreshWithoutMutatingItsDraft() {
        val original = initialTaskEditorUpdateState(
            task = task().copy(assigneeId = wife.id, isAssignedToUser = false),
            choices = readyChoices()
        )
        val pending = reduceTaskEditorUpdateResult(original.beginSubmission(), pendingResult()).state
        val refreshed = pending.withChoices(
            TaskEditorUpdateChoices(
                projects = listOf(sharedProject.copy(collaborators = listOf(me))),
                membershipWasRefreshed = true
            )
        )

        assertEquals(wife.id, refreshed.draft.assignee?.id)
        assertTrue(refreshed.pendingConfirmation)
        assertFalse(refreshed.canSubmit)
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
        val pending = reduceTaskEditorUpdateResult(initial.beginSubmission(), pendingResult()).state

        val first = reduceTaskEditorUpdateResult(pending, CoordinatorActionResult.Success)
        val repeated = reduceTaskEditorUpdateResult(first.state, CoordinatorActionResult.Success)

        assertTrue(first.shouldInvokeSuccess)
        assertFalse(repeated.shouldInvokeSuccess)
        assertTrue(repeated.state.successDelivered)
    }

    @Test
    fun staleAndFailedResultsRetainDraftAndUseGenericRetryPresentation() {
        val initial = initialTaskEditorUpdateState(task = task(), choices = readyChoices())
            .withDraft(initialTaskEditorUpdateDraft(task()).copy(title = "Keep this edit"))

        val stale = reduceTaskEditorUpdateResult(
            initial.beginSubmission(),
            CoordinatorActionResult.Stale(snapshot(), "stale details must not leak into the editor")
        )
        val failed = failTaskEditorUpdateSubmission(stale.state.beginSubmission())

        assertEquals("Keep this edit", stale.state.draft.title)
        assertEquals(GENERIC_TASK_EDITOR_UPDATE_ERROR, stale.state.resultMessage)
        assertEquals("Keep this edit", failed.draft.title)
        assertEquals(GENERIC_TASK_EDITOR_UPDATE_ERROR, failed.resultMessage)
        assertFalse(failed.submitting)
        assertFalse(failed.pendingConfirmation)
    }

    @Test
    fun clearingANonSelfCustomScheduleExplicitlySelectsNever() {
        val custom = task().copy(
            assigneeId = wife.id,
            isAssignedToUser = false,
            recurrence = TaskRecurrence(RecurrenceKind.custom, null, "every 2 days")
        )
        val state = initialTaskEditorUpdateState(custom, readyChoices())

        val cleared = state.withDraft(state.draft.copy(schedule = TaskEditorSchedule()))

        assertEquals(RecurrencePreset.never, cleared.draft.recurrencePreset)
        assertTrue(cleared.draft.repeatReplacementSelected)
        assertTrue(cleared.visibleValidation.repeatError == null)
    }

    @Test
    fun unsupportedHierarchyLocksOnlyChecklistAndShowsItsExistingWarning() {
        val unsupported = task().copy(
            hierarchyStatus = HierarchyStatus.unsupported,
            hierarchyViolation = "Nested checklist items cannot be edited"
        )
        val state = initialTaskEditorUpdateState(unsupported, readyChoices())

        val checklist = taskEditorUpdateChecklistAccess(
            task = unsupported,
            hostEnabled = state.editingEnabled
        )

        assertTrue(state.editingEnabled)
        assertFalse(checklist.enabled)
        assertEquals("Nested checklist items cannot be edited", checklist.warningMessage)
    }

    private fun readyChoices() = TaskEditorUpdateChoices(projects = listOf(sharedProject))

    private fun pendingResult() = CoordinatorActionResult.Pending(
        MutationResponse(
            actionId = "action-1",
            outcome = MutationOutcome.reconciliation_pending,
            snapshot = snapshot()
        )
    )

    private fun snapshot() = TasksSnapshotResponse(
        scheduleVersion = 1,
        serverTime = "2026-07-23T10:00:00Z",
        tasks = listOf(task()),
        health = emptyList(),
        snapshotGeneration = 1
    )

    private fun task() = TaskSnapshotItem(
        id = "task-1",
        todoistTaskId = "todoist-task-1",
        title = "Review finances",
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
        recurrence = TaskRecurrence(RecurrenceKind.supported, RecurrencePreset.never, null),
        checklistItems = emptyList()
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
    }
}
