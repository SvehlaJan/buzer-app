package app.buzer.android.observability

import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticLogRecord

interface AndroidObservability {
    fun addLog(record: DiagnosticLogRecord)
    fun captureHandled(marker: String, throwable: Throwable, context: DiagnosticCorrelation = DiagnosticCorrelation())
    fun showFeedback()
}

object NoOpAndroidObservability : AndroidObservability {
    override fun addLog(record: DiagnosticLogRecord) {}
    override fun captureHandled(marker: String, throwable: Throwable, context: DiagnosticCorrelation) {}
    override fun showFeedback() {}
}

class RecordingAndroidObservability : AndroidObservability {
    val logs = mutableListOf<DiagnosticLogRecord>()
    val handledExceptions = mutableListOf<CapturedHandledException>()
    var feedbackShowCount: Int = 0

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

    fun reset() {
        logs.clear()
        handledExceptions.clear()
        feedbackShowCount = 0
    }
}
