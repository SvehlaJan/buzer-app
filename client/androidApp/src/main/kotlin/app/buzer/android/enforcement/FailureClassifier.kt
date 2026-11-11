package app.buzer.android.enforcement

import app.buzer.shared.api.BuzerApiException
import java.io.IOException
import kotlinx.coroutines.CancellationException

object FailureClassifier {
    private val EXPECTED_OPERATIONAL_API_CODES = setOf(
        "backend_busy",
        "todoist_command_rejected",
        "todoist_delivery_unknown",
        "todoist_accepted_snapshot_stale",
        "forced_refresh_failed",
        "rate_limited",
        "validation_error",
        "invalid_request",
        "sync_initializing"
    )

    fun isTransportFailure(error: Throwable?): Boolean {
        if (error == null) return false
        if (error is IOException) return true
        val className = error::class.qualifiedName.orEmpty()
        return className.contains("Timeout") ||
            className.contains("Connect") ||
            className.contains("UnresolvedAddress")
    }

    fun isExpectedOperationalApiError(error: BuzerApiException): Boolean =
        error.code in EXPECTED_OPERATIONAL_API_CODES || error.status in 400..499

    /**
     * Returns true when [error] is an operational backend/Todoist/transport failure rather than an
     * Android-owned local defect. Any [BuzerApiException] (including unexpected 5xx responses such
     * as `forced_failure` or `internal_error`) is owned by backend observability, so Android must
     * never emit a duplicate Sentry issue for it.
     */
    fun isOperationalFailure(error: Throwable): Boolean {
        if (error is CancellationException) return false
        if (error is BuzerApiException) return true
        return isTransportFailure(error)
    }
}
