package app.buzer.android.enforcement

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(alarm: ScheduledAlarm) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            alarm.triggerAtEpochMillis,
            pendingIntent(alarm.taskId, alarm.kind)
        )
    }

    override fun cancel(taskId: String, kind: AlarmKind) {
        alarmManager.cancel(pendingIntent(taskId, kind))
    }

    override fun cancelAllForTask(taskId: String) {
        AlarmKind.entries.forEach { cancel(taskId, it) }
    }

    private fun pendingIntent(taskId: String, kind: AlarmKind): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            AlarmIntentFactory.requestCode(taskId, kind),
            AlarmIntentFactory.receiverIntent(context, taskId, kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
