package app.buzer.shared.state

import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.TasksSnapshotResponse

object SnapshotDiagnostics {
    fun describe(snapshot: TasksSnapshotResponse): String {
        val reduced = SnapshotReducer.reduce(snapshot)
        val completed = snapshot.tasks.count { it.isCompleted }
        val deleted = snapshot.tasks.count { it.isDeleted }
        val visible = snapshot.tasks.size - completed - deleted
        val unsupported = snapshot.tasks.count { it.hierarchyStatus == HierarchyStatus.unsupported }

        return listOf(
            "scheduleVersion=${snapshot.scheduleVersion}",
            "serverTime=${snapshot.serverTime}",
            "tasks=${snapshot.tasks.size}",
            "visible=$visible",
            "invalid=${reduced.invalidTasks.size}",
            "completed=$completed",
            "deleted=$deleted",
            "unsupported=$unsupported",
            "healthWarnings=${reduced.healthWarnings.size}"
        ).joinToString(" ")
    }
}
