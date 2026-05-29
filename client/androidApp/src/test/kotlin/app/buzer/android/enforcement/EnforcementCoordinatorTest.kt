package app.buzer.android.enforcement

import app.buzer.android.sync.SnapshotStorage
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnforcementCoordinatorTest {
    @Test
    fun successfulRefreshSavesSnapshotAndSchedulesPlannedAlarms() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val store = FakeSnapshotStore()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1"))),
            snapshotStore = store,
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val refreshed = coordinator.refreshAndReconcile()

        assertEquals(refreshed, store.saved)
        assertEquals(1, store.saved?.scheduleVersion)
        assertEquals(
            listOf(
                scheduled("task-1", AlarmKind.PRE_START_WARNING, "2026-05-28T10:00:00Z"),
                scheduled("task-1", AlarmKind.START_BLOCKER, "2026-05-28T11:00:00Z"),
                scheduled("task-1", AlarmKind.DEADLINE_BLOCKER, "2026-05-28T12:00:00Z")
            ),
            scheduler.scheduled
        )
    }

    @Test
    fun completedAndDeletedTasksCancelAlarmsAfterRefresh() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(
                snapshot(
                    task("done", state = CommitmentState.completed, isCompleted = true),
                    task("deleted", state = CommitmentState.deleted, isDeleted = true)
                )
            ),
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        coordinator.refreshAndReconcile()

        assertEquals(listOf("done", "deleted"), scheduler.cancelledAll)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun postponeFailureReportsErrorAndSchedulesFiveMinuteStartBlockerRetry() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val reporter = RecordingErrorReporter()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1")), failPostpone = true),
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = scheduler,
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.postponeStart("task-1")

        assertTrue(result is CoordinatorActionResult.FailedAndHidden)
        assertEquals(
            listOf(scheduled("task-1", AlarmKind.START_BLOCKER, "2026-05-28T09:35:00Z")),
            scheduler.scheduled
        )
        assertEquals(listOf(ErrorRecord(BlockerAction.POSTPONE_START, "task-1", "backend unavailable")), reporter.errors)
    }

    @Test
    fun deadlineCompleteFailureReportsErrorWithoutRetry() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val reporter = RecordingErrorReporter()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1")), failComplete = true),
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = scheduler,
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.completeTask("task-1")

        assertTrue(result is CoordinatorActionResult.FailedAndHidden)
        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(listOf(ErrorRecord(BlockerAction.COMPLETE_TASK, "task-1", "backend unavailable")), reporter.errors)
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
            startAt = "2026-05-28T11:00:00Z",
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

    private fun scheduled(taskId: String, kind: AlarmKind, triggerAtIso: String): ScheduledAlarm =
        ScheduledAlarm(taskId, kind, Instant.parse(triggerAtIso).toEpochMilli())

    private class FakeBuzerApi(
        private val snapshot: TasksSnapshotResponse,
        private val failPostpone: Boolean = false,
        private val failComplete: Boolean = false
    ) : EnforcementApi {
        override suspend fun getSnapshot(): TasksSnapshotResponse = snapshot
        override suspend fun startTask(id: String) = Unit
        override suspend fun postponeStart(id: String) {
            if (failPostpone) error("backend unavailable")
        }
        override suspend fun completeTask(id: String) {
            if (failComplete) error("backend unavailable")
        }
        override suspend fun extendTask(id: String) = Unit
        override suspend fun rescheduleTask(id: String, request: RescheduleTaskRequest) = Unit
    }

    private class FakeSnapshotStore : SnapshotStorage {
        var saved: TasksSnapshotResponse? = null

        override fun save(snapshot: TasksSnapshotResponse) {
            saved = snapshot
        }

        override fun load(): TasksSnapshotResponse? = saved
    }

    private class RecordingAlarmScheduler : AlarmScheduler {
        val scheduled = mutableListOf<ScheduledAlarm>()
        val cancelledAll = mutableListOf<String>()

        override fun schedule(alarm: ScheduledAlarm) {
            scheduled += alarm
        }

        override fun cancel(taskId: String, kind: AlarmKind) = Unit

        override fun cancelAllForTask(taskId: String) {
            cancelledAll += taskId
        }
    }

    private data class ErrorRecord(
        val action: BlockerAction,
        val taskId: String?,
        val message: String
    )

    private class RecordingErrorReporter : BlockerErrorReporting {
        val errors = mutableListOf<ErrorRecord>()

        override fun report(action: BlockerAction, taskId: String?, message: String, showNotification: Boolean) {
            errors += ErrorRecord(action, taskId, message)
        }
    }
}
