package app.buzer.android.enforcement

import app.buzer.shared.api.BuzerApiException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.channels.UnresolvedAddressException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailureClassifierTest {

    @Test
    fun knownOperationalCodesAreExpectedOperationalAndNotAndroidOwnedDefects() {
        val codes = listOf(
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
        for (code in codes) {
            val error = BuzerApiException(status = 503, code = code, message = "operational")
            assertTrue(
                FailureClassifier.isExpectedOperationalApiError(error),
                "expected $code to be an expected operational API error"
            )
            assertTrue(
                FailureClassifier.isOperationalFailure(error),
                "expected $code to be classified as operational"
            )
        }
    }

    @Test
    fun client4xxStatusesAreExpectedOperationalEvenWithoutKnownCode() {
        val error400 = BuzerApiException(status = 400, code = null)
        val error499 = BuzerApiException(status = 499, code = "unrecognized")

        assertTrue(FailureClassifier.isExpectedOperationalApiError(error400))
        assertTrue(FailureClassifier.isExpectedOperationalApiError(error499))
        assertTrue(FailureClassifier.isOperationalFailure(error400))
        assertTrue(FailureClassifier.isOperationalFailure(error499))
    }

    @Test
    fun unexpectedServer5xxRemainsBackendOwnedOperationalFailureButNotExpectedOperationalApiError() {
        val forcedFailure = BuzerApiException(status = 500, code = "forced_failure")
        val internalError = BuzerApiException(status = 500, code = "internal_error")
        val unexpected200 = BuzerApiException(status = 200, code = "unexpected")

        assertFalse(FailureClassifier.isExpectedOperationalApiError(forcedFailure))
        assertFalse(FailureClassifier.isExpectedOperationalApiError(internalError))
        assertFalse(FailureClassifier.isExpectedOperationalApiError(unexpected200))

        // Any BuzerApiException is owned by the backend for Sentry reporting, never duplicated on Android.
        assertTrue(FailureClassifier.isOperationalFailure(forcedFailure))
        assertTrue(FailureClassifier.isOperationalFailure(internalError))
        assertTrue(FailureClassifier.isOperationalFailure(unexpected200))
    }

    @Test
    fun transportAndTimeoutFailuresAreOperational() {
        val transportErrors = listOf<Throwable>(
            IOException("Connection reset"),
            SocketTimeoutException("timeout"),
            ConnectException("Failed to connect"),
            UnresolvedAddressException()
        )
        for (error in transportErrors) {
            assertTrue(FailureClassifier.isTransportFailure(error), "expected ${error::class.simpleName} to be transport")
            assertTrue(FailureClassifier.isOperationalFailure(error), "expected ${error::class.simpleName} to be operational")
        }
        assertFalse(FailureClassifier.isTransportFailure(null))
    }

    @Test
    fun cancellationAndLocalRuntimeDefectsAreNotOperational() {
        assertFalse(FailureClassifier.isOperationalFailure(CancellationException("cancelled")))
        assertFalse(FailureClassifier.isOperationalFailure(IllegalStateException("response parsing invariant failed")))
        assertFalse(FailureClassifier.isOperationalFailure(NullPointerException("unexpected null field")))
    }
}
