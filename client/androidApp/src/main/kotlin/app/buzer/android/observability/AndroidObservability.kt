package app.buzer.android.observability

import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticLogRecord

data class DiagnosticAttachment(
    val pathname: String,
    val bytes: ByteArray,
    val contentType: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiagnosticAttachment) return false
        return pathname == other.pathname && bytes.contentEquals(other.bytes) && contentType == other.contentType
    }

    override fun hashCode(): Int {
        var result = pathname.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (contentType?.hashCode() ?: 0)
        return result
    }
}

interface AndroidObservability {
    fun isEnabled(): Boolean
    fun setContext(context: DiagnosticCorrelation)
    fun addLog(record: DiagnosticLogRecord, throwable: Throwable?)
    fun captureHandled(marker: String, throwable: Throwable, context: DiagnosticCorrelation = DiagnosticCorrelation()): String?
    fun newEventId(): String
    fun captureDiagnosticEvent(
        eventId: String,
        reportSessionId: String,
        submitted: Boolean,
        context: DiagnosticCorrelation,
        attachments: List<DiagnosticAttachment>
    ): String
    fun showFeedback(associatedEventId: String? = null)
    fun flush(timeoutMillis: Long = 2000L): Boolean
}

object NoOpAndroidObservability : AndroidObservability {
    override fun isEnabled(): Boolean = false
    override fun setContext(context: DiagnosticCorrelation) {}
    override fun addLog(record: DiagnosticLogRecord, throwable: Throwable?) {}
    override fun captureHandled(marker: String, throwable: Throwable, context: DiagnosticCorrelation): String? = null
    override fun newEventId(): String = ""
    override fun captureDiagnosticEvent(
        eventId: String,
        reportSessionId: String,
        submitted: Boolean,
        context: DiagnosticCorrelation,
        attachments: List<DiagnosticAttachment>
    ): String = eventId
    override fun showFeedback(associatedEventId: String?) {}
    override fun flush(timeoutMillis: Long): Boolean = true
}

class RecordingAndroidObservability : AndroidObservability {
    var enabled: Boolean = true
    val contexts = mutableListOf<DiagnosticCorrelation>()
    val logs = mutableListOf<Pair<DiagnosticLogRecord, Throwable?>>()
    val handledExceptions = mutableListOf<CapturedHandledException>()
    val diagnosticEvents = mutableListOf<CapturedDiagnosticEvent>()
    val feedbackRequests = mutableListOf<String?>()
    var flushCount: Int = 0

    data class CapturedHandledException(
        val marker: String,
        val throwable: Throwable,
        val context: DiagnosticCorrelation
    )

    data class CapturedDiagnosticEvent(
        val eventId: String,
        val reportSessionId: String,
        val submitted: Boolean,
        val context: DiagnosticCorrelation,
        val attachments: List<DiagnosticAttachment>
    )

    override fun isEnabled(): Boolean = enabled

    override fun setContext(context: DiagnosticCorrelation) {
        contexts.add(context)
    }

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

    override fun newEventId(): String = "fake-new-event-id-${diagnosticEvents.size + 1}"

    override fun captureDiagnosticEvent(
        eventId: String,
        reportSessionId: String,
        submitted: Boolean,
        context: DiagnosticCorrelation,
        attachments: List<DiagnosticAttachment>
    ): String {
        diagnosticEvents.add(
            CapturedDiagnosticEvent(
                eventId = eventId,
                reportSessionId = reportSessionId,
                submitted = submitted,
                context = context,
                attachments = attachments
            )
        )
        return eventId
    }

    override fun showFeedback(associatedEventId: String?) {
        feedbackRequests.add(associatedEventId)
    }

    override fun flush(timeoutMillis: Long): Boolean {
        flushCount++
        return true
    }

    fun reset() {
        contexts.clear()
        logs.clear()
        handledExceptions.clear()
        diagnosticEvents.clear()
        feedbackRequests.clear()
        flushCount = 0
    }
}
