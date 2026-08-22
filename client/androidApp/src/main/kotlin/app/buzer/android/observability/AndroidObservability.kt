package app.buzer.android.observability

import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticLogRecord

interface AndroidObservability {
    fun isEnabled(): Boolean
    fun addLog(record: DiagnosticLogRecord, throwable: Throwable?)
    fun captureHandled(marker: String, throwable: Throwable, context: DiagnosticCorrelation = DiagnosticCorrelation()): String?
    fun showFeedback()
    fun flush(timeoutMillis: Long = 2_000L): Boolean
}

object NoOpAndroidObservability : AndroidObservability {
    override fun isEnabled(): Boolean = false
    override fun addLog(record: DiagnosticLogRecord, throwable: Throwable?) {}
    override fun captureHandled(marker: String, throwable: Throwable, context: DiagnosticCorrelation): String? = null
    override fun showFeedback() {}
    override fun flush(timeoutMillis: Long): Boolean = true
}

class RecordingAndroidObservability : AndroidObservability {
    var enabled: Boolean = true
    val logs = mutableListOf<Pair<DiagnosticLogRecord, Throwable?>>()
    val handledExceptions = mutableListOf<CapturedHandledException>()
    var feedbackShowCount: Int = 0
    var flushCount: Int = 0

    data class CapturedHandledException(
        val marker: String,
        val throwable: Throwable,
        val context: DiagnosticCorrelation
    )

    override fun isEnabled(): Boolean = enabled

    override fun addLog(record: DiagnosticLogRecord, throwable: Throwable?) {
        logs.add(record to throwable)
    }

    override fun captureHandled(
        marker: String,
        throwable: Throwable,
        context: DiagnosticCorrelation
    ): String? {
        handledExceptions.add(CapturedHandledException(marker, throwable, context))
        return "fake-event-id-${handledExceptions.size}"
    }

    override fun showFeedback() {
        feedbackShowCount++
    }

    override fun flush(timeoutMillis: Long): Boolean {
        flushCount++
        return true
    }

    fun reset() {
        logs.clear()
        handledExceptions.clear()
        feedbackShowCount = 0
        flushCount = 0
    }
}

