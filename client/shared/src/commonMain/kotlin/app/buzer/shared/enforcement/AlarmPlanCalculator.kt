package app.buzer.shared.enforcement

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

enum class DesiredAlarmKind {
    PRE_START_WARNING,
    START_BLOCKER,
    DEADLINE_BLOCKER
}

data class DesiredAlarm(
    val taskId: String,
    val kind: DesiredAlarmKind,
    val triggerAtIso: String
)

data class AlarmPlan(
    val alarms: List<DesiredAlarm>,
    val cancelTaskIds: Set<String>
)

object AlarmPlanCalculator {
    fun calculate(snapshot: TasksSnapshotResponse, nowIso: String): AlarmPlan {
        val now = Instant.parse(nowIso)
        val alarms = mutableListOf<DesiredAlarm>()
        val cancelTaskIds = mutableSetOf<String>()

        for (task in snapshot.tasks) {
            if (task.shouldCancelAlarms()) {
                cancelTaskIds += task.id
                continue
            }
            if (!task.isAssignedToUser || task.validationErrors.isNotEmpty()) {
                continue
            }

            alarms += desiredAlarmsFor(task, now)
        }

        return AlarmPlan(
            alarms = alarms.sortedWith(
                compareBy<DesiredAlarm> { Instant.parse(it.triggerAtIso) }
                    .thenBy { it.taskId }
                    .thenBy { it.kind.name }
            ),
            cancelTaskIds = cancelTaskIds
        )
    }

    private fun TaskSnapshotItem.shouldCancelAlarms(): Boolean =
        isCompleted ||
            isDeleted ||
            state == CommitmentState.completed ||
            state == CommitmentState.deleted

    private fun desiredAlarmsFor(task: TaskSnapshotItem, now: Instant): List<DesiredAlarm> {
        val startAt = task.startAt?.let(Instant::parse)
        val dueAt = task.dueDatetime?.let(Instant::parse)
        val alarms = mutableListOf<DesiredAlarm>()

        if (startAt != null) {
            val preStartAt = startAt.minus(1.hours)
            if (preStartAt > now) {
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.PRE_START_WARNING, preStartAt.toString())
            }
            if (startAt > now) {
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.START_BLOCKER, startAt.toString())
            }
        }

        if (dueAt != null && dueAt > now) {
            alarms += DesiredAlarm(task.id, DesiredAlarmKind.DEADLINE_BLOCKER, dueAt.toString())
        }

        return alarms
    }
}
