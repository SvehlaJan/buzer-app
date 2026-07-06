package app.buzer.shared.enforcement

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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
        val durationMinutes = task.durationMinutes
        val deadlineAt = if (dueAt != null && durationMinutes != null) {
            dueAt.plus(durationMinutes.minutes)
        } else {
            null
        }
        val alarms = mutableListOf<DesiredAlarm>()

        if (startAt != null) {
            val preStartAt = startAt.minus(1.hours)
            if (preStartAt > now) {
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.PRE_START_WARNING, preStartAt.toString())
            }
            if (startAt > now) {
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.START_BLOCKER, startAt.toString())
            } else if (
                task.state == CommitmentState.planned &&
                deadlineAt != null &&
                deadlineAt > now
            ) {
                // Catch-up (decision 2026-07-06, spec 2026-07-06-catchup-start-blocker):
                // a planned task whose block is already in progress still demands
                // "I'm starting". Re-armed by every reconcile until the user acts —
                // the strict nag policy chosen by the owner.
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.START_BLOCKER, now.toString())
            }
        }

        if (deadlineAt != null && deadlineAt > now) {
            alarms += DesiredAlarm(task.id, DesiredAlarmKind.DEADLINE_BLOCKER, deadlineAt.toString())
        }

        return alarms
    }
}
