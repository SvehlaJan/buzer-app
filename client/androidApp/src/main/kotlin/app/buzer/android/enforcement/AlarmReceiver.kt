package app.buzer.android.enforcement

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.buzer.android.blockers.DeadlineBlockerActivity
import app.buzer.android.blockers.MorningReviewActivity
import app.buzer.android.blockers.StartBlockerActivity
import app.buzer.android.enforcement.overlay.OverlayBlockerRequest
import app.buzer.android.enforcement.overlay.OverlayBlockerServiceStarter
import app.buzer.shared.diagnostics.AppLog

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationChannels.ensureCreated(context)
        val taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID)
        val kindName = intent.getStringExtra(AlarmIntentFactory.EXTRA_ALARM_KIND) ?: return
        val kind = runCatching { AlarmKind.valueOf(kindName) }.getOrNull() ?: return

        when (kind) {
            AlarmKind.PRE_START_WARNING -> showPreStartNotification(context, taskId)
            AlarmKind.MORNING_REVIEW,
            AlarmKind.START_BLOCKER,
            AlarmKind.DEADLINE_BLOCKER -> openBlocker(context, taskId, kind)
        }
    }

    private fun openBlocker(context: Context, taskId: String?, kind: AlarmKind) {
        val request = OverlayBlockerRequest.fromExtras(taskId, kind.name)
        if (request != null) {
            val started = OverlayBlockerServiceStarter.tryStart(context, request)
            AppLog.info("overlay_start attempted result=$started kind=$kind taskId=$taskId")
        } else {
            AppLog.warn("overlay_start skipped reason=invalid_request kind=$kind taskId=$taskId")
        }
        // Always post the full-screen notification fallback. Its full-screen intent is the
        // activity launch path; we no longer start the activity directly from the broadcast,
        // which Android blocks from the background (BAL_BLOCK).
        val targetIntent = blockerIntent(context, taskId, kind).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        showBlockerNotification(context, taskId, kind, targetIntent)
    }

    private fun blockerIntent(context: Context, taskId: String?, kind: AlarmKind): Intent {
        val targetClass = when (kind) {
            AlarmKind.MORNING_REVIEW -> MorningReviewActivity::class.java
            AlarmKind.START_BLOCKER -> StartBlockerActivity::class.java
            AlarmKind.DEADLINE_BLOCKER -> DeadlineBlockerActivity::class.java
            AlarmKind.PRE_START_WARNING -> error("Pre-start is not a blocker activity")
        }
        return Intent(context, targetClass).apply {
            if (taskId != null) putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
        }
    }

    private fun showPreStartNotification(context: Context, taskId: String?) {
        val notification = Notification.Builder(context, NotificationChannels.PRE_START)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Task starts soon")
            .setContentText("A committed task starts in 1 hour.")
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(taskId ?: "pre-start", AlarmKind.PRE_START_WARNING), notification)
    }

    private fun showBlockerNotification(
        context: Context,
        taskId: String?,
        kind: AlarmKind,
        targetIntent: Intent
    ) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            AlarmIntentFactory.requestCode(taskId ?: "morning-review", kind),
            targetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, NotificationChannels.BLOCKERS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(blockerTitle(kind))
            .setContentText("Review your commitment now.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(taskId ?: "morning-review", kind), notification)
    }

    private fun blockerTitle(kind: AlarmKind): String {
        return when (kind) {
            AlarmKind.MORNING_REVIEW -> "Morning review"
            AlarmKind.START_BLOCKER -> "Start your task"
            AlarmKind.DEADLINE_BLOCKER -> "Task deadline"
            AlarmKind.PRE_START_WARNING -> "Task starts soon"
        }
    }

    private fun notificationId(taskId: String, kind: AlarmKind): Int {
        return AlarmIntentFactory.requestCode(taskId, kind)
    }
}
