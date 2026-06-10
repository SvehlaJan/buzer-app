package app.buzer.android.debug

import android.app.Activity
import android.os.Bundle
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.overlay.OverlayBlockerRequest
import app.buzer.android.enforcement.overlay.OverlayBlockerServiceStarter
import app.buzer.shared.diagnostics.AppLog

class DebugOverlayLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = parseKind()
        val taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)
            ?: intent.getStringExtra(EXTRA_TASK_ID_SHORT)
            ?: DEFAULT_TASK_ID
        val request = OverlayBlockerRequest.fromAlarm(
            kind = kind,
            taskId = if (kind == AlarmKind.MORNING_REVIEW) null else taskId
        )
        val started = request?.let { OverlayBlockerServiceStarter.tryStart(this, it) } ?: false
        AppLog.info("debug_overlay_launcher result=$started kind=$kind taskId=$taskId")
        finish()
    }

    private fun parseKind(): AlarmKind {
        val raw = intent.getStringExtra(AlarmIntentFactory.EXTRA_ALARM_KIND)
            ?: intent.getStringExtra(EXTRA_KIND_SHORT)
            ?: AlarmKind.START_BLOCKER.name
        return runCatching { AlarmKind.valueOf(raw) }.getOrDefault(AlarmKind.START_BLOCKER)
    }

    private companion object {
        const val EXTRA_KIND_SHORT = "kind"
        const val EXTRA_TASK_ID_SHORT = "task_id"
        const val DEFAULT_TASK_ID = "debug-task"
    }
}
