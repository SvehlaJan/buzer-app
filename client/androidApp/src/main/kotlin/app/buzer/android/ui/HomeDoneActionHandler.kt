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

    /** The request is created by the UI once. */
    suspend fun completeTask(taskId: String, request: TaskActionRequest) {
        mutex.withLock {
            val result = completeTask.invoke(taskId, request)
            if (result is CoordinatorActionResult.TransportFailure) {
                retryCurrentAction = { completeTask.invoke(taskId, request) }
            }
            handleCompletionResult(result)
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
            CoordinatorActionResult.Success -> {
                retryCurrentAction = null
                latestSnapshot()?.let(applySnapshot)
                setStatusMessage(null)
            }

            is CoordinatorActionResult.Warning -> {
                retryCurrentAction = null
                result.snapshot?.let { applySnapshot(SnapshotReducer.reduce(it)) } ?: latestSnapshot()?.let(applySnapshot)
                setStatusMessage(result.message)
            }

            is CoordinatorActionResult.Conflict -> {
                retryCurrentAction = null
                applySnapshot(SnapshotReducer.reduce(result.snapshot))
                setStatusMessage(result.message)
            }

            is CoordinatorActionResult.TransportFailure -> {
                setStatusMessage(result.message)
            }

            is CoordinatorActionResult.LocalReconciliationFailed -> {
                retryCurrentAction = null
                applySnapshot(SnapshotReducer.reduce(result.snapshot))
                setStatusMessage("Action accepted; local alarm reconciliation failed: ${result.message}")
            }

            CoordinatorActionResult.ChildActionInFlight -> {
                setStatusMessage("Checklist update is already in progress")
            }
        }
    }
}
