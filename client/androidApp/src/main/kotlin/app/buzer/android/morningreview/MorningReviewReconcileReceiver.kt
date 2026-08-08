package app.buzer.android.morningreview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId

/** Re-arms Morning Review's locally-owned alarm after system clock and package changes. */
class MorningReviewReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> MorningReviewReconcileGuard.reconcile(context.applicationContext) {
                val clock = MorningReviewRuntimeClock.snapshot()
                MorningReviewDependencies.scheduler(context.applicationContext)
                    .reconcile(clock.instant, clock.zoneId)
            }
        }
    }
}
