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

/**
 * Background-safe boot recovery. Runs short work off a [BroadcastReceiver.goAsync] result so
 * alarms are rescheduled after reboot without starting an activity (which Android blocks from
 * the background with BAL_BLOCK).
 *
 * Cached reconciliation is the guaranteed path; the live refresh is best-effort and bounded by
 * [REFRESH_TIMEOUT_MS] so it can never prevent [BroadcastReceiver.PendingResult.finish].
 */
object BootReconcileRunner {
    private const val REFRESH_TIMEOUT_MS = 8_000L

    fun run(
        context: Context,
        pendingResult: BroadcastReceiver.PendingResult,
        work: suspend (Context) -> Unit = ::runProductionWork
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AppLog.info("boot_reconcile received")
                work(context.applicationContext)
                AppLog.info("boot_reconcile complete")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLog.error("boot_reconcile failed ${error.message}", error)
                BlockerErrorReporter(context).report(
                    action = BlockerAction.REFRESH_SNAPSHOT,
                    taskId = null,
                    message = error.message ?: "boot reconcile failed",
                    showNotification = false,
                    throwable = error
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun runProductionWork(context: Context) {
        val coordinator = EnforcementDependencies.coordinator(context)
        val cached = coordinator.reconcileLatestSnapshot()
        AppLog.info("boot_reconcile cached=$cached")
        try {
            withTimeout(REFRESH_TIMEOUT_MS) {
                coordinator.refreshAndReconcile()
            }
            AppLog.info("boot_reconcile refresh=success")
        } catch (timeout: TimeoutCancellationException) {
            AppLog.warn("boot_reconcile refresh=timeout afterMs=$REFRESH_TIMEOUT_MS")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLog.warn("boot_reconcile refresh=failed ${error.message}", error)
        }
    }
}
