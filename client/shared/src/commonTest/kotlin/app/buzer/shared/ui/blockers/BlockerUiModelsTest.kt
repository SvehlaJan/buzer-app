package app.buzer.shared.ui.blockers

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TaskRecurrence
import app.buzer.shared.ui.home.homeTaskProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockerUiModelsTest {
    @Test
    fun blockerShowsProgressAndDisablesDoneAndExtendForIncompleteRecurringChecklist() {
        val model = buildBlockerUiModel(
            kind = BlockerKind.Deadline,
            task = taskSnapshot(
                checklistItems = listOf(
                    ChecklistItem("child-done", "Done", checked = true, order = 1, childRevision = "r:1"),
                    ChecklistItem("child-next", "Next", checked = false, order = 2, childRevision = "r:2")
                ),
                checkedCount = 1,
                totalCount = 2,
                recurrence = TaskRecurrence(kind = RecurrenceKind.supported, preset = RecurrencePreset.weekly)
            )
        )

        assertEquals(1, model.checkedCount)
        assertEquals(2, model.totalCount)
        assertEquals("Next", model.nextUncheckedChildTitle)
        assertFalse(model.doneEnabled)
        assertFalse(model.extendEnabled)
    }

    @Test
    fun homeProgressEnablesDoneAndExtendForCompletedOneTimeChecklist() {
        val progress = homeTaskProgress(
            taskSnapshot(
                checklistItems = listOf(ChecklistItem("child", "Done", checked = true, order = 1, childRevision = "r:1")),
                checkedCount = 1,
                totalCount = 1,
                recurrence = TaskRecurrence(kind = RecurrenceKind.none, preset = RecurrencePreset.never)
            )
        )

        assertEquals("1/1", progress.countLabel)
        assertEquals(null, progress.nextUncheckedChildTitle)
        assertTrue(progress.doneEnabled)
        assertTrue(progress.extendEnabled)
    }
    @Test
    fun startBlockerUsesStartNowEyebrow() {
        val model = buildBlockerUiModel(
            kind = BlockerKind.Start,
            task = taskSnapshot()
        )

        assertEquals("Start now", model.eyebrow)
    }

    @Test
    fun deadlineBlockerUsesBlockEndedEyebrow() {
        val model = buildBlockerUiModel(
            kind = BlockerKind.Deadline,
            task = taskSnapshot()
        )

        assertEquals("Block ended", model.eyebrow)
    }

    @Test
    fun blockWindowShowsStartEndAndDuration() {
        val model = buildBlockerUiModel(
            kind = BlockerKind.Start,
            task = taskSnapshot(
                startAt = "2026-07-10T08:05:00Z",
                dueDatetime = "2026-07-10T08:05:00Z",
                durationMinutes = 45
            )
        )

        assertEquals("08:05-08:50 - 45 min block", model.blockWindow)
    }

    @Test
    fun blockWindowUsesDueDatetimeAsStartWhenStartAtIsMissing() {
        val model = buildBlockerUiModel(
            kind = BlockerKind.Deadline,
            task = taskSnapshot(
                startAt = null,
                dueDatetime = "2026-07-10T08:05:00Z",
                durationMinutes = 45
            )
        )

        assertEquals("08:05-08:50 - 45 min block", model.blockWindow)
    }

    @Test
    fun blockWindowFormatsEachEndpointWithItsDstOffset() {
        val model = buildBlockerUiModel(
            kind = BlockerKind.Start,
            task = taskSnapshot(
                startAt = "2026-03-29T00:30:00Z",
                dueDatetime = "2026-03-29T00:30:00Z",
                durationMinutes = 60
            ),
            timeZoneId = "Europe/Berlin"
        )

        assertEquals("01:30-03:30 - 1h block", model.blockWindow)
    }

    @Test
    fun optionalTodoistDetailsAreIncludedWhenPresent() {
        val model = buildBlockerUiModel(
            kind = BlockerKind.Start,
            task = taskSnapshot(description = "Read the acceptance notes first.")
        )

        assertEquals("Read the acceptance notes first.", model.description)
    }

    @Test
    fun productionBlockerModeDoesNotExposeBackAction() {
        assertFalse(BlockerDisplayMode.Production.showBackAction)
    }

    private fun taskSnapshot(
        startAt: String? = "2026-07-10T09:35:00Z",
        dueDatetime: String? = "2026-07-10T09:35:00Z",
        durationMinutes: Int? = 30,
        description: String? = null,
        checklistItems: List<ChecklistItem> = emptyList(),
        checkedCount: Int = 0,
        totalCount: Int = 0,
        recurrence: TaskRecurrence = TaskRecurrence()
    ): TaskSnapshotItem =
        TaskSnapshotItem(
            id = "task-start",
            todoistTaskId = "todoist-task-start",
            title = "Write task details",
            description = description,
            projectId = "project",
            assigneeId = "user",
            creatorId = "user",
            dueDatetime = dueDatetime,
            durationMinutes = durationMinutes,
            startAt = startAt,
            state = CommitmentState.start_blocker_active,
            isAssignedToUser = true,
            isCreatedByUser = true,
            isCompleted = false,
            isDeleted = false,
            validationErrors = emptyList(),
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-07-10T07:00:00Z",
            checklistItems = checklistItems,
            checkedCount = checkedCount,
            totalCount = totalCount,
            recurrence = recurrence
        )
}
