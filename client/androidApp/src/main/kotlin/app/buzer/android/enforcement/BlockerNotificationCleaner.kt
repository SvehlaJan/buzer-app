package app.buzer.android.enforcement

import android.app.NotificationManager
import android.content.Context

interface BlockerNotificationCleaner {
    /** Clears the exact occurrence that produced the blocker notification. */
    fun cancelForTask(taskId: String, occurrenceKey: String)
}

object NoOpBlockerNotificationCleaner : BlockerNotificationCleaner {
    override fun cancelForTask(taskId: String, occurrenceKey: String) = Unit
}

class AndroidBlockerNotificationCleaner(context: Context) : BlockerNotificationCleaner {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    override fun cancelForTask(taskId: String, occurrenceKey: String) {
        currentBlockerNotificationIds(taskId, occurrenceKey).forEach { notificationId ->
            notificationManager.cancel(null, notificationId)
        }
        BLOCKER_KINDS.forEach { kind ->
            // Clean notifications emitted by pre-occurrence-key app versions during upgrade.
            notificationManager.cancel(null, AlarmIntentFactory.legacyRequestCode(taskId, kind))
        }
    }
}

internal fun currentBlockerNotificationIds(taskId: String, occurrenceKey: String): Set<Int> =
    BLOCKER_KINDS.mapTo(linkedSetOf()) { kind ->
        AlarmIntentFactory.requestCode(taskId, occurrenceKey, kind)
    }

private val BLOCKER_KINDS = listOf(
    AlarmKind.MORNING_REVIEW,
    AlarmKind.START_BLOCKER,
    AlarmKind.DEADLINE_BLOCKER
)
