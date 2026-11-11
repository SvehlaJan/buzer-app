package app.buzer.shared.enforcement

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.scheduling.CommitmentEligibility
import app.buzer.shared.time.parseSnapshotInstantOrNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

enum class DesiredAlarmKind {
    PRE_START_WARNING,
    START_BLOCKER,
    DEADLINE_BLOCKER
}

data class DesiredAlarmIdentity(
    val taskId: String,
    val occurrenceKey: String,
    val kind: DesiredAlarmKind
)

data class DesiredAlarm(
    val taskId: String,
    val kind: DesiredAlarmKind,
    val triggerAtIso: String,
    val occurrenceKey: String
) {
    init {
        require(occurrenceKey.isNotBlank()) { "occurrenceKey must not be blank" }
    }

    val identity: DesiredAlarmIdentity = DesiredAlarmIdentity(taskId, occurrenceKey, kind)
}

data class AlarmPlan(
    val alarms: List<DesiredAlarm>,
    /** Tasks skipped because a snapshot time could not be parsed (a backend contract violation). */
    val invalidTimeTaskIds: Set<String> = emptySet()
)

object AlarmPlanCalculator {
    fun calculate(snapshot: TasksSnapshotResponse, nowIso: String): AlarmPlan {
        val now = Instant.parse(nowIso)
        val alarms = mutableListOf<DesiredAlarm>()
        val invalidTimeTaskIds = mutableSetOf<String>()

        for (task in snapshot.tasks) {
            if (CommitmentEligibility.isTerminalCommitment(task) ||
                !CommitmentEligibility.isEnforceableOwnerCommitment(task) ||
                task.occurrenceKey.isBlank()
            ) {
                continue
            }

            val taskAlarms = desiredAlarmsFor(task, now)
            if (taskAlarms == null) {
                invalidTimeTaskIds += task.id
                continue
            }
            alarms += taskAlarms
        }

        return AlarmPlan(
            alarms = alarms.sortedWith(
                compareBy<DesiredAlarm> { Instant.parse(it.triggerAtIso) }
                    .thenBy { it.taskId }
                    .thenBy { it.kind.name }
            ),
            invalidTimeTaskIds = invalidTimeTaskIds
        )
    }

    /** Returns null when the task carries a time the strict snapshot parser rejects. */
    private fun desiredAlarmsFor(task: TaskSnapshotItem, now: Instant): List<DesiredAlarm>? {
        val startAt = task.startAt?.let { parseSnapshotInstantOrNull(it) ?: return null }
        val dueAt = task.dueDatetime?.let { parseSnapshotInstantOrNull(it) ?: return null }
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
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.PRE_START_WARNING, preStartAt.toString(), task.occurrenceKey)
            }
            if (startAt > now) {
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.START_BLOCKER, startAt.toString(), task.occurrenceKey)
            } else if (
                task.state == CommitmentState.planned &&
                deadlineAt != null &&
                deadlineAt > now
            ) {
                // Catch-up (decision 2026-07-06, spec 2026-07-06-catchup-start-blocker):
                // a planned task whose block is already in progress still demands
                // "I'm starting". Re-armed by every reconcile until the user acts —
                // the strict nag policy chosen by the owner.
                // "In progress but unstarted" is represented by `planned` today: the backend
                // never sets the intermediate pre_start_warning/start_blocker_active/
                // deadline_blocker_active states. If the backend ever starts advancing state
                // on blocker fire, this guard must widen or the nag silently stops.
                alarms += DesiredAlarm(task.id, DesiredAlarmKind.START_BLOCKER, now.toString(), task.occurrenceKey)
            }
        }

        if (deadlineAt != null && deadlineAt > now) {
            alarms += DesiredAlarm(task.id, DesiredAlarmKind.DEADLINE_BLOCKER, deadlineAt.toString(), task.occurrenceKey)
        }

        return alarms
    }
}
