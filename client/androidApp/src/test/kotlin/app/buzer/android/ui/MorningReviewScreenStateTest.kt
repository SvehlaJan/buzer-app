package app.buzer.android.ui

import app.buzer.android.ui.screens.MorningReviewContentState
import app.buzer.android.ui.screens.buildMorningReviewReadyState
import app.buzer.android.ui.screens.buildMorningReviewRepairEditorState
import app.buzer.android.ui.screens.buildMorningReviewContentState
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.ui.commitment.CommitmentEditorMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
    fun repairEditorUsesSharedRepairModeWithReadonlyTaskContext() {
        val state = buildMorningReviewRepairEditorState(
            task = invalidTask(
                id = "task-invalid",
                title = "Renew parking permit",
                description = "Bring the printed form.",
                durationMinutes = null
            ),
            start = null,
            durationMinutes = 30
        )

        assertEquals(CommitmentEditorMode.Repair, state.mode)
        assertFalse(state.titleEditable)
        assertEquals("Renew parking permit", state.title)
        assertEquals("Bring the printed form.", state.description)
        assertEquals(30, state.durationMinutes)
        assertEquals("Save task", state.submitLabel)
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

    private fun invalidTask(
        id: String,
        title: String = "Invalid task $id",
        description: String? = null,
        validationErrors: List<String> = listOf("missing_due_datetime"),
        durationMinutes: Int? = null
    ): TaskSnapshotItem =
        TaskSnapshotItem(
            id = id,
            todoistTaskId = "todoist-$id",
            title = title,
            description = description,
            projectId = "project-1",
            assigneeId = "user-1",
            creatorId = "user-2",
            dueDatetime = null,
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
