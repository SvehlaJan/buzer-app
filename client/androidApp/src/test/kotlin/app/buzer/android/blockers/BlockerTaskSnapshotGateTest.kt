package app.buzer.android.blockers

import app.buzer.shared.model.CommitmentState
import app.buzer.android.enforcement.AlarmKind
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockerTaskSnapshotGateTest {
    @Test
    fun returnsTrueWhenTaskIsCompleted() {
        val snapshot = snapshot(task("task-1", state = CommitmentState.completed, isCompleted = true))

        assertTrue(BlockerTaskSnapshotGate.isTaskResolved(snapshot, "task-1"))
    }

    @Test
    fun returnsTrueWhenTaskIsDeleted() {
        val snapshot = snapshot(task("task-1", state = CommitmentState.deleted, isDeleted = true))

        assertTrue(BlockerTaskSnapshotGate.isTaskResolved(snapshot, "task-1"))
    }

    @Test
    fun returnsFalseWhenTaskIsStillActiveOrMissing() {
        val snapshot = snapshot(task("task-1"))

        assertFalse(BlockerTaskSnapshotGate.isTaskResolved(snapshot, "task-1"))
        assertFalse(BlockerTaskSnapshotGate.isTaskResolved(snapshot, "missing"))
    }

    @Test
    fun startBlockerIsResolvedWhenTaskHasStarted() {
        val snapshot = snapshot(task("task-1", state = CommitmentState.started))

        assertTrue(BlockerTaskSnapshotGate.isBlockerResolved(snapshot, "task-1", AlarmKind.START_BLOCKER))
    }

    @Test
    fun startBlockerActivityIsHiddenWhenTaskHasStarted() {
        val snapshot = snapshot(task("task-1", state = CommitmentState.started))

        assertTrue(BlockerTaskSnapshotGate.shouldHideAfterRefresh(snapshot, "task-1", AlarmKind.START_BLOCKER))
    }

    @Test
    fun deadlineBlockerIsNotResolvedWhenTaskHasStarted() {
        val snapshot = snapshot(task("task-1", state = CommitmentState.started))

        assertFalse(BlockerTaskSnapshotGate.isBlockerResolved(snapshot, "task-1", AlarmKind.DEADLINE_BLOCKER))
    }

    @Test
    fun taskBlockersAreResolvedWhenTaskIsCompletedOrDeleted() {
        assertTrue(
            BlockerTaskSnapshotGate.isBlockerResolved(
                snapshot(task("task-1", state = CommitmentState.completed, isCompleted = true)),
                "task-1",
                AlarmKind.START_BLOCKER
            )
        )
        assertTrue(
            BlockerTaskSnapshotGate.isBlockerResolved(
                snapshot(task("task-1", state = CommitmentState.deleted, isDeleted = true)),
                "task-1",
                AlarmKind.DEADLINE_BLOCKER
            )
        )
    }

    private fun snapshot(vararg tasks: TaskSnapshotItem): TasksSnapshotResponse =
        TasksSnapshotResponse(
            scheduleVersion = 1,
            serverTime = "2026-05-28T09:00:00Z",
            tasks = tasks.toList(),
            health = emptyList<HealthStatus>()
        )

    private fun task(
        id: String,
        state: CommitmentState = CommitmentState.planned,
        isCompleted: Boolean = false,
        isDeleted: Boolean = false
    ): TaskSnapshotItem =
        TaskSnapshotItem(
            id = id,
            todoistTaskId = "todoist-$id",
            title = "Task $id",
            projectId = null,
            assigneeId = "user-self",
            creatorId = "user-self",
            dueDatetime = "2026-05-28T12:00:00Z",
            durationMinutes = 60,
            startAt = "2026-05-28T12:00:00Z",
            state = state,
            isAssignedToUser = true,
            isCreatedByUser = true,
            isCompleted = isCompleted,
            isDeleted = isDeleted,
            validationErrors = emptyList(),
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-05-28T09:00:00Z"
        )
}
