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

    fun debug(message: String) = emit("debug", message, null) { logger.d { message } }
    fun info(message: String) = emit("info", message, null) { logger.i { message } }
    fun warn(message: String, throwable: Throwable? = null) =
        emit("warning", message, throwable) { logger.w(throwable) { message } }
    fun error(message: String, throwable: Throwable? = null) =
        emit("error", message, throwable) { logger.e(throwable) { message } }

    private inline fun emit(
        severity: String,
        message: String,
        throwable: Throwable?,
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
                    throwableMessage = throwable?.message
                )
            )
        }
    }
}
