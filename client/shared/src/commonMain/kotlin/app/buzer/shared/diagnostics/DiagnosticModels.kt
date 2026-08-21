package app.buzer.shared.diagnostics

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticCorrelation(
    val deviceId: String? = null,
    val taskId: String? = null,
    val occurrenceKey: String? = null,
    val actionId: String? = null,
    val requestId: String? = null,
    val traceId: String? = null,
    val screen: String? = null,
    val blockerType: String? = null,
    val appSection: String? = null,
    val reportSessionId: String? = null
)

@Serializable
data class DiagnosticLogRecord(
    val capturedAt: String,
    val severity: String,
    val tag: String,
    val message: String,
    val throwableType: String? = null,
    val throwableMessage: String? = null
)

@Serializable
data class DiagnosticNetworkExchange(
    val startedAt: String,
    val durationMillis: Long,
    val method: String,
    val path: String,
    val status: Int? = null,
    val outcome: String,
    val requestContentType: String? = null,
    val responseContentType: String? = null,
    val requestBody: String? = null,
    val responseBody: String? = null,
    val requestId: String,
    val traceId: String? = null,
    val actionId: String? = null,
    val requestTruncated: Boolean = false,
    val responseTruncated: Boolean = false
)

@Serializable
data class DiagnosticReportRequest(
    val schemaVersion: Int = 1,
    val reportSessionId: String,
    val androidDiagnosticEventId: String,
    val deviceId: String,
    val capturedAt: String,
    val taskId: String? = null,
    val occurrenceKey: String? = null,
    val actionId: String? = null,
    val requestIds: List<String> = emptyList(),
    val traceId: String? = null
)

@Serializable
data class DiagnosticReportResponse(
    val schemaVersion: Int,
    val reportSessionId: String,
    val backendDiagnosticEventId: String,
    val outcome: String
)
