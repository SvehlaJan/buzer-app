package app.buzer.shared.diagnostics

import co.touchlab.kermit.Logger
import kotlin.concurrent.Volatile

fun interface DiagnosticLogSink {
    fun record(record: DiagnosticLogRecord)
}

object AppLog {
    private val logger = Logger.withTag("Buzer")

    @Volatile
    private var sink: DiagnosticLogSink? = null

    fun installSink(value: DiagnosticLogSink?) {
        sink = value
    }

    internal fun resetSinkForTests() {
        sink = null
    }

    fun debug(message: String, attributes: Map<String, String> = emptyMap()) =
        emit("debug", message, null, attributes) { logger.d { formatLog(message, attributes) } }

    fun info(message: String, attributes: Map<String, String> = emptyMap()) =
        emit("info", message, null, attributes) { logger.i { formatLog(message, attributes) } }

    fun warn(
        message: String,
        throwable: Throwable? = null,
        attributes: Map<String, String> = emptyMap()
    ) = emit("warning", message, throwable, attributes) { logger.w(throwable) { formatLog(message, attributes) } }

    fun warn(
        message: String,
        attributes: Map<String, String>
    ) = warn(message, null, attributes)

    fun error(
        message: String,
        throwable: Throwable? = null,
        attributes: Map<String, String> = emptyMap()
    ) = emit("error", message, throwable, attributes) { logger.e(throwable) { formatLog(message, attributes) } }

    fun error(
        message: String,
        attributes: Map<String, String>
    ) = error(message, null, attributes)

    private fun formatLog(message: String, attributes: Map<String, String>): String =
        if (attributes.isEmpty()) {
            message
        } else {
            val formattedAttrs = attributes.entries.joinToString(" ") { "${it.key}=${it.value}" }
            "$message $formattedAttrs"
        }

    private inline fun emit(
        severity: String,
        message: String,
        throwable: Throwable?,
        attributes: Map<String, String>,
        local: () -> Unit
    ) {
        local()
        runCatching {
            sink?.record(
                DiagnosticLogRecord(
                    severity = severity,
                    tag = "Buzer",
                    message = message,
                    throwableType = throwable?.let { it::class.simpleName },
                    throwableMessage = throwable?.message,
                    attributes = attributes
                )
            )
        }
    }
}
