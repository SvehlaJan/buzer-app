package app.buzer.android.morningreview

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.shared.state.SnapshotReducer
import app.buzer.shared.state.SnapshotViewState

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
    suspend fun forceSync(operation: suspend () -> SnapshotViewState): Exception? {
        if (!beginForcedSync()) return null
        return try {
            forceSyncSucceeded(operation())
            null
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            forceSyncFailed(error.message ?: "snapshot refresh failed")
            error
        }
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
        execute: suspend () -> CoordinatorActionResult = { CoordinatorActionResult.FailedAndHidden("Retry unavailable") }
    ) {
        when (result) {
            CoordinatorActionResult.Success -> {
                reloadCached()
                retry = null
                finishAction()
            }
            is CoordinatorActionResult.Pending -> applyAuthority(SnapshotReducer.reduce(result.response.snapshot))
            is CoordinatorActionResult.Stale -> applyAuthority(SnapshotReducer.reduce(result.snapshot))
            is CoordinatorActionResult.LocalReconciliationFailed -> applyAuthority(SnapshotReducer.reduce(result.snapshot))
            is CoordinatorActionResult.RetryAfter -> {
                retry = {
                    if (beginAction()) handleOverdueResult(result.retry(), reloadCached, execute)
                }
                update(state.actionFailed(result.message))
            }
            is CoordinatorActionResult.FailedAndHidden -> retryOverdue(result.message, reloadCached, execute)
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
            is CoordinatorActionResult.Pending -> cache(SnapshotReducer.reduce(result.response.snapshot))
            is CoordinatorActionResult.Stale -> cache(SnapshotReducer.reduce(result.snapshot))
            is CoordinatorActionResult.LocalReconciliationFailed -> cache(SnapshotReducer.reduce(result.snapshot))
            else -> Unit
        }
    }

    private fun applyAuthority(snapshot: SnapshotViewState) {
        cache(snapshot)
        retry = null
        finishAction()
    }

    private fun update(next: MorningReviewSessionState) {
        state = next
        publish(next)
    }
}
