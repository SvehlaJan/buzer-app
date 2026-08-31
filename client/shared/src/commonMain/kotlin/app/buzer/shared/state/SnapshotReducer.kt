package app.buzer.shared.state

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthLevel
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse

data class SnapshotViewState(
    val scheduleVersion: Long,
    val serverTime: String,
    val invalidTasks: List<TaskSnapshotItem>,
    val activeBlockers: List<TaskSnapshotItem>,
    val inProgressTasks: List<TaskSnapshotItem>,
    val startingToday: List<TaskSnapshotItem>,
    val healthWarnings: List<HealthStatus>,
    val tasks: List<TaskSnapshotItem> = (
        invalidTasks +
            activeBlockers +
            inProgressTasks +
            startingToday
        ).distinctBy { it.id }
)

data class SnapshotWithCallerDraft<D>(
    val viewState: SnapshotViewState,
    val callerDraft: D
)

object SnapshotReducer {
    fun reduce(snapshot: TasksSnapshotResponse): SnapshotViewState {
        val serverDate = snapshot.serverTime.isoDatePrefix()
        val visibleTasks = snapshot.tasks.filterNot { it.isCompleted || it.isDeleted }

        return SnapshotViewState(
            scheduleVersion = snapshot.scheduleVersion,
            serverTime = snapshot.serverTime,
            invalidTasks = visibleTasks.filter {
                it.state == CommitmentState.invalid ||
                    it.validationErrors.isNotEmpty() ||
                    it.hierarchyStatus == HierarchyStatus.unsupported
            },
            activeBlockers = visibleTasks.filter {
                it.state == CommitmentState.start_blocker_active ||
                    it.state == CommitmentState.deadline_blocker_active
            },
            inProgressTasks = visibleTasks.filter { it.state == CommitmentState.started },
            startingToday = visibleTasks.filter {
                it.state == CommitmentState.planned && it.startAt?.isoDatePrefix() == serverDate
            },
            healthWarnings = snapshot.health.filter { it.level != HealthLevel.ok },
            tasks = snapshot.tasks.filterNot { it.isDeleted }
        )
    }

    fun <D> replaceAuthoritative(
        cached: SnapshotWithCallerDraft<D>,
        snapshot: TasksSnapshotResponse
    ): SnapshotWithCallerDraft<D> = cached.copy(viewState = reduce(snapshot))
}

private fun String.isoDatePrefix(): String =
    substringBefore('T')
