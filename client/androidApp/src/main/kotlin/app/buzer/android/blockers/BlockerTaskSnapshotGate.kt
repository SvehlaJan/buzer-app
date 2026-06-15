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

    fun isBlockerResolved(snapshot: TasksSnapshotResponse, taskId: String, kind: AlarmKind): Boolean {
        val task = snapshot.tasks.firstOrNull { it.id == taskId } ?: return false
        if (isTaskResolved(snapshot, taskId)) return true
        return kind == AlarmKind.START_BLOCKER && task.state == CommitmentState.started
    }
}
