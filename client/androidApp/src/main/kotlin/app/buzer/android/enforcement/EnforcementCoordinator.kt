package app.buzer.android.enforcement

import app.buzer.android.sync.SnapshotStorage
import app.buzer.shared.api.BuzerApiException
import app.buzer.shared.api.TaskBackendClient
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.enforcement.AlarmPlanCalculator
import app.buzer.shared.enforcement.DesiredAlarmKind
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ExtendTaskRequest
import app.buzer.shared.model.FocusedChecklistMutationRequest
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.state.SnapshotDiagnostics
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CoordinatorActionResult {
    data object Success : CoordinatorActionResult
    data class Warning(val code: String, val message: String, val snapshot: TasksSnapshotResponse?) : CoordinatorActionResult
    data class Conflict(val code: String, val message: String, val snapshot: TasksSnapshotResponse) : CoordinatorActionResult
    data class TransportFailure(val isConnected: Boolean, val message: String) : CoordinatorActionResult
    data class LocalReconciliationFailed(val snapshot: TasksSnapshotResponse, val message: String) : CoordinatorActionResult
    data object ChildActionInFlight : CoordinatorActionResult
}

class EnforcementCoordinator(
    private val api: TaskBackendClient,
    private val snapshotStore: SnapshotStorage,
    private val alarmScheduler: AlarmScheduler,
    private val scheduledAlarmRegistry: ScheduledAlarmRegistry =
        ScheduledAlarmRegistry(InMemoryScheduledAlarmRegistryStorage()),
    private val errorReporter: BlockerErrorReporting,
    private val blockerNotificationCleaner: BlockerNotificationCleaner = NoOpBlockerNotificationCleaner,
    private val isConnectedProvider: (() -> Boolean)? = null,
    private val nowProvider: () -> String = { Instant.now().toString() },
    private val logger: (String) -> Unit = {}
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
        val snapshot = api.getSnapshot(forceRefresh = false)
        logger("refresh fetched ${SnapshotDiagnostics.describe(snapshot)}")
        val appliedSnapshot = applyFreshSnapshot(snapshot)
        logger("refresh complete scheduleVersion=${appliedSnapshot.scheduleVersion}")
        return appliedSnapshot
    }

    suspend fun forceSyncAndReconcile(): TasksSnapshotResponse {
        logger("force_sync start")
        val snapshot = api.getSnapshot(forceRefresh = true)
        logger("force_sync fetched ${SnapshotDiagnostics.describe(snapshot)}")
        val appliedSnapshot = applyFreshSnapshot(snapshot)
        logger("force_sync complete scheduleVersion=${appliedSnapshot.scheduleVersion}")
        return appliedSnapshot
    }

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

    /**
     * A root create has no idempotency key, so once `POST /tasks` returns 2xx the result is always
     * [CoordinatorActionResult.Success]: reporting a stale snapshot or a local reconciliation failure
     * would keep Add Task open and a retry would create a duplicate Todoist task. Local failures are
     * still reported, and the next refresh reconciles alarms.
     */
    suspend fun createTask(request: CreateTaskRequest): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.CREATE_TASK,
            taskId = null,
            occurrenceKey = null,
            showErrorNotification = false,
            landedMutationIsSuccess = true
        ) {
            api.createTask(request)
        }

    suspend fun startTask(taskId: String, request: TaskActionRequest): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.START_TASK,
            taskId = taskId,
            occurrenceKey = request.expectedOccurrenceKey
        ) {
            api.startTask(taskId, request)
        }

    suspend fun postponeStart(taskId: String, request: TaskActionRequest): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.POSTPONE_START,
            taskId = taskId,
            occurrenceKey = request.expectedOccurrenceKey
        ) {
            api.postponeStart(taskId, request)
        }

    suspend fun completeTask(taskId: String, request: TaskActionRequest): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.COMPLETE_TASK,
            taskId = taskId,
            occurrenceKey = request.expectedOccurrenceKey
        ) {
            api.completeTask(taskId, request)
        }

    suspend fun extendTask(taskId: String, request: ExtendTaskRequest): CoordinatorActionResult =
        runBackendAction(
            action = BlockerAction.EXTEND_TASK,
            taskId = taskId,
            occurrenceKey = request.expectedOccurrenceKey
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
            occurrenceKey = request.expectedOccurrenceKey
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
        reportInvalidTimes(plan.invalidTimeTaskIds)
        val scheduledAlarms = plan.alarms
            .map(AndroidEnforcementMapper::toScheduledAlarm)
        scheduledAlarmRegistry.reconcile(scheduledAlarms, alarmScheduler)
        logger(
            "reconcile complete scheduled=${plan.alarms.size} " +
                "preStart=${plan.alarms.count { it.kind == DesiredAlarmKind.PRE_START_WARNING }} " +
                "start=${plan.alarms.count { it.kind == DesiredAlarmKind.START_BLOCKER }} " +
                "deadline=${plan.alarms.count { it.kind == DesiredAlarmKind.DEADLINE_BLOCKER }} " +
                "invalidTime=${plan.invalidTimeTaskIds.size}"
        )
    }

    /** A snapshot time without an offset is a backend contract violation; report each task once per process. */
    private fun reportInvalidTimes(taskIds: Set<String>) {
        for (taskId in taskIds) {
            if (!reportedInvalidTimeTaskIds.add(taskId)) continue
            val message = "snapshot task time has no offset; no alarms scheduled for it"
            errorReporter.report(
                action = BlockerAction.REFRESH_SNAPSHOT,
                taskId = taskId,
                message = message,
                showNotification = false,
                throwable = IllegalStateException(message)
            )
        }
    }

    /**
     * Accepts only the backend's mutation authority. In particular, no local checked/completed
     * state is inferred when a request fails, and alarm removal happens only after reconciliation
     * of the returned snapshot.
     */
    private fun applyMutationResponse(
        taskId: String?,
        occurrenceKey: String?,
        response: MutationResponse,
        showErrorNotification: Boolean
    ): CoordinatorActionResult = applyAuthoritativeSnapshot(
        taskId = taskId,
        occurrenceKey = occurrenceKey,
        snapshot = response.snapshot,
        showErrorNotification = showErrorNotification
    ) {
        logger(
            "mutation applied taskId=${taskId.orEmpty()} occurrence=${occurrenceKey.orEmpty()} " +
                "scheduleVersion=${response.snapshot.scheduleVersion}"
        )
        CoordinatorActionResult.Success
    }

    private fun applyConflictSnapshot(
        taskId: String?,
        occurrenceKey: String?,
        snapshot: TasksSnapshotResponse,
        code: String,
        message: String,
        showErrorNotification: Boolean
    ): CoordinatorActionResult = applyAuthoritativeSnapshot(
        taskId = taskId,
        occurrenceKey = occurrenceKey,
        snapshot = snapshot,
        showErrorNotification = showErrorNotification
    ) { appliedSnapshot ->
        CoordinatorActionResult.Conflict(
            code = code,
            message = message,
            snapshot = appliedSnapshot
        )
    }

    /** Serializes local application only; API requests happen before entering this boundary. */
    private fun applyAuthoritativeSnapshot(
        taskId: String?,
        occurrenceKey: String?,
        snapshot: TasksSnapshotResponse,
        showErrorNotification: Boolean = true,
        onStale: (TasksSnapshotResponse) -> CoordinatorActionResult = { cached ->
            CoordinatorActionResult.Conflict(
                code = "stale_snapshot",
                message = "A newer authoritative snapshot is already applied",
                snapshot = cached
            )
        },
        result: (TasksSnapshotResponse) -> CoordinatorActionResult
    ): CoordinatorActionResult = synchronized(snapshotApplicationLock) {
        try {
            val application = applySnapshotGeneration(snapshotStore.load(), snapshot)
            when {
                application.stale -> {
                    logger(
                        "authoritative snapshot ignored_stale taskId=${taskId.orEmpty()} occurrence=${occurrenceKey.orEmpty()} " +
                            "incomingGeneration=${snapshot.snapshotGeneration} " +
                            "cachedGeneration=${application.snapshot.snapshotGeneration}"
                    )
                    onStale(application.snapshot)
                }

                application.applied -> {
                    snapshotStore.save(application.snapshot)
                    reconcile(application.snapshot)
                    if (taskId != null && !occurrenceKey.isNullOrEmpty()) {
                        blockerNotificationCleaner.cancelForTask(taskId, occurrenceKey)
                    }
                    result(application.snapshot)
                }

                else -> {
                    // Equal generation is an idempotent replay of the same complete authority.
                    // It must not write/reconcile again, but the completed action may clear its notification.
                    if (taskId != null && !occurrenceKey.isNullOrEmpty()) {
                        blockerNotificationCleaner.cancelForTask(taskId, occurrenceKey)
                    }
                    result(application.snapshot)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message ?: error::class.simpleName ?: "local reconciliation failed"
            logger(
                "authoritative snapshot local_failure taskId=${taskId.orEmpty()} occurrence=${occurrenceKey.orEmpty()} " +
                    "scheduleVersion=${snapshot.scheduleVersion} message=$message"
            )
            errorReporter.report(
                action = BlockerAction.REFRESH_SNAPSHOT,
                taskId = taskId,
                message = message,
                showNotification = showErrorNotification,
                throwable = error
            )
            CoordinatorActionResult.LocalReconciliationFailed(snapshot, message)
        }
    }

    private fun reconcileWarningSnapshotOrNull(
        taskId: String?,
        occurrenceKey: String?,
        snapshot: TasksSnapshotResponse?,
        code: String,
        message: String,
        showErrorNotification: Boolean
    ): CoordinatorActionResult {
        if (snapshot == null) {
            return CoordinatorActionResult.Warning(code = code, message = message, snapshot = null)
        }
        // Apply this operation's own authoritative snapshot exactly once and use whichever
        // snapshot local reconciliation actually settled on (this one, an already-cached newer
        // one, or LocalReconciliationFailed on local store/alarm failure).
        return applyAuthoritativeSnapshot(
            taskId = taskId,
            occurrenceKey = occurrenceKey,
            snapshot = snapshot,
            showErrorNotification = showErrorNotification,
            onStale = { cachedSnapshot ->
                CoordinatorActionResult.Warning(code = code, message = message, snapshot = cachedSnapshot)
            }
        ) { appliedSnapshot ->
            CoordinatorActionResult.Warning(code = code, message = message, snapshot = appliedSnapshot)
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
        taskId: String?,
        occurrenceKey: String?,
        showErrorNotification: Boolean = true,
        landedMutationIsSuccess: Boolean = false,
        call: suspend () -> MutationResponse
    ): CoordinatorActionResult {
        return try {
            logger("action start action=$action taskId=${taskId.orEmpty()} occurrence=${occurrenceKey.orEmpty()}")
            val response = call()
            val applied = applyMutationResponse(taskId, occurrenceKey, response, showErrorNotification)
            if (landedMutationIsSuccess && applied != CoordinatorActionResult.Success) {
                logger("action landed action=$action localResult=${applied::class.simpleName} reported=Success")
                CoordinatorActionResult.Success
            } else {
                applied
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: BuzerApiException) {
            val snapshot = error.snapshot
            when {
                error.status == 409 && snapshot != null -> {
                    applyConflictSnapshot(
                        taskId = taskId,
                        occurrenceKey = occurrenceKey,
                        snapshot = snapshot,
                        code = error.code ?: "conflict",
                        message = error.message ?: "Task changed on another device",
                        showErrorNotification = showErrorNotification
                    )
                }

                FailureClassifier.isExpectedOperationalApiError(error) -> {
                    val code = error.code ?: "operational_error"
                    val message = error.message ?: "Operational error"
                    val attributes = buildMap {
                        put("action", action.name)
                        taskId?.let { put("task_id", it) }
                        put("code", code)
                    }
                    AppLog.warn("enforcement_action_warning", error, attributes)
                    logger("action warning action=$action taskId=${taskId.orEmpty()} occurrence=${occurrenceKey.orEmpty()} code=$code message=$message")
                    reconcileWarningSnapshotOrNull(
                        taskId = taskId,
                        occurrenceKey = occurrenceKey,
                        snapshot = snapshot,
                        code = code,
                        message = message,
                        showErrorNotification = showErrorNotification
                    )
                }

                else -> {
                    val code = error.code ?: "backend_error"
                    val message = error.message ?: "Backend error"
                    errorReporter.report(action, taskId, message, showNotification = showErrorNotification, throwable = error)
                    logger("action failed action=$action taskId=${taskId.orEmpty()} occurrence=${occurrenceKey.orEmpty()} message=$message")
                    reconcileWarningSnapshotOrNull(
                        taskId = taskId,
                        occurrenceKey = occurrenceKey,
                        snapshot = snapshot,
                        code = code,
                        message = message,
                        showErrorNotification = showErrorNotification
                    )
                }
            }
        } catch (error: Exception) {
            if (FailureClassifier.isTransportFailure(error)) {
                val isConnected = isConnectedProvider?.invoke() ?: false
                val message = error.message ?: "Network failure"
                logger("action transport_failure action=$action taskId=${taskId.orEmpty()} occurrence=${occurrenceKey.orEmpty()} isConnected=$isConnected message=$message")
                CoordinatorActionResult.TransportFailure(isConnected = isConnected, message = message)
            } else {
                val message = error.message ?: error::class.simpleName ?: "unknown error"
                errorReporter.report(action, taskId, message, showNotification = showErrorNotification, throwable = error)
                logger("action failed action=$action taskId=${taskId.orEmpty()} occurrence=${occurrenceKey.orEmpty()} message=$message")
                val cachedSnapshot = loadLatestSnapshot()
                CoordinatorActionResult.Warning(code = "local_failure", message = message, snapshot = cachedSnapshot)
            }
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
            runBackendAction(
                action = action,
                taskId = taskId,
                occurrenceKey = request.expectedOccurrenceKey,
                call = call
            )
        } finally {
            childActionMutex.withLock { inFlightChildren.remove(childIdentity) }
        }
    }

    companion object {
        /**
         * Process-wide on purpose: every entry point (activities, services, boot) builds its own
         * coordinator, so an instance field would re-report a persistently bad task per lifecycle.
         */
        private val reportedInvalidTimeTaskIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

        internal fun resetReportedInvalidTimesForTests() {
            reportedInvalidTimeTaskIds.clear()
        }
    }
}
