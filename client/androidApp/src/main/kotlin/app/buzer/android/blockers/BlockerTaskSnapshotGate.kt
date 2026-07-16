package app.buzer.android.blockers

import app.buzer.android.enforcement.AlarmKind
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.TasksSnapshotResponse

internal object BlockerTaskSnapshotGate {
    fun isTaskResolved(snapshot: TasksSnapshotResponse, taskId: String): Boolean {
        val task = snapshot.tasks.firstOrNull { it.id == taskId } ?: return false
        return task.isCompleted ||
            task.isDeleted ||
            task.state == CommitmentState.completed ||
            task.state == CommitmentState.deleted
    }

    fun isBlockerResolved(
        snapshot: TasksSnapshotResponse,
        taskId: String,
        occurrenceKey: String,
        kind: AlarmKind
    ): Boolean {
        // Authoritative snapshots omit roots that are no longer owned by this installation's user.
        // An omitted root can never justify preserving a blocker from an old alarm occurrence.
        val task = snapshot.tasks.firstOrNull { it.id == taskId } ?: return true
        if (isTaskResolved(snapshot, taskId)) return true
        if (task.occurrenceKey != occurrenceKey) return true
        return kind == AlarmKind.START_BLOCKER && task.state == CommitmentState.started
    }

    fun shouldHideAfterRefresh(
        snapshot: TasksSnapshotResponse,
        taskId: String,
        occurrenceKey: String,
        kind: AlarmKind
    ): Boolean = isBlockerResolved(snapshot, taskId, occurrenceKey, kind)
}
