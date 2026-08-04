package app.buzer.android.enforcement

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.buzer.android.MainActivity

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(alarm: ScheduledAlarm) {
        when (alarm.kind.schedulingPath()) {
            // Blocker alarms must fire on time on a locked, dozing device. setAlarmClock is
            // exempt from allow-while-idle throttling (observed 9+ min deferral on a real
            // Pixel, 2026-07-05); the status-bar alarm icon is an accepted tradeoff. Morning
            // Review is an equally user-facing, one-shot commitment and takes this same path.
            AlarmSchedulingPath.AlarmClock -> alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(alarm.triggerAtEpochMillis, showIntent()),
                pendingIntent(alarm)
            )

            AlarmSchedulingPath.ExactAndAllowWhileIdle ->
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarm.triggerAtEpochMillis,
                    pendingIntent(alarm)
                )
        }
    }

    override fun cancel(identity: ScheduledAlarmIdentity) {
        alarmManager.cancel(pendingIntent(identity))
    }

    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun pendingIntent(alarm: ScheduledAlarm): PendingIntent = pendingIntent(alarm.identity)

    private fun pendingIntent(identity: ScheduledAlarmIdentity): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            AlarmIntentFactory.requestCode(identity.taskId, identity.occurrenceKey, identity.kind),
            AlarmIntentFactory.receiverIntent(context, identity.taskId, identity.occurrenceKey, identity.kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

internal enum class AlarmSchedulingPath { AlarmClock, ExactAndAllowWhileIdle }

internal fun AlarmKind.schedulingPath(): AlarmSchedulingPath = when (this) {
    AlarmKind.MORNING_REVIEW, AlarmKind.START_BLOCKER, AlarmKind.DEADLINE_BLOCKER ->
        AlarmSchedulingPath.AlarmClock

    AlarmKind.PRE_START_WARNING -> AlarmSchedulingPath.ExactAndAllowWhileIdle
}
