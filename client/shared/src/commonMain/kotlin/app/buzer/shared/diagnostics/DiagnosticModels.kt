package app.buzer.shared.diagnostics

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticCorrelation(
    val deviceId: String? = null,
    val taskId: String? = null,
    val occurrenceKey: String? = null,
    val actionId: String? = null,
    val requestId: String? = null,
    val screen: String? = null,
    val blockerType: String? = null,
    val appSection: String? = null
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
