package app.buzer.android.enforcement

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class EnforcementRefreshRunner(
    private val scope: CoroutineScope,
    private val reportFailure: (Throwable) -> Unit
) {
    private var refreshJob: Job? = null

    fun refresh(refresh: suspend () -> Unit): Job? {
        if (refreshJob?.isActive == true) return null

        val job = scope.launch {
            try {
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure(error)
            }
        }
        refreshJob = job
        return job
    }
}
