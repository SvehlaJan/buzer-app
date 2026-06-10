package app.buzer.android.enforcement.overlay

import android.content.Context
import android.content.Intent
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind

data class OverlayBlockerRequest(
    val kind: AlarmKind,
    val taskId: String?
) {
    fun intent(context: Context): Intent =
        Intent(context, OverlayBlockerService::class.java).apply {
            putExtra(AlarmIntentFactory.EXTRA_ALARM_KIND, kind.name)
            taskId?.let { putExtra(AlarmIntentFactory.EXTRA_TASK_ID, it) }
        }

    companion object {
        fun fromAlarm(kind: AlarmKind, taskId: String?): OverlayBlockerRequest? {
            return when (kind) {
                AlarmKind.MORNING_REVIEW -> OverlayBlockerRequest(kind, taskId)
                AlarmKind.START_BLOCKER,
                AlarmKind.DEADLINE_BLOCKER -> {
                    if (taskId.isNullOrBlank()) null else OverlayBlockerRequest(kind, taskId)
                }
                AlarmKind.PRE_START_WARNING -> null
            }
        }

        fun fromIntent(intent: Intent): OverlayBlockerRequest? {
            val kindName = intent.getStringExtra(AlarmIntentFactory.EXTRA_ALARM_KIND) ?: return null
            return fromExtras(kindName, intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID))
        }

        fun fromExtras(kindName: String, taskId: String?): OverlayBlockerRequest? {
            val kind = runCatching { AlarmKind.valueOf(kindName) }.getOrNull() ?: return null
            return fromAlarm(kind, taskId)
        }
    }
}
