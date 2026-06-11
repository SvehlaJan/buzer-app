package app.buzer.android.enforcement

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class EnforcementRefreshTimeoutException(timeoutMillis: Long) :
    RuntimeException("snapshot refresh timed out after ${timeoutMillis}ms")

class EnforcementRefreshRunner(
    private val scope: CoroutineScope,
    private val reportFailure: (Throwable) -> Unit,
    private val logger: (String) -> Unit = {},
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) {
    private var refreshJob: Job? = null

    fun refresh(refresh: suspend () -> Unit): Job? {
        if (refreshJob?.isActive == true) {
            logger("refresh_runner skipped reason=already_active")
            return null
        }

        val job = scope.launch {
            try {
                logger("refresh_runner started")
                val completed = withTimeoutOrNull(timeoutMillis) {
                    refresh()
                }
                if (completed == null) {
                    logger("refresh_runner timeout timeoutMillis=$timeoutMillis")
                    reportFailure(EnforcementRefreshTimeoutException(timeoutMillis))
                } else {
                    logger("refresh_runner completed")
                }
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

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    }
}
