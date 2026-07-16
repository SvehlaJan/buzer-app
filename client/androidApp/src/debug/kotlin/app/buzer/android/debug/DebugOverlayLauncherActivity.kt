package app.buzer.android.debug

import android.app.Activity
import android.os.Bundle
import app.buzer.android.enforcement.overlay.OverlayBlockerRequest
import app.buzer.android.enforcement.overlay.OverlayBlockerServiceStarter
import app.buzer.shared.diagnostics.AppLog

/**
 * Debug-only: launch a sample blocker overlay from ADB without waiting for a real alarm.
 *
 *   adb shell am start -n app.buzer.android/.debug.DebugOverlayLauncherActivity \
 *     --es kind START_BLOCKER --es task_id debug-task --es occurrence_key debug-occurrence
 *
 * `kind` is an [app.buzer.android.enforcement.AlarmKind] name (START_BLOCKER, DEADLINE_BLOCKER,
 * MORNING_REVIEW). Requires the "Display over other apps" permission to be granted.
 */
class DebugOverlayLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: DEFAULT_TASK_ID
        val occurrenceKey = intent.getStringExtra(EXTRA_OCCURRENCE_KEY) ?: DEFAULT_OCCURRENCE_KEY
        val request = OverlayBlockerRequest.fromExtras(taskId, occurrenceKey, kind)
        if (request == null) {
            AppLog.warn("debug_overlay invalid kind=$kind taskId=$taskId occurrence=$occurrenceKey")
        } else {
            val started = OverlayBlockerServiceStarter.tryStart(applicationContext, request)
            AppLog.info("debug_overlay launch result=$started kind=$kind taskId=$taskId occurrence=$occurrenceKey")
        }
        finish()
    }

    private companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_OCCURRENCE_KEY = "occurrence_key"
        const val DEFAULT_TASK_ID = "debug-task"
        const val DEFAULT_OCCURRENCE_KEY = "debug-occurrence"
    }
}
