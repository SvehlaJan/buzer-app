package app.buzer.android.ui.screens

import app.buzer.android.ui.taskeditor.TaskEditorDraft
import app.buzer.android.ui.taskeditor.TaskEditorSchedule
import app.buzer.android.ui.taskeditor.validateScheduleForSave
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskRecurrence
import app.buzer.shared.model.TaskSnapshotItem
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EditTaskScreenStateTest {
    @Test
    fun untouchedCustomRecurrenceShowsItsRuleAndPreselectsNoSupportedPreset() {
        val customTask = task(assigneeId = wife.id, assignedToUser = false).copy(
            recurrence = TaskRecurrence(
                kind = RecurrenceKind.custom,
                preset = null,
                rule = "every 2 days"
            )
        )

        val presentation = editRecurrencePresentation(
            task = customTask,
            customRecurrenceUntouched = true,
            draftPreset = RecurrencePreset.never
        )

        assertEquals("Custom: every 2 days", presentation.rowValue)
        assertNull(presentation.selectedPreset)
    }

    @Test
    fun deliberateSupportedRecurrenceReplacementStopsShowingTheCustomRule() {
        val customTask = task(assigneeId = wife.id, assignedToUser = false).copy(
            recurrence = TaskRecurrence(
                kind = RecurrenceKind.custom,
                preset = null,
                rule = "every 2 days"
            )
        )

        val presentation = editRecurrencePresentation(
            task = customTask,
            customRecurrenceUntouched = false,
            draftPreset = RecurrencePreset.weekly
        )

        assertEquals("Weekly", presentation.rowValue)
        assertEquals(RecurrencePreset.weekly, presentation.selectedPreset)
    }

    @Test
    fun nonSelfNoScheduleDeliberatelyReplacesCustomRecurrenceWithNever() {
        val customTask = task(assigneeId = wife.id, assignedToUser = false).copy(
            recurrence = TaskRecurrence(
                kind = RecurrenceKind.custom,
                preset = null,
                rule = "every 2 days"
            )
        )
        val result = selectEditNoSchedule(
            draft = TaskEditorDraft(
                title = "Ask about booking",
                assignee = wife,
                schedule = TaskEditorSchedule(
                    date = java.time.LocalDate.of(2026, 7, 16),
                    hour = 10,
                    minute = 0,
                    durationMinutes = 30
                ),
                recurrencePreset = RecurrencePreset.never
            ),
            customRecurrenceUntouched = true
        )

        val presentation = editRecurrencePresentation(
            task = customTask,
            customRecurrenceUntouched = result.customRecurrenceUntouched,
            draftPreset = result.draft.recurrencePreset
        )

        assertEquals(TaskEditorSchedule(), result.draft.schedule)
        assertEquals(RecurrencePreset.never, result.draft.recurrencePreset)
        assertFalse(result.customRecurrenceUntouched)
        assertEquals("Does not repeat", presentation.rowValue)
        assertEquals(RecurrencePreset.never, presentation.selectedPreset)
    }

    @Test
    fun updateExceptionLeavesTheDraftRetryableInsteadOfSavingForever() {
        val state = failedEditTaskSubmitState(IllegalStateException("Network unavailable"))

        assertFalse(state.submitting)
        assertEquals(
            "Network unavailable. Your draft is still here; retry.",
            state.resultMessage
        )
    }

    @Test
    fun initialTaskAssigneeStaysAuthoritativeUntilRefreshedMembershipProvesItStale() {
        val draft = initialEditTaskDraft(task(assigneeId = wife.id, assignedToUser = false))
        val projectWithOnlyMe = sharedProject(collaborators = listOf(me))

        val cached = reconcileEditProjectMembership(
            draft = draft,
            projects = listOf(projectWithOnlyMe),
            currentUser = me,
            membershipWasRefreshed = false
        )
        val refreshed = reconcileEditProjectMembership(
            draft = cached,
            projects = listOf(projectWithOnlyMe),
            currentUser = me,
            membershipWasRefreshed = true
        )

        assertEquals(wife.id, cached.assignee?.id)
        assertEquals(me, refreshed.assignee)
    }

    @Test
    fun selfWithoutScheduleFocusesScheduleAndDoesNotBuildAnUpdateRequest() {
        val draft = TaskEditorDraft(
            title = "Review finances",
            projectId = sharedProject().id,
            assignee = me,
            schedule = TaskEditorSchedule()
        )

        assertFalse(validateScheduleForSave(draft).isValid)
        assertEquals(
            "Schedule",
            validateScheduleForSave(draft).focusSection?.name
        )
        assertNull(
            buildEditUpdateRequestWhenScheduleValid(
                task = task(assigneeId = wife.id, assignedToUser = false),
                draft = draft,
                project = sharedProject(),
                recurrencePreset = RecurrencePreset.never,
                existingDrafts = emptyList(),
                deletedChildren = emptySet(),
                addedDrafts = emptyList(),
                deviceId = "device-1"
            )
        )
    }

    private fun task(assigneeId: String?, assignedToUser: Boolean) = TaskSnapshotItem(
        id = "root",
        todoistTaskId = "todoist-root",
        title = "Root",
        projectId = "project-test",
        assigneeId = assigneeId,
        creatorId = null,
        dueDatetime = "2026-07-16T09:00:00Z",
        durationMinutes = 30,
        startAt = null,
        state = CommitmentState.planned,
        isAssignedToUser = assignedToUser,
        isCreatedByUser = true,
        isCompleted = false,
        isDeleted = false,
        validationErrors = emptyList(),
        startedAt = null,
        startDismissCount = 0,
        deadlineExtensionCount = 0,
        escalationSentAt = null,
        updatedAt = "2026-07-16T08:00:00Z",
        occurrenceKey = "occurrence-1",
        taskRevision = "root-r1",
        recurrence = TaskRecurrence(RecurrenceKind.supported, RecurrencePreset.never, null),
        checklistItems = emptyList()
    )

    private fun sharedProject(
        collaborators: List<ProjectCollaborator> = listOf(me, wife)
    ) = ProjectItem(
        id = "project-test",
        name = "Shared",
        canAssignTasks = true,
        shared = true,
        isInboxProject = false,
        collaborators = collaborators,
        collaboratorsReady = true
    )

    private companion object {
        val me = ProjectCollaborator(id = "user-me", displayName = "Me", isCurrentUser = true)
        val wife = ProjectCollaborator(id = "user-wife", displayName = "Wife", isCurrentUser = false)
    }
}
