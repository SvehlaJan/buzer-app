package app.buzer.shared.scheduling

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.time.parseSnapshotInstantOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class CommitmentWindow(
    val start: Instant,
    val end: Instant
)

object CommitmentEligibility {
    fun isTerminalCommitment(task: TaskSnapshotItem): Boolean =
        task.isDeleted ||
            task.isCompleted ||
            task.state == CommitmentState.completed ||
            task.state == CommitmentState.deleted

    fun isOwnerBlockingTask(task: TaskSnapshotItem): Boolean =
        !isTerminalCommitment(task) &&
            task.isAssignedToUser &&
            (
                task.state == CommitmentState.invalid ||
                    task.hierarchyStatus == HierarchyStatus.unsupported ||
                    task.validationErrors.isNotEmpty()
                )

    fun isEnforceableOwnerCommitment(task: TaskSnapshotItem): Boolean =
        !isTerminalCommitment(task) &&
            task.isAssignedToUser &&
            task.state != CommitmentState.invalid &&
            task.hierarchyStatus == HierarchyStatus.valid &&
            task.validationErrors.isEmpty()

    fun timedWindowOrNull(task: TaskSnapshotItem): CommitmentWindow? {
        if (!isEnforceableOwnerCommitment(task)) return null
        val rawStart = task.startAt ?: task.dueDatetime ?: return null
        val start = parseSnapshotInstantOrNull(rawStart) ?: return null
        val duration = task.durationMinutes?.takeIf { it > 0 } ?: return null
        val end = runCatching { start.plus(duration.minutes) }.getOrNull()?.takeIf { it > start } ?: return null
        return CommitmentWindow(start = start, end = end)
    }

    fun hasActiveStartPriorityAt(tasks: Iterable<TaskSnapshotItem>, now: Instant): Boolean =
        tasks.any { task ->
            val window = timedWindowOrNull(task) ?: return@any false
            now >= window.start &&
                now < window.end &&
                task.startedAt == null &&
                task.state != CommitmentState.started
        }
}
