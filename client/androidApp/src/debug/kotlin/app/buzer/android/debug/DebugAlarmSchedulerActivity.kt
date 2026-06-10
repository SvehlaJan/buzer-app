package app.buzer.android.debug

import android.app.Activity
import android.os.Bundle
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.AndroidAlarmScheduler
import app.buzer.android.enforcement.ScheduledAlarm
import app.buzer.shared.diagnostics.AppLog

class DebugAlarmSchedulerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = parseKind()
        val taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)
            ?: intent.getStringExtra(EXTRA_TASK_ID_SHORT)
            ?: DEFAULT_TASK_ID
        val delaySeconds = intent.getIntExtra(EXTRA_DELAY_SECONDS, DEFAULT_DELAY_SECONDS)
            .coerceAtLeast(1)
        AndroidAlarmScheduler(applicationContext).schedule(
            ScheduledAlarm(
                taskId = taskId,
                kind = kind,
                triggerAtEpochMillis = System.currentTimeMillis() + delaySeconds * 1_000L
            )
        )
        AppLog.info("debug_alarm_scheduler scheduled kind=$kind taskId=$taskId delaySeconds=$delaySeconds")
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
        const val EXTRA_DELAY_SECONDS = "delay_seconds"
        const val DEFAULT_DELAY_SECONDS = 15
        const val DEFAULT_TASK_ID = "debug-task"
    }
}
