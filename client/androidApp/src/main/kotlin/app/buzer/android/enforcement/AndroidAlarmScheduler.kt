package app.buzer.android.enforcement

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.buzer.android.MainActivity

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(alarm: ScheduledAlarm) {
        when (alarm.kind) {
            // Blocker alarms must fire on time on a locked, dozing device. setAlarmClock is
            // exempt from allow-while-idle throttling (observed 9+ min deferral on a real
            // Pixel, 2026-07-05); the status-bar alarm icon is an accepted tradeoff.
            AlarmKind.START_BLOCKER, AlarmKind.DEADLINE_BLOCKER -> alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(alarm.triggerAtEpochMillis, showIntent()),
                pendingIntent(alarm.taskId, alarm.kind)
            )

            AlarmKind.PRE_START_WARNING, AlarmKind.MORNING_REVIEW ->
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarm.triggerAtEpochMillis,
                    pendingIntent(alarm.taskId, alarm.kind)
                )
        }
    }

    override fun cancel(taskId: String, kind: AlarmKind) {
        alarmManager.cancel(pendingIntent(taskId, kind))
    }

    override fun cancelAllForTask(taskId: String) {
        AlarmKind.entries.forEach { cancel(taskId, it) }
    }

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun pendingIntent(taskId: String, kind: AlarmKind): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            AlarmIntentFactory.requestCode(taskId, kind),
            AlarmIntentFactory.receiverIntent(context, taskId, kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
