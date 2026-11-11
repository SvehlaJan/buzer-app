package app.buzer.android.blockers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporting
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.ui.screens.BlockerActionState
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The snapshot operations a full-screen blocker needs from [EnforcementCoordinator]. */
internal interface BlockerSnapshotSource {
    fun loadLatestSnapshot(): TasksSnapshotResponse?
    suspend fun refreshAndReconcile(): TasksSnapshotResponse
    suspend fun forceSyncAndReconcile(): TasksSnapshotResponse
}

internal fun EnforcementCoordinator.asBlockerSnapshotSource(): BlockerSnapshotSource =
    object : BlockerSnapshotSource {
        override fun loadLatestSnapshot(): TasksSnapshotResponse? =
            this@asBlockerSnapshotSource.loadLatestSnapshot()

        override suspend fun refreshAndReconcile(): TasksSnapshotResponse =
            this@asBlockerSnapshotSource.refreshAndReconcile()

        override suspend fun forceSyncAndReconcile(): TasksSnapshotResponse =
            this@asBlockerSnapshotSource.forceSyncAndReconcile()
    }

/**
 * Host state and lifecycle shared by [StartBlockerActivity] and [DeadlineBlockerActivity]: which
 * occurrence is shown, the latest snapshot of it, the sticky warning banner, and the single
 * in-flight blocker mutation.
 *
 * A warning handed off from the native overlay survives lifecycle refreshes while the task stays
 * unresolved. Only an explicit user refresh that succeeds clears it.
 */
internal class BlockerHostController(
    private val kind: AlarmKind,
    private val source: BlockerSnapshotSource,
    private val errorReporter: BlockerErrorReporting,
    private val scope: CoroutineScope,
    private val onFinish: () -> Unit,
    private val refreshTimeoutMillis: Long = DEFAULT_REFRESH_TIMEOUT_MILLIS
) {
    private val logTag = if (kind == AlarmKind.DEADLINE_BLOCKER) "deadline_blocker" else "start_blocker"
    private var actionInFlight = false
    private var finished = false
    private var refreshJob: Job? = null

    var taskId: String? by mutableStateOf(null)
        private set
    var occurrenceKey: String? by mutableStateOf(null)
        private set
    var checking: Boolean by mutableStateOf(true)
        private set
    var warningMessage: String? by mutableStateOf(null)
    var actionState: BlockerActionState by mutableStateOf(BlockerActionState.Idle)
        private set

    /** The root as last seen in any snapshot, used for the compact blocker presentation. */
    var latestTask: TaskSnapshotItem? by mutableStateOf(null)
        private set

    /** The unresolved root for the bound occurrence, required by editors that send revisions. */
    var currentTask: TaskSnapshotItem? by mutableStateOf(null)
        private set

    /** True once the shown snapshot came from a backend refresh rather than the local cache. */
    var snapshotConfirmed: Boolean by mutableStateOf(false)
        private set

    fun bindTarget(taskId: String, occurrenceKey: String, initialWarningMessage: String?) {
        // A refresh for the previous target must not show its task or finish the new blocker.
        refreshJob?.cancel()
        refreshJob = null
        this.taskId = taskId
        this.occurrenceKey = occurrenceKey
        warningMessage = initialWarningMessage
        snapshotConfirmed = false
        latestTask = null
        currentTask = null
    }

    fun refresh(explicitUserRefresh: Boolean): Job {
        refreshJob?.cancel()
        return scope.launch {
            val taskId = taskId ?: return@launch
            val occurrenceKey = occurrenceKey ?: return@launch
            if (explicitUserRefresh) {
                forceRefresh(taskId, occurrenceKey)
            } else {
                lifecycleRefresh(taskId, occurrenceKey)
            }
        }.also { refreshJob = it }
    }

    /** Forces a Todoist sync for the reschedule editor's warning banner. Failures propagate to the editor. */
    suspend fun forceSyncForEditor() {
        val taskId = taskId ?: return
        val occurrenceKey = occurrenceKey ?: return
        applySnapshot(source.forceSyncAndReconcile(), taskId, occurrenceKey, confirmed = true)
    }

    fun runMutation(
        loadingLabel: String,
        closeOnSuccess: Boolean = true,
        block: suspend () -> CoordinatorActionResult
    ): Job? {
        if (actionInFlight) {
            AppLog.debug("$logTag action ignored reason=in_flight occurrence=$occurrenceKey")
            return null
        }
        actionInFlight = true
        actionState = BlockerActionState.Loading(loadingLabel)
        return scope.launch {
            try {
                when (val result = block()) {
                    CoordinatorActionResult.Success ->
                        if (closeOnSuccess) finish() else reloadCachedSnapshot()
                    is CoordinatorActionResult.Warning -> {
                        reloadCachedSnapshot()
                        warningMessage = result.message
                    }
                    is CoordinatorActionResult.Conflict -> {
                        reloadCachedSnapshot()
                        warningMessage = result.message
                    }
                    is CoordinatorActionResult.LocalReconciliationFailed -> {
                        reloadCachedSnapshot()
                        warningMessage = result.message
                    }
                    is CoordinatorActionResult.TransportFailure -> warningMessage = result.message
                    CoordinatorActionResult.ChildActionInFlight -> warningMessage = childActionInFlightMessage()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                warningMessage = error.message ?: "Action failed"
            } finally {
                actionInFlight = false
                actionState = BlockerActionState.Idle
            }
        }
    }

    private suspend fun lifecycleRefresh(taskId: String, occurrenceKey: String) {
        AppLog.debug("$logTag prepare taskId=$taskId occurrence=$occurrenceKey")
        checking = true
        try {
            source.loadLatestSnapshot()?.let { applySnapshot(it, taskId, occurrenceKey, confirmed = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.error("$logTag local snapshot load failed taskId=$taskId occurrence=$occurrenceKey", error)
            errorReporter.report(
                action = BlockerAction.REFRESH_SNAPSHOT,
                taskId = taskId,
                message = error.message ?: "local snapshot load failed",
                throwable = error
            )
        }

        try {
            val refreshed = withTimeoutOrNull(refreshTimeoutMillis) { source.refreshAndReconcile() }
            if (!isBound(taskId, occurrenceKey)) return
            if (refreshed == null) {
                snapshotConfirmed = false
                AppLog.warn("$logTag refresh_timeout taskId=$taskId occurrence=$occurrenceKey")
                showStaleWarning()
                errorReporter.report(
                    action = BlockerAction.REFRESH_SNAPSHOT,
                    taskId = taskId,
                    message = "snapshot refresh timed out"
                )
            } else {
                applySnapshot(refreshed, taskId, occurrenceKey, confirmed = true)
                if (isResolved(refreshed, taskId, occurrenceKey)) {
                    AppLog.debug("$logTag hidden reason=stale_or_resolved taskId=$taskId occurrence=$occurrenceKey")
                    finish()
                    return
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.error("$logTag refresh failed taskId=$taskId occurrence=$occurrenceKey", error)
            if (!isBound(taskId, occurrenceKey)) return
            snapshotConfirmed = false
            showStaleWarning()
            errorReporter.report(
                action = BlockerAction.REFRESH_SNAPSHOT,
                taskId = taskId,
                message = error.message ?: "snapshot refresh failed",
                throwable = error
            )
        }
        checking = false
    }

    private suspend fun forceRefresh(taskId: String, occurrenceKey: String) {
        checking = true
        try {
            val snapshot = source.forceSyncAndReconcile()
            if (!isBound(taskId, occurrenceKey)) return
            applySnapshot(snapshot, taskId, occurrenceKey, confirmed = true)
            if (isResolved(snapshot, taskId, occurrenceKey)) {
                finish()
                return
            }
            warningMessage = null
            checking = false
        } catch (error: CancellationException) {
            // A newer refresh or rebind owns `checking` now.
            throw error
        } catch (error: Exception) {
            AppLog.error("$logTag force refresh failed taskId=$taskId occurrence=$occurrenceKey", error)
            if (!isBound(taskId, occurrenceKey)) return
            checking = false
            warningMessage = "Refresh failed"
            // The banner already tells the user; the report only feeds observability.
            errorReporter.report(
                action = BlockerAction.REFRESH_SNAPSHOT,
                taskId = taskId,
                message = error.message ?: "snapshot force refresh failed",
                showNotification = false,
                throwable = error
            )
        }
    }

    private fun reloadCachedSnapshot() {
        val taskId = taskId ?: return
        val occurrenceKey = occurrenceKey ?: return
        source.loadLatestSnapshot()?.let { applySnapshot(it, taskId, occurrenceKey, confirmed = null) }
    }

    private fun applySnapshot(
        snapshot: TasksSnapshotResponse,
        taskId: String,
        occurrenceKey: String,
        confirmed: Boolean?
    ) {
        latestTask = snapshot.tasks.firstOrNull { it.id == taskId }
        currentTask = BlockerTaskSnapshotGate.currentTask(snapshot, taskId, occurrenceKey)
        confirmed?.let { snapshotConfirmed = it }
    }

    private fun isBound(taskId: String, occurrenceKey: String): Boolean =
        this.taskId == taskId && this.occurrenceKey == occurrenceKey

    private fun isResolved(snapshot: TasksSnapshotResponse, taskId: String, occurrenceKey: String): Boolean =
        BlockerTaskSnapshotGate.shouldHideAfterRefresh(snapshot, taskId, occurrenceKey, kind)

    private fun showStaleWarning() {
        // A specific handoff warning (for example from the overlay) is more useful than the generic one.
        if (warningMessage == null) warningMessage = STALE_BLOCKER_WARNING
    }

    private fun childActionInFlightMessage(): String =
        if (kind == AlarmKind.DEADLINE_BLOCKER) "Checklist update is already in progress" else "Action already in progress"

    private fun finish() {
        if (finished) return
        finished = true
        onFinish()
    }

    companion object {
        const val STALE_BLOCKER_WARNING = "Could not refresh task state. This blocker may be stale."
        private const val DEFAULT_REFRESH_TIMEOUT_MILLIS = 5_000L
    }
}
