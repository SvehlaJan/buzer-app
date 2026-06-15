package app.buzer.android.enforcement

import android.app.NotificationManager
import android.content.Context

interface BlockerNotificationCleaner {
    fun cancelForTask(taskId: String)
}

object NoOpBlockerNotificationCleaner : BlockerNotificationCleaner {
    override fun cancelForTask(taskId: String) = Unit
}

class AndroidBlockerNotificationCleaner(context: Context) : BlockerNotificationCleaner {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    override fun cancelForTask(taskId: String) {
        BLOCKER_KINDS.forEach { kind ->
            notificationManager.cancel(null, AlarmIntentFactory.requestCode(taskId, kind))
        }
    }

    private companion object {
        val BLOCKER_KINDS = listOf(
            AlarmKind.START_BLOCKER,
            AlarmKind.DEADLINE_BLOCKER
        )
    }
}
