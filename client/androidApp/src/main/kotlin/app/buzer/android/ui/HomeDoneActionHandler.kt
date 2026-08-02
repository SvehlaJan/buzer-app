package app.buzer.android.ui

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.state.SnapshotReducer
import app.buzer.shared.state.SnapshotViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HomeDoneActionHandler(
    private val refreshSnapshot: suspend (forceTodoist: Boolean) -> SnapshotViewState,
    private val completeTask: suspend (String, TaskActionRequest) -> CoordinatorActionResult,
    private val latestSnapshot: () -> SnapshotViewState?,
    private val applySnapshot: (SnapshotViewState) -> Unit,
    private val setStatusMessage: (String?) -> Unit = {},
    private val setRefreshInFlight: (Boolean) -> Unit = {},
    private val clearRefreshFailure: () -> Unit = {},
    private val reportRefreshFailure: (Throwable) -> Unit = {}
) {
    private val mutex = Mutex()
    private var retryCurrentAction: (suspend () -> CoordinatorActionResult)? = null

    suspend fun refresh(forceTodoist: Boolean = false) {
        mutex.withLock {
            setRefreshInFlight(true)
            clearRefreshFailure()
            try {
                applySnapshot(refreshSnapshot(forceTodoist))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportRefreshFailure(error)
            } finally {
                setRefreshInFlight(false)
            }
        }
    }

    /** The request is created by the UI once and retained by the coordinator's retry closure. */
    suspend fun completeTask(taskId: String, request: TaskActionRequest) {
        mutex.withLock {
            handleCompletionResult(this.completeTask.invoke(taskId, request))
        }
    }

    suspend fun retryCompletion() {
        mutex.withLock {
            val retry = retryCurrentAction ?: return
            handleCompletionResult(retry())
        }
    }

    private fun handleCompletionResult(result: CoordinatorActionResult) {
        when (result) {
            CoordinatorActionResult.Success,
            is CoordinatorActionResult.Pending,
            is CoordinatorActionResult.Stale -> {
                retryCurrentAction = null
                authoritativeViewState(result)?.let(applySnapshot) ?: latestSnapshot()?.let(applySnapshot)
                setStatusMessage(
                    when (result) {
                        is CoordinatorActionResult.Pending -> "Saved; waiting for Todoist confirmation"
                        is CoordinatorActionResult.Stale -> result.message
                        else -> null
                    }
                )
            }

            is CoordinatorActionResult.RetryAfter -> {
                retryCurrentAction = result.retry
                setStatusMessage("Retry in ${result.retryAfterSeconds} seconds")
            }

            CoordinatorActionResult.ChildActionInFlight -> {
                setStatusMessage("Checklist update is already in progress")
            }

            is CoordinatorActionResult.LocalReconciliationFailed -> {
                retryCurrentAction = null
                applySnapshot(SnapshotReducer.reduce(result.snapshot))
                setStatusMessage("Action accepted; local alarm reconciliation failed: ${result.message}")
            }

            is CoordinatorActionResult.FailedAndHidden -> {
                retryCurrentAction = null
                setStatusMessage(result.message)
            }
        }
    }

    private fun authoritativeViewState(result: CoordinatorActionResult): SnapshotViewState? =
        when (result) {
            is CoordinatorActionResult.Pending -> SnapshotReducer.reduce(result.response.snapshot)
            is CoordinatorActionResult.Stale -> SnapshotReducer.reduce(result.snapshot)
            else -> null
        }
}
