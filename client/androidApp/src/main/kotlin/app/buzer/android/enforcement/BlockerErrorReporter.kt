package app.buzer.android.enforcement

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import app.buzer.android.observability.AndroidObservability
import app.buzer.android.observability.ObservabilityRuntime
import app.buzer.shared.api.BuzerApiException
import app.buzer.shared.api.BuzerApiRequestCorrelation
import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticLogRecord
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException

interface BlockerErrorReporting {
    fun report(
        action: BlockerAction,
        taskId: String?,
        message: String,
        showNotification: Boolean = true,
        throwable: Throwable? = null
    )
}

class BlockerErrorReporter(
    private val context: Context?,
    directory: File,
    private val fileName: String = "blocker_action_errors.log",
    private val observability: AndroidObservability = ObservabilityRuntime.instance
) : BlockerErrorReporting {
    constructor(
        context: Context,
        fileName: String = "blocker_action_errors.log",
        observability: AndroidObservability = ObservabilityRuntime.instance
    ) : this(context, context.filesDir, fileName, observability)

    constructor(
        directory: File,
        fileName: String = "blocker_action_errors.log",
        observability: AndroidObservability = ObservabilityRuntime.instance
    ) : this(null, directory, fileName, observability)

    private val file = directory.resolve(fileName)

    override fun report(
        action: BlockerAction,
        taskId: String?,
        message: String,
        showNotification: Boolean,
        throwable: Throwable?
    ) {
        runCatching {
            file.appendText(
                "${Instant.now()} action=${action.name} taskId=${taskId.orEmpty()} message=${message.singleLine()}\n"
            )
        }
        if (showNotification) {
            runCatching {
                showNotification(action, message.singleLine())
            }
        }
        runCatching {
            reportToObservability(action, taskId, message, throwable)
        }
    }

    private fun reportToObservability(
        action: BlockerAction,
        taskId: String?,
        message: String,
        throwable: Throwable?
    ) {
        val correlation = DiagnosticCorrelation(
            taskId = taskId,
            requestId = throwable.requestId(),
            appSection = "Enforcement"
        )
        if (isCaptureWorthyNonFatal(action, message, throwable)) {
            val exceptionToCapture = throwable ?: RuntimeException("Blocker action error: ${action.name} - $message")
            if (shouldCapture(exceptionToCapture)) {
                val marker = "enforcement.${action.name.lowercase()}"
                observability.captureHandled(marker, exceptionToCapture, correlation)
            }
        } else {
            observability.addLog(
                DiagnosticLogRecord(
                    severity = "warning",
                    tag = "BlockerError",
                    message = "action=${action.name} taskId=${taskId.orEmpty()} message=$message",
                    throwableType = throwable?.let { it::class.simpleName },
                    throwableMessage = throwable?.message
                )
            )
        }
    }

    private fun isCaptureWorthyNonFatal(
        action: BlockerAction,
        message: String,
        throwable: Throwable?
    ): Boolean {
        if (throwable is CancellationException) return false
        if (throwable is BuzerApiException) return false
        if (isTransportOrTimeout(throwable)) return false
        if (isValidationOrClientError(throwable, message)) return false
        return true
    }

    private fun isTransportOrTimeout(throwable: Throwable?): Boolean {
        if (throwable == null) return false
        if (throwable is IOException) return true
        val className = throwable::class.qualifiedName.orEmpty()
        return className.contains("Timeout") ||
            className.contains("Connect") ||
            className.contains("UnresolvedAddress")
    }

    private fun isValidationOrClientError(throwable: Throwable?, message: String): Boolean {
        if (throwable is IllegalArgumentException &&
            (throwable.message?.contains("validation", ignoreCase = true) == true || message.contains("validation", ignoreCase = true))
        ) {
            return true
        }
        if (message.contains("validation", ignoreCase = true) || message.contains("invalid input", ignoreCase = true)) {
            return true
        }
        return false
    }

    private fun shouldCapture(throwable: Throwable): Boolean {
        return capturedThrowables.add(throwable)
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
            ?.notify(ERROR_NOTIFICATION_ID, notification)
    }

    private fun String.singleLine(): String = replace('\n', ' ').replace('\r', ' ')

    private fun Throwable?.requestId(): String? = when (this) {
        is BuzerApiException -> requestId
        null -> null
        else -> suppressedExceptions.filterIsInstance<BuzerApiRequestCorrelation>().firstOrNull()?.requestId
    }

    companion object {
        const val ERROR_NOTIFICATION_ID = 9417
        private val capturedThrowables: MutableSet<Throwable> =
            Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<Throwable, Boolean>()))

        internal fun resetDeduplicationForTests() {
            capturedThrowables.clear()
        }
    }
}
