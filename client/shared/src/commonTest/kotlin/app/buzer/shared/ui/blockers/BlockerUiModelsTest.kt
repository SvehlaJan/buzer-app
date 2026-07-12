package app.buzer.shared.ui.blockers

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.TaskSnapshotItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BlockerUiModelsTest {
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
        description: String? = null
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
            updatedAt = "2026-07-10T07:00:00Z"
        )
}
