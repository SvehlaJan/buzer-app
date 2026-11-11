package app.buzer.shared.state

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthLevel
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.scheduling.CommitmentEligibility

data class SnapshotViewState(
    val scheduleVersion: Long,
    val snapshotGeneration: Long = 0,
    val serverTime: String,
    val invalidTasks: List<TaskSnapshotItem>,
    val healthWarnings: List<HealthStatus>,
    val tasks: List<TaskSnapshotItem> = invalidTasks.distinctBy { it.id }
)

object SnapshotReducer {
    fun reduce(snapshot: TasksSnapshotResponse): SnapshotViewState {
        val visibleTasks = snapshot.tasks.filterNot(CommitmentEligibility::isTerminalCommitment)

        return SnapshotViewState(
            scheduleVersion = snapshot.scheduleVersion,
            snapshotGeneration = snapshot.snapshotGeneration,
            serverTime = snapshot.serverTime,
            invalidTasks = visibleTasks.filter {
                it.state == CommitmentState.invalid ||
                    it.validationErrors.isNotEmpty() ||
                    it.hierarchyStatus == HierarchyStatus.unsupported
            },
            healthWarnings = snapshot.health.filter { it.level != HealthLevel.ok },
            tasks = snapshot.tasks.filterNot { it.isDeleted }
        )
    }
}
