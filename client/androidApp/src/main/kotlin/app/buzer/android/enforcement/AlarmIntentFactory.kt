package app.buzer.android.enforcement

import android.content.Context
import android.content.Intent

enum class AlarmKind {
    MORNING_REVIEW,
    PRE_START_WARNING,
    START_BLOCKER,
    DEADLINE_BLOCKER
}

object AlarmIntentFactory {
    const val EXTRA_TASK_ID = "app.buzer.extra.TASK_ID"
    const val EXTRA_ALARM_KIND = "app.buzer.extra.ALARM_KIND"

    fun requestCode(taskId: String, kind: AlarmKind): Int {
        return "$taskId:${kind.name}".hashCode()
    }

    fun receiverIntent(context: Context, taskId: String, kind: AlarmKind): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_ALARM_KIND, kind.name)
        }
    }
}
