package app.buzer.shared.enforcement

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlarmPlanCalculatorTest {
    @Test
    fun schedulesFutureAlarmsForValidActiveTask() {
        val snapshot = snapshot(
            task(
                id = "task-1",
                dueDatetime = "2026-05-28T12:00:00Z",
                durationMinutes = 60,
                startAt = "2026-05-28T12:00:00Z"
            )
        )

        val plan = AlarmPlanCalculator.calculate(snapshot, nowIso = "2026-05-28T09:30:00Z")

        assertEquals(
            listOf(
                DesiredAlarm("task-1", DesiredAlarmKind.PRE_START_WARNING, "2026-05-28T11:00:00Z"),
                DesiredAlarm("task-1", DesiredAlarmKind.START_BLOCKER, "2026-05-28T12:00:00Z"),
                DesiredAlarm("task-1", DesiredAlarmKind.DEADLINE_BLOCKER, "2026-05-28T13:00:00Z")
            ),
            plan.alarms
        )
        assertTrue(plan.cancelTaskIds.isEmpty())
    }

    @Test
    fun schedulesNoDeadlineAlarmWhenDurationIsNull() {
        val snapshot = snapshot(
            task(
                id = "no-duration",
                dueDatetime = "2026-05-28T12:00:00Z",
                durationMinutes = null,
                startAt = "2026-05-28T12:00:00Z"
            )
        )

        val plan = AlarmPlanCalculator.calculate(snapshot, nowIso = "2026-05-28T09:30:00Z")

        assertEquals(
            listOf(
                DesiredAlarm("no-duration", DesiredAlarmKind.PRE_START_WARNING, "2026-05-28T11:00:00Z"),
                DesiredAlarm("no-duration", DesiredAlarmKind.START_BLOCKER, "2026-05-28T12:00:00Z")
            ),
            plan.alarms
        )
        assertTrue(plan.cancelTaskIds.isEmpty())
    }

    @Test
    fun cancelsCompletedAndDeletedTasks() {
        val snapshot = snapshot(
            task(id = "done", state = CommitmentState.completed, isCompleted = true),
            task(id = "deleted", state = CommitmentState.deleted, isDeleted = true)
        )

        val plan = AlarmPlanCalculator.calculate(snapshot, nowIso = "2026-05-28T09:30:00Z")

        assertTrue(plan.alarms.isEmpty())
        assertEquals(setOf("done", "deleted"), plan.cancelTaskIds)
    }

    @Test
    fun skipsInvalidTasksForPerTaskAlarms() {
        val snapshot = snapshot(
            task(
                id = "invalid",
                state = CommitmentState.invalid,
                dueDatetime = null,
                durationMinutes = null,
                startAt = null,
                validationErrors = listOf("exact_due_datetime_required")
            )
        )

        val plan = AlarmPlanCalculator.calculate(snapshot, nowIso = "2026-05-28T09:30:00Z")

        assertTrue(plan.alarms.isEmpty())
        assertTrue(plan.cancelTaskIds.isEmpty())
    }

    @Test
    fun skipsTasksNotAssignedToUserForPerTaskAlarms() {
        val snapshot = snapshot(
            task(id = "other-user", isAssignedToUser = false)
        )

        val plan = AlarmPlanCalculator.calculate(snapshot, nowIso = "2026-05-28T09:30:00Z")

        assertTrue(plan.alarms.isEmpty())
        assertTrue(plan.cancelTaskIds.isEmpty())
    }

    @Test
    fun schedulesOnlyTriggersStillInTheFuture() {
        val snapshot = snapshot(
            task(
                id = "already-starting",
                dueDatetime = "2026-05-28T12:00:00Z",
                durationMinutes = 60,
                startAt = "2026-05-28T12:00:00Z"
            )
        )

        val plan = AlarmPlanCalculator.calculate(snapshot, nowIso = "2026-05-28T11:30:00Z")

        assertEquals(
            listOf(
                DesiredAlarm("already-starting", DesiredAlarmKind.START_BLOCKER, "2026-05-28T12:00:00Z"),
                DesiredAlarm("already-starting", DesiredAlarmKind.DEADLINE_BLOCKER, "2026-05-28T13:00:00Z")
            ),
            plan.alarms
        )
        assertTrue(plan.cancelTaskIds.isEmpty())
    }

    @Test
    fun schedulesDeadlineForInProgressBlock() {
        val snapshot = snapshot(
            task(
                id = "in-progress",
                dueDatetime = "2026-05-28T12:00:00Z",
                durationMinutes = 60,
                startAt = "2026-05-28T12:00:00Z"
            )
        )

        val plan = AlarmPlanCalculator.calculate(snapshot, nowIso = "2026-05-28T12:30:00Z")

        assertEquals(
            listOf(
                DesiredAlarm("in-progress", DesiredAlarmKind.DEADLINE_BLOCKER, "2026-05-28T13:00:00Z")
            ),
            plan.alarms
        )
        assertTrue(plan.cancelTaskIds.isEmpty())
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
        dueDatetime: String? = "2026-05-28T12:00:00Z",
        durationMinutes: Int? = 60,
        startAt: String? = "2026-05-28T12:00:00Z",
        isCompleted: Boolean = false,
        isDeleted: Boolean = false,
        isAssignedToUser: Boolean = true,
        validationErrors: List<String> = emptyList()
    ): TaskSnapshotItem =
        TaskSnapshotItem(
            id = id,
            todoistTaskId = "todoist-$id",
            title = "Task $id",
            projectId = null,
            assigneeId = "user-self",
            creatorId = "user-self",
            dueDatetime = dueDatetime,
            durationMinutes = durationMinutes,
            startAt = startAt,
            state = state,
            isAssignedToUser = isAssignedToUser,
            isCreatedByUser = true,
            isCompleted = isCompleted,
            isDeleted = isDeleted,
            validationErrors = validationErrors,
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-05-28T09:00:00Z"
        )
}
