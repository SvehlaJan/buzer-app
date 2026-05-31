package app.buzer.android.enforcement

import app.buzer.android.sync.SnapshotStorage
import app.buzer.shared.enforcement.AlarmPlanCalculator
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TasksSnapshotResponse
import java.time.Instant
import kotlinx.coroutines.CancellationException

sealed interface CoordinatorActionResult {
    data object Success : CoordinatorActionResult
    data class FailedAndHidden(val message: String) : CoordinatorActionResult
}

class EnforcementCoordinator(
    private val api: EnforcementApi,
    private val snapshotStore: SnapshotStorage,
    private val alarmScheduler: AlarmScheduler,
    private val errorReporter: BlockerErrorReporting,
    private val nowProvider: () -> String = { Instant.now().toString() }
) {
    suspend fun refreshAndReconcile(): TasksSnapshotResponse {
        val snapshot = api.getSnapshot()
        snapshotStore.save(snapshot)
        reconcile(snapshot)
        return snapshot
    }

    fun loadLatestSnapshot(): TasksSnapshotResponse? = snapshotStore.load()

    suspend fun startTask(taskId: String): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.START_TASK,
            taskId = taskId,
            scheduleStartRetry = false
        ) {
            api.startTask(taskId)
        }

    suspend fun postponeStart(taskId: String): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.POSTPONE_START,
            taskId = taskId,
            scheduleStartRetry = true
        ) {
            api.postponeStart(taskId)
        }

    suspend fun completeTask(taskId: String): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.COMPLETE_TASK,
            taskId = taskId,
            scheduleStartRetry = false
        ) {
            api.completeTask(taskId)
            alarmScheduler.cancelAllForTask(taskId)
        }

    suspend fun extendTask(taskId: String): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.EXTEND_TASK,
            taskId = taskId,
            scheduleStartRetry = false
        ) {
            api.extendTask(taskId)
        }

    suspend fun rescheduleTask(taskId: String, request: RescheduleTaskRequest): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.RESCHEDULE_TASK,
            taskId = taskId,
            scheduleStartRetry = false
        ) {
            api.rescheduleTask(taskId, request)
        }

    private fun reconcile(snapshot: TasksSnapshotResponse) {
        val plan = AlarmPlanCalculator.calculate(snapshot, nowProvider())
        snapshot.tasks
            .map { it.id }
            .toSet()
            .forEach(alarmScheduler::cancelAllForTask)
        plan.alarms
            .map(AndroidEnforcementMapper::toScheduledAlarm)
            .forEach(alarmScheduler::schedule)
    }

    private suspend fun runBackendAction(
        action: BlockerAction,
        taskId: String,
        scheduleStartRetry: Boolean,
        call: suspend () -> Unit
    ): CoordinatorActionResult {
        return try {
            call()
            refreshAndReconcile()
            CoordinatorActionResult.Success
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message ?: error::class.simpleName ?: "unknown error"
            errorReporter.report(action, taskId, message)
            if (scheduleStartRetry) {
                alarmScheduler.schedule(startRetryAlarm(taskId))
            }
            CoordinatorActionResult.FailedAndHidden(message)
        }
    }

    private fun startRetryAlarm(taskId: String): ScheduledAlarm =
        ScheduledAlarm(
            taskId = taskId,
            kind = AlarmKind.START_BLOCKER,
            triggerAtEpochMillis = Instant.parse(nowProvider()).plusSeconds(RETRY_DELAY_SECONDS).toEpochMilli()
        )

    private companion object {
        const val RETRY_DELAY_SECONDS = 5L * 60L
    }
}
