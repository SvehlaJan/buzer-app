package app.buzer.android.enforcement

import app.buzer.android.sync.SnapshotStorage
import app.buzer.shared.enforcement.AlarmPlanCalculator
import app.buzer.shared.enforcement.DesiredAlarmKind
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.state.SnapshotDiagnostics
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
    private val nowProvider: () -> String = { Instant.now().toString() },
    private val logger: (String) -> Unit = {}
) {
    suspend fun refreshAndReconcile(): TasksSnapshotResponse {
        logger("refresh start")
        val snapshot = api.getSnapshot()
        logger("refresh fetched ${SnapshotDiagnostics.describe(snapshot)}")
        snapshotStore.save(snapshot)
        reconcile(snapshot)
        logger("refresh complete scheduleVersion=${snapshot.scheduleVersion}")
        return snapshot
    }

    fun loadLatestSnapshot(): TasksSnapshotResponse? {
        val snapshot = snapshotStore.load()
        if (snapshot == null) {
            logger("snapshot load result=miss")
        } else {
            logger("snapshot load result=hit ${SnapshotDiagnostics.describe(snapshot)}")
        }
        return snapshot
    }

    /**
     * Reschedules alarms from the latest cached snapshot without any network call.
     * Returns true when a cached snapshot was found and reconciled, false on cache miss.
     * Used by boot recovery so enforcement survives reboot without opening the app.
     */
    fun reconcileLatestSnapshot(): Boolean {
        val snapshot = loadLatestSnapshot() ?: return false
        reconcile(snapshot)
        logger("cached reconcile complete scheduleVersion=${snapshot.scheduleVersion}")
        return true
    }

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

    suspend fun dismissMorningReview(): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.MORNING_REVIEW_DISMISS,
            taskId = MORNING_REVIEW_TASK_ID,
            scheduleStartRetry = false
        ) {
            api.dismissMorningReview()
        }

    private fun reconcile(snapshot: TasksSnapshotResponse) {
        val plan = AlarmPlanCalculator.calculate(snapshot, nowProvider())
        val cancelledTaskIds = snapshot.tasks.map { it.id }.toSet()
        cancelledTaskIds.forEach(alarmScheduler::cancelAllForTask)
        plan.alarms
            .map(AndroidEnforcementMapper::toScheduledAlarm)
            .forEach(alarmScheduler::schedule)
        logger(
            "reconcile complete cancelled=${cancelledTaskIds.size} scheduled=${plan.alarms.size} " +
                "preStart=${plan.alarms.count { it.kind == DesiredAlarmKind.PRE_START_WARNING }} " +
                "start=${plan.alarms.count { it.kind == DesiredAlarmKind.START_BLOCKER }} " +
                "deadline=${plan.alarms.count { it.kind == DesiredAlarmKind.DEADLINE_BLOCKER }}"
        )
    }

    private suspend fun runBackendAction(
        action: BlockerAction,
        taskId: String,
        scheduleStartRetry: Boolean,
        call: suspend () -> Unit
    ): CoordinatorActionResult {
        return try {
            logger("action start action=$action taskId=$taskId")
            call()
            refreshAndReconcile()
            logger("action success action=$action taskId=$taskId")
            CoordinatorActionResult.Success
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message ?: error::class.simpleName ?: "unknown error"
            errorReporter.report(action, taskId, message)
            logger("action failed action=$action taskId=$taskId message=$message")
            if (scheduleStartRetry) {
                alarmScheduler.schedule(startRetryAlarm(taskId))
                logger("action retry scheduled action=$action taskId=$taskId delaySeconds=$RETRY_DELAY_SECONDS")
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

    companion object {
        const val MORNING_REVIEW_TASK_ID = "morning-review"

        private const val RETRY_DELAY_SECONDS = 5L * 60L
    }
}
