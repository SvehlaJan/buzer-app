package app.buzer.android.enforcement.overlay

import app.buzer.android.enforcement.AlarmKind
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayInitialDisplayGateTest {
    @Test
    fun freshStaleOccurrenceDismissesBeforeOverlayCanRender() {
        val request = OverlayBlockerRequest(
            kind = AlarmKind.START_BLOCKER,
            taskId = "task-1",
            occurrenceKey = "occurrence-alarm"
        )
        val freshSnapshot = snapshot(task("task-1", occurrenceKey = "occurrence-current"))

        assertEquals(
            OverlayInitialDisplayDecision.DismissStaleOrResolved,
            OverlayInitialDisplayGate.decide(request, freshSnapshot, cachedSnapshot = null)
        )
    }

    @Test
    fun freshSnapshotThatOmitsTheRootDismissesBeforeOverlayCanRender() {
        val request = OverlayBlockerRequest(
            kind = AlarmKind.START_BLOCKER,
            taskId = "task-omitted-after-unassignment",
            occurrenceKey = "occurrence-alarm"
        )

        assertEquals(
            OverlayInitialDisplayDecision.DismissStaleOrResolved,
            OverlayInitialDisplayGate.decide(request, snapshot(), cachedSnapshot = null)
        )
    }

    private fun snapshot(vararg tasks: TaskSnapshotItem): TasksSnapshotResponse =
        TasksSnapshotResponse(
            scheduleVersion = 1,
            serverTime = "2026-07-16T10:00:00Z",
            tasks = tasks.toList(),
            health = emptyList<HealthStatus>(),
            snapshotGeneration = 1
        )

    private fun task(id: String, occurrenceKey: String): TaskSnapshotItem =
        TaskSnapshotItem(
            id = id,
            todoistTaskId = "todoist-$id",
            title = "Task $id",
            projectId = null,
            assigneeId = "user-self",
            creatorId = "user-self",
            dueDatetime = "2026-07-16T12:00:00Z",
            durationMinutes = 60,
            startAt = "2026-07-16T12:00:00Z",
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
            updatedAt = "2026-07-16T10:00:00Z",
            occurrenceKey = occurrenceKey
        )
}
