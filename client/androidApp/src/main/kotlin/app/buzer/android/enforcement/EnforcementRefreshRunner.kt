package app.buzer.android.enforcement

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class EnforcementRefreshRunner(
    private val scope: CoroutineScope,
    private val reportFailure: (Throwable) -> Unit,
    private val logger: (String) -> Unit = {}
) {
    private var refreshJob: Job? = null

    fun refresh(
        onStarted: () -> Unit = {},
        refresh: suspend () -> Unit
    ): Job? {
        if (refreshJob?.isActive == true) {
            logger("refresh_runner skipped reason=already_active")
            return null
        }

        onStarted()
        val job = scope.launch {
            try {
                logger("refresh_runner started")
                refresh()
                logger("refresh_runner completed")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger("refresh_runner failed message=${error.message ?: error::class.simpleName}")
                reportFailure(error)
            }
        }
        refreshJob = job
        return job
    }
}
