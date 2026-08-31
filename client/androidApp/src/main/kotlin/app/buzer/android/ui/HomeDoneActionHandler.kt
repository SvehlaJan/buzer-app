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
    private val setInteractionInFlight: (Boolean) -> Unit = {},
    private val clearRefreshFailure: () -> Unit = {},
    private val reportRefreshFailure: (Throwable) -> Unit = {}
) {
    private val mutex = Mutex()

    suspend fun refresh(forceTodoist: Boolean = false) {
        mutex.withLock {
            setRefreshInFlight(true)
            setInteractionInFlight(true)
            clearRefreshFailure()
            try {
                applySnapshot(refreshSnapshot(forceTodoist))
                setStatusMessage(null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportRefreshFailure(error)
                if (forceTodoist) {
                    setStatusMessage(error.message ?: "Refresh failed")
                }
            } finally {
                setRefreshInFlight(false)
                setInteractionInFlight(false)
            }
        }
    }

    /** The request is created by the UI once. Screen locks during execution and unlocks immediately on completion. */
    suspend fun completeTask(taskId: String, request: TaskActionRequest) {
        mutex.withLock {
            setInteractionInFlight(true)
            try {
                val result = completeTask.invoke(taskId, request)
                handleCompletionResult(result)
            } finally {
                setInteractionInFlight(false)
            }
        }
    }

    private fun handleCompletionResult(result: CoordinatorActionResult) {
        when (result) {
            CoordinatorActionResult.Success -> {
                latestSnapshot()?.let(applySnapshot)
                setStatusMessage(null)
            }

            is CoordinatorActionResult.Warning -> {
                result.snapshot?.let { applySnapshot(SnapshotReducer.reduce(it)) } ?: latestSnapshot()?.let(applySnapshot)
                setStatusMessage(result.message)
            }

            is CoordinatorActionResult.Conflict -> {
                applySnapshot(SnapshotReducer.reduce(result.snapshot))
                setStatusMessage(result.message)
            }

            is CoordinatorActionResult.TransportFailure -> {
                setStatusMessage(result.message)
            }

            is CoordinatorActionResult.LocalReconciliationFailed -> {
                applySnapshot(SnapshotReducer.reduce(result.snapshot))
                setStatusMessage("Action accepted; local alarm reconciliation failed: ${result.message}")
            }

            CoordinatorActionResult.ChildActionInFlight -> {
                setStatusMessage("Checklist update is already in progress")
            }
        }
    }
}
