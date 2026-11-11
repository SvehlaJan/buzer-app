package app.buzer.android.morningreview

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.shared.state.SnapshotReducer
import app.buzer.shared.state.SnapshotViewState
import kotlinx.coroutines.delay

/** Small activity-owned state machine: cache is always retained across failed forced sync. */
data class MorningReviewSessionState(
    val snapshot: SnapshotViewState? = null,
    val loading: Boolean = true,
    val refreshInFlight: Boolean = false,
    val refreshError: String? = null,
    val actionInFlight: Boolean = false,
    val actionError: String? = null
) {
    fun cache(snapshot: SnapshotViewState?) = copy(snapshot = snapshot, loading = false)
    fun beginForcedSync() = copy(refreshInFlight = true, refreshError = null)
    fun forceSyncSucceeded(snapshot: SnapshotViewState) =
        copy(snapshot = snapshot, loading = false, refreshInFlight = false, refreshError = null)
    fun forceSyncFailed(message: String) = copy(loading = false, refreshInFlight = false, refreshError = message)
    fun beginAction() = if (actionInFlight) null else copy(actionInFlight = true, actionError = null)
    fun finishAction() = copy(actionInFlight = false)
    fun actionFailed(message: String) = copy(actionInFlight = false, actionError = message)
}

/** Holds retry closures while exposing every state transition to the Compose host. */
class MorningReviewSessionController(
    private val publish: (MorningReviewSessionState) -> Unit,
    initial: MorningReviewSessionState = MorningReviewSessionState()
) {
    var state: MorningReviewSessionState = initial
        private set
    private var retry: (suspend () -> Unit)? = null
    val hasRetryAction get() = retry != null

    fun cache(snapshot: SnapshotViewState?) = update(state.cache(snapshot))
    fun beginForcedSync(): Boolean = if (state.refreshInFlight || state.actionInFlight) false else {
        update(state.beginForcedSync())
        true
    }
    fun forceSyncSucceeded(snapshot: SnapshotViewState) = update(state.forceSyncSucceeded(snapshot))
    fun forceSyncFailed(message: String) = update(state.forceSyncFailed(message))
    /**
     * Forced sync that absorbs one transient failure: Todoist's 5xx spike at the top of the hour
     * usually clears within seconds (decision 2026-09-29). Only a second failure is surfaced.
     */
    suspend fun forceSyncWithRetry(
        retryDelayMillis: Long,
        operation: suspend () -> SnapshotViewState
    ): Exception? {
        if (!beginForcedSync()) return null
        attemptForcedSync(operation) ?: return null
        delay(retryDelayMillis)
        val error = attemptForcedSync(operation) ?: return null
        forceSyncFailed(error.message ?: "snapshot refresh failed")
        return error
    }

    private suspend fun attemptForcedSync(operation: suspend () -> SnapshotViewState): Exception? =
        try {
            forceSyncSucceeded(operation())
            null
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            error
        }
    fun beginAction(): Boolean = state.beginAction()?.let { update(it); true } ?: false
    fun finishAction() = update(state.finishAction())
    fun localActionFailed(message: String, retryAction: () -> Unit) {
        retry = { retryAction() }
        update(state.actionFailed(message))
    }

    suspend fun retryAction() = retry?.invoke()

    suspend fun runTerminalAction(
        result: MorningReviewResult,
        operation: () -> Unit,
        onFinish: (MorningReviewResult) -> Unit,
        retryAction: () -> Unit
    ): Exception? {
        if (!beginAction()) return null
        return try {
            operation()
            finishAction()
            onFinish(result)
            null
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            localActionFailed(error.message ?: "action failed", retryAction)
            error
        }
    }

    suspend fun handleOverdueResult(
        result: CoordinatorActionResult,
        reloadCached: () -> Unit,
        execute: suspend () -> CoordinatorActionResult = { CoordinatorActionResult.Warning("retry_unavailable", "Retry unavailable", null) }
    ) {
        when (result) {
            CoordinatorActionResult.Success -> {
                reloadCached()
                retry = null
                update(state.copy(actionInFlight = false, actionError = null))
            }
            is CoordinatorActionResult.Warning -> applyFailedAuthority(
                snapshot = result.snapshot?.let(SnapshotReducer::reduce),
                message = result.message
            )
            is CoordinatorActionResult.Conflict -> applyFailedAuthority(
                snapshot = SnapshotReducer.reduce(result.snapshot),
                message = result.message
            )
            is CoordinatorActionResult.LocalReconciliationFailed -> applyFailedAuthority(
                snapshot = SnapshotReducer.reduce(result.snapshot),
                message = result.message
            )
            is CoordinatorActionResult.TransportFailure -> retryOverdue(result.message, reloadCached, execute)
            CoordinatorActionResult.ChildActionInFlight -> retryOverdue("Another action is in progress", reloadCached, execute)
        }
    }

    fun retryOverdue(message: String, reloadCached: () -> Unit, execute: suspend () -> CoordinatorActionResult) {
        retry = {
            if (beginAction()) {
                try {
                    handleOverdueResult(execute(), reloadCached, execute)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    retryOverdue(error.message ?: "Task completion failed", reloadCached, execute)
                }
            }
        }
        update(state.actionFailed(message))
    }

    fun handleRepairResult(result: CoordinatorActionResult, reloadCached: () -> Unit) {
        when (result) {
            CoordinatorActionResult.Success -> reloadCached()
            is CoordinatorActionResult.Warning -> result.snapshot?.let { cache(SnapshotReducer.reduce(it)) }
            is CoordinatorActionResult.Conflict -> cache(SnapshotReducer.reduce(result.snapshot))
            is CoordinatorActionResult.LocalReconciliationFailed -> cache(SnapshotReducer.reduce(result.snapshot))
            else -> Unit
        }
    }

    private fun applyFailedAuthority(snapshot: SnapshotViewState?, message: String) {
        retry = null
        val next = snapshot?.let(state::cache) ?: state
        update(next.actionFailed(message))
    }

    private fun update(next: MorningReviewSessionState) {
        state = next
        publish(next)
    }
}
