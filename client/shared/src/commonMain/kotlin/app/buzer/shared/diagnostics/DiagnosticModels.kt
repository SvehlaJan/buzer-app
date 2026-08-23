package app.buzer.shared.diagnostics

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

data class DiagnosticLogRecord(
    val severity: String,
    val tag: String,
    val message: String,
    val throwableType: String? = null,
    val throwableMessage: String? = null
)
