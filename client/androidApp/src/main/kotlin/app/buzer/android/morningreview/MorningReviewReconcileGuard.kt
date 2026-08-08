package app.buzer.android.morningreview

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import app.buzer.shared.diagnostics.AppLog

internal object MorningReviewReconcileGuard {
    fun reconcile(context: Context, action: () -> Unit) {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        reconcile(permitted, action) { AppLog.warn("morning_review reconcile degraded $it") }
    }

    internal fun reconcile(permitted: Boolean, action: () -> Unit, report: (String) -> Unit) {
        if (!permitted) {
            report("exact_alarm_permission_denied")
            return
        }
        try {
            action()
        } catch (_: SecurityException) {
            report("exact_alarm_permission_race")
        }
    }
}
