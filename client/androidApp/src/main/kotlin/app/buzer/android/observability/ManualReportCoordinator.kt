package app.buzer.android.observability

import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticReportRequest
import app.buzer.shared.diagnostics.DiagnosticReportResponse
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

interface TemporaryScopeModifier {
    fun setTemporaryScope(reportSessionId: String, androidDiagnosticEventId: String)
    fun clearTemporaryScope()
}

object SentryTemporaryScopeModifier : TemporaryScopeModifier {
    override fun setTemporaryScope(reportSessionId: String, androidDiagnosticEventId: String) {
        Sentry.configureScope { scope ->
            scope.setTag("report_session_id", reportSessionId)
            scope.setTag("android_diagnostic_event_id", androidDiagnosticEventId)
        }
    }

    override fun clearTemporaryScope() {
        Sentry.configureScope { scope ->
            scope.removeTag("report_session_id")
            scope.removeTag("android_diagnostic_event_id")
        }
    }
}

enum class ManualReportState {
    IDLE,
    FORM_OPEN,
    SUBMITTED,
    FAILED,
    CLOSED
}

class ManualReportCoordinator(
    private val contextStore: DiagnosticContextStore = DiagnosticContextStore,
    private val attachmentCollector: AndroidAttachmentCollector = AndroidAttachmentCollector(),
    private val observability: AndroidObservability = ObservabilityRuntime.instance,
    private val scopeModifier: TemporaryScopeModifier = SentryTemporaryScopeModifier,
    var backendReportSender: (suspend (DiagnosticReportRequest) -> DiagnosticReportResponse)? = null,
    private val recentRequestIdsProvider: (() -> List<String>)? = null,
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val lock = Any()

    @Volatile
    private var _state: ManualReportState = ManualReportState.IDLE
    val state: ManualReportState
        get() = _state

    private var activeSession: ActiveSession? = null

    private data class ActiveSession(
        val reportSessionId: String,
        val androidDiagnosticEventId: String,
        val capturedAt: String,
        val frozenSnapshot: DiagnosticContextSnapshot,
        val correlation: DiagnosticCorrelation,
        val attachmentDeferred: Deferred<List<DiagnosticAttachment>>
    )

    fun onFormOpen(): Job {
        synchronized(lock) {
            activeSession?.let { oldSession ->
                oldSession.attachmentDeferred.cancel()
                scopeModifier.clearTemporaryScope()
            }

            val reportSessionId = uuidProvider()
            val rawEventId = observability.newEventId().ifBlank { uuidProvider() }
            val androidDiagnosticEventId = rawEventId.replace("-", "").lowercase()

            val frozenSnapshot = contextStore.snapshot()
            val correlation = DiagnosticCorrelation(
                deviceId = frozenSnapshot.correlation.deviceId,
                taskId = frozenSnapshot.correlation.taskId,
                occurrenceKey = frozenSnapshot.correlation.occurrenceKey,
                actionId = frozenSnapshot.correlation.actionId,
                requestId = frozenSnapshot.correlation.requestId,
                traceId = frozenSnapshot.correlation.traceId,
                screen = frozenSnapshot.ui.screen,
                blockerType = frozenSnapshot.ui.blockerType,
                appSection = frozenSnapshot.ui.appSection,
                reportSessionId = reportSessionId
            )

            scopeModifier.setTemporaryScope(reportSessionId, androidDiagnosticEventId)

            val capturedAt = Instant.ofEpochMilli(clock()).toString()
            val attachmentDeferred = scope.async {
                attachmentCollector.collectAttachments(
                    reportSessionId = reportSessionId,
                    eventId = androidDiagnosticEventId,
                    capturedAt = capturedAt
                )
            }

            activeSession = ActiveSession(
                reportSessionId = reportSessionId,
                androidDiagnosticEventId = androidDiagnosticEventId,
                capturedAt = capturedAt,
                frozenSnapshot = frozenSnapshot,
                correlation = correlation,
                attachmentDeferred = attachmentDeferred
            )
            _state = ManualReportState.FORM_OPEN
            return attachmentDeferred
        }
    }

    fun onSubmitSuccess(userFeedback: Any? = null): Job? {
        val session = synchronized(lock) {
            if (_state != ManualReportState.FORM_OPEN || activeSession == null) {
                return null
            }
            _state = ManualReportState.SUBMITTED
            val s = activeSession
            activeSession = null
            scopeModifier.clearTemporaryScope()
            _state = ManualReportState.IDLE
            s
        } ?: return null

        return scope.launch {
            val attachments = runCatching { session.attachmentDeferred.await() }.getOrElse { emptyList() }
            observability.captureDiagnosticEvent(
                eventId = session.androidDiagnosticEventId,
                reportSessionId = session.reportSessionId,
                submitted = true,
                context = session.correlation,
                attachments = attachments
            )

            val sender = backendReportSender
            if (sender != null) {
                runCatching {
                    val recentRequestIds = recentRequestIdsProvider?.invoke()
                        ?: DiagnosticRingBuffer.network.snapshot(clock()).map { it.requestId }.distinct().take(20)
                    val request = DiagnosticReportRequest(
                        schemaVersion = 1,
                        reportSessionId = session.reportSessionId,
                        androidDiagnosticEventId = session.androidDiagnosticEventId,
                        deviceId = session.correlation.deviceId ?: session.frozenSnapshot.correlation.deviceId ?: "",
                        capturedAt = session.capturedAt,
                        taskId = session.correlation.taskId,
                        occurrenceKey = session.correlation.occurrenceKey,
                        actionId = session.correlation.actionId,
                        requestIds = recentRequestIds,
                        traceId = session.correlation.traceId
                    )
                    sender.invoke(request)
                }.onFailure { error ->
                    AppLog.warn("Failed to send backend diagnostic report: ${error.message}", error)
                }
            }
        }
    }

    fun onFormClose(): Job? {
        val session = synchronized(lock) {
            if (_state != ManualReportState.FORM_OPEN || activeSession == null) {
                return null
            }
            _state = ManualReportState.CLOSED
            val s = activeSession
            activeSession = null
            scopeModifier.clearTemporaryScope()
            _state = ManualReportState.IDLE
            s
        } ?: return null

        return scope.launch {
            val attachments = runCatching { session.attachmentDeferred.await() }.getOrElse { emptyList() }
            observability.captureDiagnosticEvent(
                eventId = session.androidDiagnosticEventId,
                reportSessionId = session.reportSessionId,
                submitted = false,
                context = session.correlation,
                attachments = attachments
            )
        }
    }

    fun onSubmitError(error: Any? = null): Job? {
        val session = synchronized(lock) {
            if (_state != ManualReportState.FORM_OPEN || activeSession == null) {
                return null
            }
            _state = ManualReportState.FAILED
            val s = activeSession
            activeSession = null
            scopeModifier.clearTemporaryScope()
            _state = ManualReportState.IDLE
            s
        } ?: return null

        return scope.launch {
            val attachments = runCatching { session.attachmentDeferred.await() }.getOrElse { emptyList() }
            observability.captureDiagnosticEvent(
                eventId = session.androidDiagnosticEventId,
                reportSessionId = session.reportSessionId,
                submitted = false,
                context = session.correlation,
                attachments = attachments
            )
        }
    }

    fun reset() {
        synchronized(lock) {
            activeSession?.attachmentDeferred?.cancel()
            activeSession = null
            scopeModifier.clearTemporaryScope()
            _state = ManualReportState.IDLE
        }
    }
}
