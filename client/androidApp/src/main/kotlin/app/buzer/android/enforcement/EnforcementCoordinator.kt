package app.buzer.android.enforcement

import app.buzer.android.sync.SnapshotStorage
import app.buzer.shared.api.BuzerApiException
import app.buzer.shared.enforcement.AlarmPlanCalculator
import app.buzer.shared.enforcement.DesiredAlarmKind
import app.buzer.shared.model.FocusedChecklistMutationRequest
import app.buzer.shared.model.ExtendTaskRequest
import app.buzer.shared.model.MutationOutcome
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.state.SnapshotDiagnostics
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import app.buzer.android.observability.AndroidObservability
import app.buzer.android.observability.ObservabilityRuntime

sealed interface CoordinatorActionResult {
    data object Success : CoordinatorActionResult
    data class Pending(val response: MutationResponse) : CoordinatorActionResult
    data class Stale(val snapshot: TasksSnapshotResponse, val message: String) : CoordinatorActionResult
    data class RetryAfter(
        val retryAfterSeconds: Long,
        val message: String,
        val retry: suspend () -> CoordinatorActionResult
    ) : CoordinatorActionResult
    data object ChildActionInFlight : CoordinatorActionResult
    data class LocalReconciliationFailed(
        val snapshot: TasksSnapshotResponse,
        val message: String
    ) : CoordinatorActionResult
    data class FailedAndHidden(val message: String) : CoordinatorActionResult
}

class EnforcementCoordinator(
    private val api: EnforcementApi,
    private val snapshotStore: SnapshotStorage,
    private val alarmScheduler: AlarmScheduler,
    private val scheduledAlarmRegistry: ScheduledAlarmRegistry =
        ScheduledAlarmRegistry(InMemoryScheduledAlarmRegistryStorage()),
    private val errorReporter: BlockerErrorReporting,
    private val blockerNotificationCleaner: BlockerNotificationCleaner = NoOpBlockerNotificationCleaner,
    private val nowProvider: () -> String = { Instant.now().toString() },
    private val logger: (String) -> Unit = {},
    private val observability: AndroidObservability = ObservabilityRuntime.instance
) {
    private val childActionMutex = Mutex()
    private val inFlightChildren = mutableSetOf<Pair<String, String>>()
    /**
     * Only persisted snapshot/reconciliation work is serialized. Network calls deliberately stay
     * outside this lock so independent child mutations can be sent concurrently.
     */
    private val snapshotApplicationLock = Any()

    private data class AuthorityApplication(
        val snapshot: TasksSnapshotResponse,
        val applied: Boolean,
        val stale: Boolean
    )

    suspend fun refreshAndReconcile(): TasksSnapshotResponse {
        logger("refresh start")
        val snapshot = api.getSnapshot()
        logger("refresh fetched ${SnapshotDiagnostics.describe(snapshot)}")
        val appliedSnapshot = applyFreshSnapshot(snapshot)
        logger("refresh complete scheduleVersion=${appliedSnapshot.scheduleVersion}")
        return appliedSnapshot
    }

    suspend fun forceSyncAndReconcile(): TasksSnapshotResponse {
        logger("force_sync start")
        val snapshot = api.forceSync()
        logger("force_sync fetched ${SnapshotDiagnostics.describe(snapshot)}")
        val appliedSnapshot = applyFreshSnapshot(snapshot)
        logger("force_sync complete scheduleVersion=${appliedSnapshot.scheduleVersion}")
        return appliedSnapshot
    }

    /** Applies mutation authority already received from the backend; never refetches it. */
    fun applyReturnedSnapshot(snapshot: TasksSnapshotResponse): TasksSnapshotResponse =
        applyFreshSnapshot(snapshot)

    fun loadLatestSnapshot(): TasksSnapshotResponse? {
        synchronized(snapshotApplicationLock) {
            return loadLatestSnapshotLocked()
        }
    }

    /**
     * Reschedules alarms from the latest cached snapshot without any network call.
     * Returns true when a cached snapshot was found and reconciled, false on cache miss.
     * Used by boot recovery so enforcement survives reboot without opening the app.
     */
    fun reconcileLatestSnapshot(): Boolean {
        synchronized(snapshotApplicationLock) {
            val snapshot = loadLatestSnapshotLocked() ?: return false
            if (snapshot.snapshotGeneration <= 0) {
                logger("cached reconcile skipped legacyGeneration=${snapshot.snapshotGeneration}")
                return false
            }
            reconcile(snapshot)
            logger("cached reconcile complete scheduleVersion=${snapshot.scheduleVersion}")
            return true
        }
    }

    suspend fun startTask(taskId: String, request: TaskActionRequest): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.START_TASK,
            taskId = taskId,
            request = request
        ) {
            api.startTask(taskId, request)
        }

    suspend fun postponeStart(taskId: String, request: TaskActionRequest): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.POSTPONE_START,
            taskId = taskId,
            request = request
        ) {
            api.postponeStart(taskId, request)
        }

    suspend fun completeTask(taskId: String, request: TaskActionRequest): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.COMPLETE_TASK,
            taskId = taskId,
            request = request
        ) {
            api.completeTask(taskId, request)
        }

    suspend fun extendTask(taskId: String, request: ExtendTaskRequest): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.EXTEND_TASK,
            taskId = taskId,
            request = TaskActionRequest(
                actionId = request.actionId,
                deviceId = request.deviceId,
                expectedOccurrenceKey = request.expectedOccurrenceKey,
                expectedTaskRevision = request.expectedTaskRevision
            )
        ) {
            api.extendTask(taskId, request)
        }

    suspend fun updateTask(
        taskId: String,
        request: UpdateTaskRequest
    ): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.UPDATE_TASK,
            taskId = taskId,
            request = TaskActionRequest(
                actionId = request.actionId,
                deviceId = request.deviceId,
                expectedOccurrenceKey = request.expectedOccurrenceKey
            )
        ) {
            api.updateTask(taskId, request)
        }

    suspend fun checkChild(
        taskId: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): CoordinatorActionResult = runFocusedChildAction(
        action = BlockerAction.CHECK_CHILD,
        taskId = taskId,
        childId = childId,
        request = request
    ) {
        api.checkChecklistItem(taskId, childId, request)
    }

    suspend fun uncheckChild(
        taskId: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): CoordinatorActionResult = runFocusedChildAction(
        action = BlockerAction.UNCHECK_CHILD,
        taskId = taskId,
        childId = childId,
        request = request
    ) {
        api.uncheckChecklistItem(taskId, childId, request)
    }

    private fun reconcile(snapshot: TasksSnapshotResponse) {
        val plan = AlarmPlanCalculator.calculate(snapshot, nowProvider())
        val scheduledAlarms = plan.alarms
            .map(AndroidEnforcementMapper::toScheduledAlarm)
        scheduledAlarmRegistry.reconcile(scheduledAlarms, alarmScheduler)
        logger(
            "reconcile complete scheduled=${plan.alarms.size} " +
                "preStart=${plan.alarms.count { it.kind == DesiredAlarmKind.PRE_START_WARNING }} " +
                "start=${plan.alarms.count { it.kind == DesiredAlarmKind.START_BLOCKER }} " +
                "deadline=${plan.alarms.count { it.kind == DesiredAlarmKind.DEADLINE_BLOCKER }}"
        )
    }

    /**
     * Accepts only the backend's mutation authority. In particular, no local checked/completed
     * state is inferred when a request fails, and alarm removal happens only after reconciliation
     * of the returned snapshot.
     */
    private fun applyMutationResponse(
        taskId: String,
        occurrenceKey: String,
        response: MutationResponse
    ): CoordinatorActionResult = applyAuthoritativeSnapshot(
        taskId = taskId,
        occurrenceKey = occurrenceKey,
        snapshot = response.snapshot
    ) { appliedSnapshot ->
        logger(
            "mutation applied taskId=$taskId occurrence=$occurrenceKey " +
                "actionId=${response.actionId} outcome=${response.outcome}"
        )
        when (response.outcome) {
            MutationOutcome.completed -> CoordinatorActionResult.Success
            MutationOutcome.reconciliation_pending -> CoordinatorActionResult.Pending(response.copy(snapshot = appliedSnapshot))
        }
    }

    private fun applyConflictSnapshot(
        taskId: String,
        occurrenceKey: String,
        snapshot: TasksSnapshotResponse,
        message: String
    ): CoordinatorActionResult = applyAuthoritativeSnapshot(
        taskId = taskId,
        occurrenceKey = occurrenceKey,
        snapshot = snapshot
    ) { appliedSnapshot ->
        CoordinatorActionResult.Stale(appliedSnapshot, message)
    }

    /** Serializes local application only; API requests happen before entering this boundary. */
    private fun applyAuthoritativeSnapshot(
        taskId: String,
        occurrenceKey: String,
        snapshot: TasksSnapshotResponse,
        result: (TasksSnapshotResponse) -> CoordinatorActionResult
    ): CoordinatorActionResult = synchronized(snapshotApplicationLock) {
        try {
            val application = applySnapshotGeneration(snapshotStore.load(), snapshot)
            when {
                application.stale -> {
                    logger(
                        "authoritative snapshot ignored_stale taskId=$taskId occurrence=$occurrenceKey " +
                            "incomingGeneration=${snapshot.snapshotGeneration} " +
                            "cachedGeneration=${application.snapshot.snapshotGeneration}"
                    )
                    CoordinatorActionResult.Stale(
                        snapshot = application.snapshot,
                        message = "A newer authoritative snapshot is already applied"
                    )
                }

                application.applied -> {
                    snapshotStore.save(application.snapshot)
                    reconcile(application.snapshot)
                    blockerNotificationCleaner.cancelForTask(taskId, occurrenceKey)
                    result(application.snapshot)
                }

                else -> {
                    // Equal generation is an idempotent replay of the same complete authority.
                    // It must not write/reconcile again, but the completed action may clear its notification.
                    blockerNotificationCleaner.cancelForTask(taskId, occurrenceKey)
                    result(application.snapshot)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message ?: error::class.simpleName ?: "local reconciliation failed"
            logger(
                "authoritative snapshot local_failure taskId=$taskId occurrence=$occurrenceKey " +
                    "scheduleVersion=${snapshot.scheduleVersion} message=$message"
            )
            CoordinatorActionResult.LocalReconciliationFailed(snapshot, message)
        }
    }

    private fun applyFreshSnapshot(snapshot: TasksSnapshotResponse): TasksSnapshotResponse =
        synchronized(snapshotApplicationLock) {
            val application = applySnapshotGeneration(snapshotStore.load(), snapshot)
            if (application.applied) {
                snapshotStore.save(application.snapshot)
                reconcile(application.snapshot)
            } else if (application.stale) {
                logger(
                    "refresh ignored_stale incomingGeneration=${snapshot.snapshotGeneration} " +
                        "cachedGeneration=${application.snapshot.snapshotGeneration}"
                )
            }
            application.snapshot
        }

    private fun loadLatestSnapshotLocked(): TasksSnapshotResponse? {
        val snapshot = snapshotStore.load()
        if (snapshot == null) {
            logger("snapshot load result=miss")
        } else {
            logger("snapshot load result=hit ${SnapshotDiagnostics.describe(snapshot)}")
        }
        return snapshot
    }

    /**
     * `snapshotGeneration` is the only ordering token for a complete snapshot.
     * Root and child revisions are opaque request preconditions and must never be parsed here.
     */
    private fun applySnapshotGeneration(
        current: TasksSnapshotResponse?,
        incoming: TasksSnapshotResponse
    ): AuthorityApplication {
        if (incoming.snapshotGeneration <= 0) {
            return AuthorityApplication(
                snapshot = current ?: incoming,
                applied = false,
                stale = current != null
            )
        }
        if (current == null || current.snapshotGeneration <= 0) {
            return AuthorityApplication(incoming, applied = true, stale = false)
        }
        return when {
            incoming.snapshotGeneration > current.snapshotGeneration ->
                AuthorityApplication(incoming, applied = true, stale = false)
            incoming.snapshotGeneration == current.snapshotGeneration ->
                AuthorityApplication(current, applied = false, stale = false)
            else -> AuthorityApplication(current, applied = false, stale = true)
        }
    }

    private suspend fun runBackendAction(
        action: BlockerAction,
        taskId: String,
        request: TaskActionRequest,
        call: suspend () -> MutationResponse
    ): CoordinatorActionResult {
        val occurrenceKey = request.expectedOccurrenceKey
        return try {
            logger("action start action=$action taskId=$taskId occurrence=$occurrenceKey")
            applyMutationResponse(taskId, occurrenceKey, call())
        } catch (error: CancellationException) {
            throw error
        } catch (error: BuzerApiException) {
            val snapshot = error.snapshot
            when {
                error.status == 409 && snapshot != null -> {
                    applyConflictSnapshot(
                        taskId = taskId,
                        occurrenceKey = request.expectedOccurrenceKey,
                        snapshot = snapshot,
                        message = error.message ?: "Task changed on another device"
                    )
                }

                error.status == 429 -> {
                    val retryAfterSeconds = error.retryAfterSeconds ?: RETRY_DELAY_SECONDS
                    CoordinatorActionResult.RetryAfter(
                        retryAfterSeconds = retryAfterSeconds,
                        message = error.message ?: "Too many requests",
                        retry = { runBackendAction(action, taskId, request, call) }
                    )
                }

                else -> failedAction(action, taskId, request.expectedOccurrenceKey, error, request.actionId)
            }
        } catch (error: Exception) {
            failedAction(action, taskId, request.expectedOccurrenceKey, error, request.actionId)
        }
    }

    private suspend fun runFocusedChildAction(
        action: BlockerAction,
        taskId: String,
        childId: String,
        request: FocusedChecklistMutationRequest,
        call: suspend () -> MutationResponse
    ): CoordinatorActionResult {
        val childIdentity = taskId to childId
        if (!childActionMutex.withLock { inFlightChildren.add(childIdentity) }) {
            return CoordinatorActionResult.ChildActionInFlight
        }
        return try {
            val result = runBackendAction(
                action = action,
                taskId = taskId,
                request = TaskActionRequest(
                    actionId = request.actionId,
                    deviceId = request.deviceId,
                    expectedOccurrenceKey = request.expectedOccurrenceKey
                ),
                call = call
            )
            if (result is CoordinatorActionResult.RetryAfter) {
                result.copy(
                    retry = {
                        runFocusedChildAction(action, taskId, childId, request, call)
                    }
                )
            } else {
                result
            }
        } finally {
            childActionMutex.withLock { inFlightChildren.remove(childIdentity) }
        }
    }

    private fun failedAction(
        action: BlockerAction,
        taskId: String,
        occurrenceKey: String,
        error: Throwable,
        actionId: String? = null
    ): CoordinatorActionResult {
        val message = error.message ?: error::class.simpleName ?: "unknown error"
        errorReporter.report(action, taskId, message, showNotification = true, throwable = error)
        logger("action failed action=$action taskId=$taskId occurrence=$occurrenceKey message=$message")
        if (action == BlockerAction.POSTPONE_START) {
            alarmScheduler.schedule(startRetryAlarm(taskId, occurrenceKey))
            logger(
                "action retry scheduled action=$action taskId=$taskId occurrence=$occurrenceKey " +
                    "delaySeconds=$RETRY_DELAY_SECONDS"
            )
        }
        return CoordinatorActionResult.FailedAndHidden(message)
    }

    private fun startRetryAlarm(taskId: String, occurrenceKey: String): ScheduledAlarm =
        ScheduledAlarm(
            taskId = taskId,
            occurrenceKey = occurrenceKey,
            kind = AlarmKind.START_BLOCKER,
            triggerAtEpochMillis = Instant.parse(nowProvider()).plusSeconds(RETRY_DELAY_SECONDS).toEpochMilli()
        )

    companion object {
        private const val RETRY_DELAY_SECONDS = 5L * 60L
    }
}
