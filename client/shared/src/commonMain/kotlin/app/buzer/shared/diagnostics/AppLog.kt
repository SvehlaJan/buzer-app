package app.buzer.shared.diagnostics

import co.touchlab.kermit.Logger

object AppLog {
    private val logger = Logger.withTag("Buzer")

    fun debug(message: String) {
        logger.d { message }
    }

    fun info(message: String) {
        logger.i { message }
    }

    fun warn(message: String, throwable: Throwable? = null) {
        logger.w(throwable) { message }
    }

    fun error(message: String, throwable: Throwable? = null) {
        logger.e(throwable) { message }
    }
}
