package app.buzer.android.ui

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.shared.state.SnapshotViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HomeDoneActionHandler(
    private val refreshSnapshot: suspend () -> SnapshotViewState,
    private val completeTask: suspend (String) -> CoordinatorActionResult,
    private val latestSnapshot: () -> SnapshotViewState?,
    private val applySnapshot: (SnapshotViewState) -> Unit,
    private val setStatusMessage: (String?) -> Unit,
    private val setRefreshInFlight: (Boolean) -> Unit = {},
    private val reportRefreshFailure: (Throwable) -> Unit = {}
) {
    private val mutex = Mutex()

    suspend fun refresh() {
        mutex.withLock {
            setRefreshInFlight(true)
            setStatusMessage(null)
            try {
                applySnapshot(refreshSnapshot())
                setStatusMessage(null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                reportRefreshFailure(error)
            } finally {
                setRefreshInFlight(false)
            }
        }
    }

    suspend fun completeCurrentTask(taskId: String) {
        mutex.withLock {
            when (val result = completeTask(taskId)) {
                CoordinatorActionResult.Success -> {
                    setStatusMessage(null)
                    latestSnapshot()?.let(applySnapshot)
                }

                is CoordinatorActionResult.FailedAndHidden -> {
                    setStatusMessage(result.message)
                }
            }
        }
    }
}
