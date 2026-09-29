package app.buzer.android.enforcement

import android.content.BroadcastReceiver
import android.content.Context
import app.buzer.shared.diagnostics.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Background-safe boot recovery. Runs short work off a [BroadcastReceiver.goAsync] result so
 * alarms are rescheduled after reboot without starting an activity (which Android blocks from
 * the background with BAL_BLOCK).
 *
 * Only the cached reconcile runs here (decision 2026-09-29-reminder-reliability): the network
 * refresh outlived the 60 s boot-broadcast window (BUZER-ANDROID-D). The next app open
 * refreshes as usual.
 */
object BootReconcileRunner {
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

    @Suppress("RedundantSuspendModifier")
    private suspend fun runProductionWork(context: Context) {
        val cached = EnforcementDependencies.coordinator(context).reconcileLatestSnapshot()
        AppLog.info("boot_reconcile cached=$cached")
    }
}
