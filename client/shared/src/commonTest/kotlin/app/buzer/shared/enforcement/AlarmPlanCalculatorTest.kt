package app.buzer.shared.enforcement

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AlarmPlanCalculatorTest {
    @Test
    fun desiredAlarmIdentityIsStableAcrossTriggerMovementAndChangesForKeyRotation() {
        val oldOccurrence = DesiredAlarm(
            taskId = "task-1",
            occurrenceKey = "occurrence-old",
            kind = DesiredAlarmKind.START_BLOCKER,
            triggerAtIso = "2026-05-28T12:00:00Z"
        )
        val newOccurrence = oldOccurrence.copy(occurrenceKey = "occurrence-new")
        val extended = oldOccurrence.copy(triggerAtIso = "2026-05-28T12:30:00Z")

        assertEquals(
            DesiredAlarmIdentity("task-1", "occurrence-old", DesiredAlarmKind.START_BLOCKER),
            oldOccurrence.identity
        )
        assertNotEquals(oldOccurrence.identity, newOccurrence.identity)
        assertEquals(oldOccurrence.identity, extended.identity)
        assertNotEquals(oldOccurrence.triggerAtIso, extended.triggerAtIso)
    }

    @Test
    fun desiredAlarmRejectsBlankOccurrenceKey() {
        assertFailsWith<IllegalArgumentException> {
            DesiredAlarm("task-1", DesiredAlarmKind.START_BLOCKER, "2026-05-28T12:00:00Z", " ")
        }
    }

    @Test
    fun recurringRolloverReplacesSameTimedAlarmsWithNewOccurrenceIdentity() {
        val oldPlan = AlarmPlanCalculator.calculate(
            snapshot(task(id = "recurring", occurrenceKey = "occurrence-old")),
            nowIso = "2026-05-28T09:30:00Z"
        )
        val nextPlan = AlarmPlanCalculator.calculate(
            snapshot(task(id = "recurring", occurrenceKey = "occurrence-next")),
            nowIso = "2026-05-28T09:30:00Z"
        )

        assertEquals(oldPlan.alarms.map { it.triggerAtIso }, nextPlan.alarms.map { it.triggerAtIso })
        assertTrue(oldPlan.alarms.zip(nextPlan.alarms).all { (old, next) -> old.occurrenceKey != next.occurrenceKey })
    }

    @Test
    fun extendKeepsOccurrenceIdentityWhileMovingDeadlineAlarm() {
        val before = AlarmPlanCalculator.calculate(
            snapshot(task(id = "extend", occurrenceKey = "occurrence-1", dueDatetime = "2026-05-28T12:00:00Z")),
            nowIso = "2026-05-28T09:30:00Z"
        )
        val after = AlarmPlanCalculator.calculate(
            snapshot(task(id = "extend", occurrenceKey = "occurrence-1", dueDatetime = "2026-05-28T12:30:00Z", startAt = "2026-05-28T12:30:00Z")),
            nowIso = "2026-05-28T09:30:00Z"
        )

        assertEquals("occurrence-1", before.alarms.last().occurrenceKey)
        assertEquals("occurrence-1", after.alarms.last().occurrenceKey)
        assertTrue(before.alarms.last().triggerAtIso != after.alarms.last().triggerAtIso)
    }

    @Test
    fun checklistOnlyChangeKeepsAlarmIdentities() {
        val before = AlarmPlanCalculator.calculate(
            snapshot(task(id = "checklist", occurrenceKey = "occurrence-1")),
            nowIso = "2026-05-28T09:30:00Z"
        )
        val after = AlarmPlanCalculator.calculate(
            snapshot(
                task(id = "checklist", occurrenceKey = "occurrence-1").copy(
                    checklistItems = listOf(ChecklistItem("child-1", "Checked", checked = true, order = 1, childRevision = "r:2")),
                    checkedCount = 1,
                    totalCount = 1
                )
            ),
            nowIso = "2026-05-28T09:30:00Z"
        )

        assertEquals(before.alarms, after.alarms)
    }

    @Test
    fun unsupportedRootCreatesNoAlarms() {
        val plan = AlarmPlanCalculator.calculate(
            snapshot(task(id = "unsupported", hierarchyStatus = HierarchyStatus.unsupported)),
            nowIso = "2026-05-28T09:30:00Z"
        )

        assertTrue(plan.alarms.isEmpty())
    }

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
                DesiredAlarm("task-1", DesiredAlarmKind.PRE_START_WARNING, "2026-05-28T11:00:00Z", "occurrence-task-1"),
                DesiredAlarm("task-1", DesiredAlarmKind.START_BLOCKER, "2026-05-28T12:00:00Z", "occurrence-task-1"),
                DesiredAlarm("task-1", DesiredAlarmKind.DEADLINE_BLOCKER, "2026-05-28T13:00:00Z", "occurrence-task-1")
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
                DesiredAlarm("no-duration", DesiredAlarmKind.PRE_START_WARNING, "2026-05-28T11:00:00Z", "occurrence-no-duration"),
                DesiredAlarm("no-duration", DesiredAlarmKind.START_BLOCKER, "2026-05-28T12:00:00Z", "occurrence-no-duration")
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
    fun skipsInvalidStateEvenWhenBackendValidationErrorsAreEmpty() {
        val plan = AlarmPlanCalculator.calculate(
            snapshot(task(id = "invalid-state", state = CommitmentState.invalid)),
            nowIso = "2026-05-28T09:30:00Z"
        )

        assertTrue(plan.alarms.isEmpty())
    }

    @Test
    fun skipsTasksWithoutAnOccurrenceKeyInsteadOfCreatingAnUnidentifiableAlarm() {
        val plan = AlarmPlanCalculator.calculate(
            snapshot(task(id = "missing-occurrence", occurrenceKey = "")),
            nowIso = "2026-05-28T09:30:00Z"
        )

        assertTrue(plan.alarms.isEmpty())
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
                DesiredAlarm("already-starting", DesiredAlarmKind.START_BLOCKER, "2026-05-28T12:00:00Z", "occurrence-already-starting"),
                DesiredAlarm("already-starting", DesiredAlarmKind.DEADLINE_BLOCKER, "2026-05-28T13:00:00Z", "occurrence-already-starting")
            ),
            plan.alarms
        )
        assertTrue(plan.cancelTaskIds.isEmpty())
    }

    @Test
    fun schedulesCatchUpStartAndDeadlineForInProgressPlannedBlock() {
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
                DesiredAlarm("in-progress", DesiredAlarmKind.START_BLOCKER, "2026-05-28T12:30:00Z", "occurrence-in-progress"),
                DesiredAlarm("in-progress", DesiredAlarmKind.DEADLINE_BLOCKER, "2026-05-28T13:00:00Z", "occurrence-in-progress")
            ),
            plan.alarms
        )
        assertTrue(plan.cancelTaskIds.isEmpty())
    }

    @Test
    fun schedulesNoCatchUpForStartedInProgressBlock() {
        // started = the user already acted; only the deadline alarm remains.
        val snapshot = snapshot(
            task(
                id = "in-progress-started",
                dueDatetime = "2026-05-28T12:00:00Z",
                durationMinutes = 60,
                startAt = "2026-05-28T12:00:00Z",
                state = CommitmentState.started,
                startedAt = "2026-05-28T12:05:00Z"
            )
        )

        val plan = AlarmPlanCalculator.calculate(snapshot, nowIso = "2026-05-28T12:30:00Z")

        assertEquals(
            listOf(
                DesiredAlarm("in-progress-started", DesiredAlarmKind.DEADLINE_BLOCKER, "2026-05-28T13:00:00Z", "occurrence-in-progress-started")
            ),
            plan.alarms
        )
        assertTrue(plan.cancelTaskIds.isEmpty())
    }

    @Test
    fun schedulesNothingForPlannedBlockFullyInThePast() {
        val snapshot = snapshot(
            task(
                id = "fully-past",
                dueDatetime = "2026-05-28T12:00:00Z",
                durationMinutes = 60,
                startAt = "2026-05-28T12:00:00Z"
            )
        )

        val plan = AlarmPlanCalculator.calculate(snapshot, nowIso = "2026-05-28T14:00:00Z")

        assertTrue(plan.alarms.isEmpty())
        assertTrue(plan.cancelTaskIds.isEmpty())
    }

    private fun snapshot(vararg tasks: TaskSnapshotItem): TasksSnapshotResponse =
        TasksSnapshotResponse(
            scheduleVersion = 1,
            serverTime = "2026-05-28T09:00:00Z",
            tasks = tasks.toList(),
            health = emptyList<HealthStatus>(),
            snapshotGeneration = 1
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
        validationErrors: List<String> = emptyList(),
        startedAt: String? = null,
        occurrenceKey: String = "occurrence-$id",
        hierarchyStatus: HierarchyStatus = HierarchyStatus.valid
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
            startedAt = startedAt,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-05-28T09:00:00Z",
            occurrenceKey = occurrenceKey,
            hierarchyStatus = hierarchyStatus
        )
}
