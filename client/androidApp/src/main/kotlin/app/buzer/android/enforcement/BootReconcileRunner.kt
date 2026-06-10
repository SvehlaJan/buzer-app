package app.buzer.android.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import app.buzer.shared.diagnostics.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

object BootReconcileRunner {
    private const val REFRESH_TIMEOUT_MS = 8_000L

    fun run(context: Context, pendingResult: BroadcastReceiver.PendingResult) {
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val coordinator = EnforcementDependencies.coordinator(appContext)
                runWork(
                    reconcileLatestSnapshot = coordinator::reconcileLatestSnapshot,
                    refreshAndReconcile = {
                        coordinator.refreshAndReconcile()
                    },
                    logger = AppLog::debug
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLog.error("boot_reconcile failed message=${error.message ?: error::class.simpleName}", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    suspend fun runWork(
        reconcileLatestSnapshot: () -> Boolean,
        refreshAndReconcile: suspend () -> Unit,
        refreshTimeoutMillis: Long = REFRESH_TIMEOUT_MS,
        logger: (String) -> Unit = AppLog::debug
    ) {
        logger("boot_reconcile received")
        val cached = try {
            reconcileLatestSnapshot()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger("boot_reconcile cached=failed message=${error.message ?: error::class.simpleName}")
            false
        }
        logger("boot_reconcile cached=$cached")

        try {
            withTimeout(refreshTimeoutMillis) {
                refreshAndReconcile()
            }
            logger("boot_reconcile refresh=success")
        } catch (_: TimeoutCancellationException) {
            logger("boot_reconcile refresh=timeout")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger("boot_reconcile refresh=failed message=${error.message ?: error::class.simpleName}")
        }

        logger("boot_reconcile complete")
    }
}
