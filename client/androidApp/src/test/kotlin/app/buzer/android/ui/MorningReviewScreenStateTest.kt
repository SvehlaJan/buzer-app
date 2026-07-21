package app.buzer.android.ui

import app.buzer.android.ui.screens.MorningReviewContentState
import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.addtask.AddTaskInvalidField
import app.buzer.android.ui.screens.buildMorningReviewReadyState
import app.buzer.android.ui.screens.buildMorningReviewRepairEditorState
import app.buzer.android.ui.screens.buildMorningReviewRepairValidation
import app.buzer.android.ui.screens.buildMorningReviewContentState
import app.buzer.android.ui.screens.initialRepairStartDraft
import app.buzer.android.ui.screens.updateTaskRequestFromEditorStart
import app.buzer.android.ui.screens.ExistingChecklistDraft
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.ChecklistMutation
import app.buzer.shared.ui.commitment.ChecklistEditorDraft
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.ui.commitment.CommitmentEditorMode
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs

class MorningReviewScreenStateTest {
    @Test
    fun showsLoadingWhenRefreshingWithoutCachedTasks() {
        val state = buildMorningReviewContentState(
            invalidTaskCount = 0,
            refreshing = true,
            refreshError = null,
            hasLoadedOnce = false
        )

        assertEquals(MorningReviewContentState.Loading, state)
    }

    @Test
    fun showsReadyWithRefreshWarningWhenCachedTasksExistAndRefreshFails() {
        val state = buildMorningReviewContentState(
            invalidTaskCount = 2,
            refreshing = false,
            refreshError = "Network request failed",
            hasLoadedOnce = true
        )

        assertEquals(
            MorningReviewContentState.Ready(warning = "Refresh failed. Showing last known tasks."),
            state
        )
    }

    @Test
    fun showsBlockingErrorWhenNoCachedDataAndRefreshFails() {
        val state = buildMorningReviewContentState(
            invalidTaskCount = 0,
            refreshing = false,
            refreshError = "Backend unavailable",
            hasLoadedOnce = false
        )

        assertEquals(MorningReviewContentState.Error("Backend unavailable"), state)
    }

    @Test
    fun readyStateSummarizesInvalidTasksInHeaderOnly() {
        val state = buildMorningReviewReadyState(
            invalidTasks = listOf(
                invalidTask("one"),
                invalidTask("two"),
                invalidTask("three")
            ),
            warning = null,
            refreshing = false
        )

        assertEquals("3 tasks need attention", state.headerText)
        assertEquals("3 unresolved", state.headerProgressText)
        state.cards.forEach { card ->
            assertNull(card.progressText)
            assertFalse(card.allText().contains("left after this task"))
        }
    }

    @Test
    fun invalidTaskCardShowsTitleProblemAndDetails() {
        val state = buildMorningReviewReadyState(
            invalidTasks = listOf(
                invalidTask(
                    id = "task-invalid",
                    title = "Renew parking permit",
                    description = "Bring the printed form.",
                    validationErrors = listOf("missing_due_datetime", "missing_duration")
                )
            ),
            warning = null,
            refreshing = false
        )

        val card = state.cards.single()
        assertEquals("Renew parking permit", card.title)
        assertEquals("No start time or duration set", card.problemText)
        assertEquals("Bring the printed form.", card.description)
        assertEquals("Repair", card.repairActionLabel)
        assertEquals("Snooze", card.snoozeActionLabel)
    }

    @Test
    fun invalidTaskCardShowsPlainLanguageForCanonicalScheduleErrors() {
        val state = buildMorningReviewReadyState(
            invalidTasks = listOf(
                invalidTask(
                    id = "task-invalid",
                    validationErrors = listOf("due_datetime_required", "duration_required")
                )
            ),
            warning = null,
            refreshing = false
        )

        assertEquals("No start time or duration set", state.cards.single().problemText)
    }

    @Test
    fun repairEditorUsesSharedEditModeWithResolvedProjectAndAssigneeLabels() {
        val state = buildMorningReviewRepairEditorState(
            task = invalidTask(
                id = "task-invalid",
                title = "Renew parking permit",
                description = "Bring the printed form.",
                durationMinutes = null
            ),
            start = DateTimeDraftSelection.Empty,
            durationMinutes = 30,
            projects = listOf(
                ProjectItem(
                    id = "project-1",
                    name = "Family",
                    canAssignTasks = true,
                    shared = true,
                    isInboxProject = false
                )
            ),
            assigneeOptions = listOf(ProjectCollaborator(id = "user-1", displayName = "Me", isCurrentUser = true))
        )

        assertEquals(CommitmentEditorMode.Repair, state.mode)
        assertEquals(true, state.titleEditable)
        assertEquals("Renew parking permit", state.title)
        assertEquals("Bring the printed form.", state.description)
        assertEquals("Family", state.projectLabel)
        assertEquals("Me", state.assigneeLabel)
        assertEquals(30, state.durationMinutes)
        assertEquals("Save task", state.submitLabel)
    }

    @Test
    fun repairEditorPreservesDateOnlyTodoistDueDate() {
        val draft = invalidTask(
            id = "task-date-only",
            dueDate = "2026-07-13",
            validationErrors = listOf("missing_due_datetime")
        ).initialRepairStartDraft()

        assertEquals(LocalDate.of(2026, 7, 13), draft.date)
        assertNull(draft.hour)
        assertNull(draft.minute)
        assertEquals("2026-07-13", draft.dateLabel)
        assertEquals("No time", draft.timeLabel)
    }

    @Test
    fun repairValidationRunsImmediatelyForMissingStartTime() {
        val validation = buildMorningReviewRepairValidation(
            title = "Renew parking permit",
            selectedProject = ProjectItem(
                id = "project-1",
                name = "Family",
                canAssignTasks = true,
                shared = true,
                isInboxProject = false
            ),
            selectedAssignee = ProjectCollaborator(id = "user-1", displayName = "Me", isCurrentUser = true),
            start = DateTimeDraftSelection(date = LocalDate.of(2026, 7, 13), hour = null, minute = null)
        )

        assertEquals("Start time is required", validation.bannerMessage)
        assertEquals("Required", validation.startError)
        assertEquals(AddTaskInvalidField.Start, validation.firstInvalidField)
    }

    @Test
    fun allClearStateShowsConfirmationAndSingleDoneAction() {
        val state = buildMorningReviewReadyState(
            invalidTasks = emptyList(),
            warning = null,
            refreshing = false
        )

        assertEquals("All commitments are valid", state.headerText)
        assertEquals(emptyList(), state.cards)
        assertEquals(listOf("Done"), state.actionLabels)
    }

    @Test
    fun repairRequestIncludesExplicitExistingAndNewDirectChildMutations() {
        val task = invalidTask(id = "task-repair").copy(
            occurrenceKey = "occurrence-repair",
            taskRevision = "r:7",
            checklistItems = listOf(
                ChecklistItem("child-edit", "Before", null, false, 1, "r:2"),
                ChecklistItem("child-delete", "Delete", null, false, 2, "r:3")
            )
        )
        val request = updateTaskRequestFromEditorStart(
            actionId = "repair-action",
            deviceId = "device-1",
            expectedOccurrenceKey = task.occurrenceKey,
            expectedTaskRevision = task.taskRevision,
            title = task.title,
            description = "",
            selectedProject = ProjectItem("project-1", "Family", true, true, false),
            selectedAssignee = ProjectCollaborator("user-1", "Me", isCurrentUser = true),
            start = DateTimeSelection(LocalDate.of(2026, 7, 13), 10, 0),
            durationMinutes = 30,
            existingChecklistItems = task.checklistItems,
            existingDrafts = listOf(ExistingChecklistDraft("child-edit", "r:2", "After", null, true)),
            deletedChildIds = setOf("child-delete"),
            checklistDrafts = listOf(ChecklistEditorDraft("new-child", "New"))
        )

        assertIs<ChecklistMutation.Edit>(request.childMutations[0])
        assertIs<ChecklistMutation.Check>(request.childMutations[1])
        assertIs<ChecklistMutation.Delete>(request.childMutations[2])
        assertIs<ChecklistMutation.Add>(request.childMutations[3])
    }

    private fun invalidTask(
        id: String,
        title: String = "Invalid task $id",
        description: String? = null,
        validationErrors: List<String> = listOf("missing_due_datetime"),
        durationMinutes: Int? = null,
        dueDatetime: String? = null,
        dueDate: String? = null
    ): TaskSnapshotItem =
        TaskSnapshotItem(
            id = id,
            todoistTaskId = "todoist-$id",
            title = title,
            description = description,
            projectId = "project-1",
            assigneeId = "user-1",
            creatorId = "user-2",
            dueDatetime = dueDatetime,
            dueDate = dueDate,
            durationMinutes = durationMinutes,
            startAt = null,
            state = CommitmentState.invalid,
            isAssignedToUser = true,
            isCreatedByUser = false,
            isCompleted = false,
            isDeleted = false,
            validationErrors = validationErrors,
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-07-11T08:00:00Z"
        )
}
