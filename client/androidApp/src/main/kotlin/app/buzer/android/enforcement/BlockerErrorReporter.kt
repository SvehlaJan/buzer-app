package app.buzer.android.enforcement

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import java.io.File
import java.time.Instant

interface BlockerErrorReporting {
    fun report(
        action: BlockerAction,
        taskId: String?,
        message: String,
        showNotification: Boolean = true
    )
}

class BlockerErrorReporter(
    private val context: Context?,
    directory: File,
    private val fileName: String = "blocker_action_errors.log"
) : BlockerErrorReporting {
    constructor(
        context: Context,
        fileName: String = "blocker_action_errors.log"
    ) : this(context, context.filesDir, fileName)

    constructor(
        directory: File,
        fileName: String = "blocker_action_errors.log"
    ) : this(null, directory, fileName)

    private val file = directory.resolve(fileName)

    override fun report(
        action: BlockerAction,
        taskId: String?,
        message: String,
        showNotification: Boolean
    ) {
        file.appendText(
            "${Instant.now()} action=${action.name} taskId=${taskId.orEmpty()} message=${message.singleLine()}\n"
        )
        if (showNotification) {
            showNotification(action, message.singleLine())
        }
    }

    private fun showNotification(action: BlockerAction, message: String) {
        val appContext = context ?: return
        NotificationChannels.ensureCreated(appContext)
        val notificationText = "Buzer backend action failed. Check VPS/backend health."
        val details = "$notificationText ${action.name}: $message"
        val notification = Notification.Builder(appContext, NotificationChannels.ERRORS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Backend action failed")
            .setContentText(notificationText)
            .setStyle(Notification.BigTextStyle().bigText(details))
            .setAutoCancel(true)
            .build()
        appContext.getSystemService(NotificationManager::class.java)
            .notify(ERROR_NOTIFICATION_ID, notification)
    }

    private fun String.singleLine(): String = replace('\n', ' ').replace('\r', ' ')

    private companion object {
        const val ERROR_NOTIFICATION_ID = 9417
    }
}
