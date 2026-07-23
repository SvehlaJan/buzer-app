package app.buzer.android.ui

import app.buzer.android.ui.screens.MorningReviewContentState
import app.buzer.android.ui.screens.ExistingChecklistDraft
import app.buzer.android.ui.screens.buildMorningReviewReadyState
import app.buzer.android.ui.screens.buildMorningReviewContentState
import app.buzer.android.ui.screens.completeMorningReviewRepair
import app.buzer.android.ui.screens.morningReviewRepairPresentation
import app.buzer.android.ui.screens.updateRequest
import app.buzer.android.ui.taskeditor.TaskEditorField
import app.buzer.android.ui.taskeditor.TaskEditorSchedule
import app.buzer.android.ui.taskeditor.TaskEditorUpdateCancelRequest
import app.buzer.android.ui.taskeditor.TaskEditorUpdateChoices
import app.buzer.android.ui.taskeditor.initialTaskEditorUpdateState
import app.buzer.android.ui.taskeditor.primaryActionLabel
import app.buzer.android.ui.taskeditor.shouldRenderTaskEditorUpdateFooter
import app.buzer.android.ui.taskeditor.taskEditorUpdateCancelRequest
import app.buzer.android.ui.taskeditor.taskEditorUpdateChecklistAccess
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.ChecklistMutation
import app.buzer.shared.ui.commitment.ChecklistEditorDraft
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskSnapshotItem
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun repairUsesTheSharedImmediateValidationEditorAndActivityFooter() {
        val task = invalidTask(
            id = "task-invalid",
            title = " ",
            description = "Bring the printed form."
        ).copy(
            projectId = "",
            assigneeId = null,
            dueDatetime = null,
            durationMinutes = null
        )
        val state = initialTaskEditorUpdateState(
            task = task,
            choices = TaskEditorUpdateChoices(projects = listOf(sharedProject))
        )
        val repairedDraft = state.draft.copy(
            title = "Renew parking permit",
            description = "Bring the printed form.",
            projectId = sharedProject.id,
            assignee = me,
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 13), 10, 0, 30),
            recurrencePreset = RecurrencePreset.daily
        )

        assertEquals("Repair task", morningReviewRepairPresentation.screenTitle)
        assertEquals("Repair", morningReviewRepairPresentation.primaryVerb)
        assertTrue(shouldRenderTaskEditorUpdateFooter(morningReviewRepairPresentation))
        assertEquals("Fix the highlighted fields", state.visibleValidation.bannerMessage)
        assertEquals("Title is required", state.visibleValidation.titleError)
        assertTrue(TaskEditorField.Title in state.visibleValidation.errors)
        assertTrue(TaskEditorField.Project in state.visibleValidation.errors)
        assertTrue(TaskEditorField.Assignee in state.visibleValidation.errors)
        assertTrue(TaskEditorField.Schedule in state.visibleValidation.errors)
        assertEquals("Renew parking permit", repairedDraft.title)
        assertEquals("Bring the printed form.", repairedDraft.description)
        assertEquals(sharedProject.id, repairedDraft.projectId)
        assertEquals(me.id, repairedDraft.assignee?.id)
        assertEquals(RecurrencePreset.daily, repairedDraft.recurrencePreset)
        assertEquals("Repair 10:00–10:30", primaryActionLabel("Repair", repairedDraft))
    }

    @Test
    fun repairWaitsForChoicesAndLeavesNestedChecklistReadOnlyWhileRootFieldsRemainEditable() {
        val nested = invalidTask(
            id = "nested",
            dueDatetime = "2026-07-13T10:00:00Z",
            durationMinutes = 30
        ).copy(
            hierarchyStatus = HierarchyStatus.unsupported,
            hierarchyViolation = "Nested checklist items cannot be edited"
        )
        val loading = initialTaskEditorUpdateState(nested, TaskEditorUpdateChoices(loading = true))
        val ready = loading.withChoices(TaskEditorUpdateChoices(projects = listOf(sharedProject)))
        val checklist = taskEditorUpdateChecklistAccess(nested, hostEnabled = ready.editingEnabled)

        assertFalse(loading.canSubmit)
        assertEquals(nested.title, loading.draft.title)
        assertTrue(ready.canSubmit)
        assertTrue(ready.editingEnabled)
        assertFalse(checklist.enabled)
        assertEquals("Nested checklist items cannot be edited", checklist.warningMessage)
    }

    @Test
    fun confirmedRepairRemovesOnlyThatTaskAndFinishesOnlyAfterTheLastOne() {
        val first = invalidTask("first")
        val second = invalidTask("second")

        val afterFirst = completeMorningReviewRepair(listOf(first, second), first.id)
        val afterLast = completeMorningReviewRepair(listOf(second), second.id)

        assertEquals(listOf(second), afterFirst.remainingInvalidTasks)
        assertFalse(afterFirst.shouldFinish)
        assertEquals(emptyList(), afterLast.remainingInvalidTasks)
        assertTrue(afterLast.shouldFinish)
    }

    @Test
    fun cancellingRepairReturnsToTheReviewWithoutProducingAnUpdate() {
        val state = initialTaskEditorUpdateState(
            invalidTask("cancelled", dueDatetime = "2026-07-13T10:00:00Z", durationMinutes = 30),
            TaskEditorUpdateChoices(projects = listOf(sharedProject))
        )

        assertEquals(
            TaskEditorUpdateCancelRequest.Cancel,
            taskEditorUpdateCancelRequest(state = state, isDirty = false)
        )
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
    fun repairBuildsTheFullUpdateRequestWithDirectChecklistMutations() {
        val task = invalidTask(id = "task-repair").copy(
            occurrenceKey = "occurrence-repair",
            taskRevision = "r:7",
            checklistItems = listOf(
                ChecklistItem("child-edit", "Before", null, false, 1, "r:2"),
                ChecklistItem("child-delete", "Delete", null, false, 2, "r:3")
            )
        )
        val request = updateRequest(
            task = task,
            title = task.title,
            description = "",
            project = sharedProject,
            selectedAssignee = me,
            schedule = TaskEditorSchedule(LocalDate.of(2026, 7, 13), 10, 0, 30),
            recurrencePreset = RecurrencePreset.never,
            existingDrafts = listOf(ExistingChecklistDraft("child-edit", "r:2", "After", null, true)),
            deletedChildren = setOf("child-delete"),
            addedDrafts = listOf(ChecklistEditorDraft("new-child", "New")),
            deviceId = "device-1"
        )

        assertEquals(task.taskRevision, request.rootPatch?.expectedTaskRevision)
        assertEquals(sharedProject.id, request.rootPatch?.projectId)
        assertIs<ChecklistMutation.Edit>(request.childMutations[0])
        assertIs<ChecklistMutation.Check>(request.childMutations[1])
        assertIs<ChecklistMutation.Delete>(request.childMutations[2])
        assertIs<ChecklistMutation.Add>(request.childMutations[3])
    }

    private companion object {
        val me = ProjectCollaborator(id = "user-1", displayName = "Me", isCurrentUser = true)
        val sharedProject = ProjectItem(
            id = "project-1",
            name = "Family",
            canAssignTasks = true,
            shared = true,
            isInboxProject = false,
            collaborators = listOf(me),
            collaboratorsReady = true
        )
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
