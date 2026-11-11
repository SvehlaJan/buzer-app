package app.buzer.android.observability

import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticLogRecord

/** The Main screen the user is on, attached to every later Sentry event and feedback report. */
data class ScreenContext(
    val screen: String,
    val taskId: String?
)

interface AndroidObservability {
    fun addLog(record: DiagnosticLogRecord)
    fun captureHandled(marker: String, throwable: Throwable, context: DiagnosticCorrelation = DiagnosticCorrelation())
    fun showFeedback()

    /** Sets the current screen context, or clears it when [context] is null. */
    fun setScreenContext(context: ScreenContext?)
}

object NoOpAndroidObservability : AndroidObservability {
    override fun addLog(record: DiagnosticLogRecord) {}
    override fun captureHandled(marker: String, throwable: Throwable, context: DiagnosticCorrelation) {}
    override fun showFeedback() {}
    override fun setScreenContext(context: ScreenContext?) {}
}

class RecordingAndroidObservability : AndroidObservability {
    val logs = mutableListOf<DiagnosticLogRecord>()
    val handledExceptions = mutableListOf<CapturedHandledException>()
    var feedbackShowCount: Int = 0

    @Volatile
    var screenContext: ScreenContext? = null
        private set

    data class CapturedHandledException(
        val marker: String,
        val throwable: Throwable,
        val context: DiagnosticCorrelation
    )

    override fun addLog(record: DiagnosticLogRecord) {
        logs.add(record)
    }

    override fun captureHandled(
        marker: String,
        throwable: Throwable,
        context: DiagnosticCorrelation
    ) {
        handledExceptions.add(CapturedHandledException(marker, throwable, context))
    }

    override fun showFeedback() {
        feedbackShowCount++
    }

    override fun setScreenContext(context: ScreenContext?) {
        screenContext = context
    }

    fun reset() {
        logs.clear()
        handledExceptions.clear()
        feedbackShowCount = 0
        screenContext = null
    }
}
