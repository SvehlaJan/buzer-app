package app.buzer.android.ui.screens

import app.buzer.shared.api.BuzerApiException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Add Task must classify errors like the combined effect of EnforcementCoordinator and
 * BlockerErrorReporter: any structured backend response is the backend's own responsibility,
 * so it stays warning-only regardless of its code or HTTP status. Only a genuinely local
 * exception (no BuzerApiException at all) is Add Task's own owned defect (captured once via
 * Sentry).
 */
class AddTaskOperationalFailureTest {

    @Test
    fun knownOperationalCodesAreNotAnAddTaskOwnedDefect() {
        val codes = listOf(
            "backend_busy",
            "todoist_command_rejected",
            "todoist_delivery_unknown",
            "todoist_accepted_snapshot_stale",
            "forced_refresh_failed",
            "rate_limited",
            "validation_error",
            "invalid_request"
        )
        for (code in codes) {
            val error = BuzerApiException(status = 503, code = code, message = "operational")
            assertTrue(isAddTaskOperationalFailure(error), "expected $code to be operational")
        }
    }

    @Test
    fun anyClientOrServerHttpStatusIsOperationalEvenWithoutAKnownCode() {
        assertTrue(isAddTaskOperationalFailure(BuzerApiException(status = 400, code = null)))
        assertTrue(isAddTaskOperationalFailure(BuzerApiException(status = 499, code = "unrecognized")))
    }

    @Test
    fun anUnrecognizedBackendResponseIsStillTheBackendsOwnResponsibility() {
        // A structured backend error the backend itself already owns (e.g. an unexpected 500
        // with no recognized code) must never become a duplicate Android-owned Issue -- Android
        // never re-captures any BuzerApiException, matching BlockerErrorReporter's own guard.
        assertTrue(isAddTaskOperationalFailure(BuzerApiException(status = 500, code = "forced_failure")))
        assertTrue(isAddTaskOperationalFailure(BuzerApiException(status = 200, code = "unexpected")))
    }

    @Test
    fun transportAndTimeoutFailuresAreOperational() {
        assertTrue(isAddTaskOperationalFailure(IOException("Connection reset")))
        assertTrue(isAddTaskOperationalFailure(SocketTimeoutException("timeout")))
        assertTrue(isAddTaskOperationalFailure(ConnectException("Failed to connect")))
    }

    @Test
    fun anUnexpectedRuntimeDefectWithNoBackendResponseIsOwnedByAddTask() {
        // No BuzerApiException means the backend never got a chance to own this: it is Add
        // Task's own local defect (response parsing, an unexpected invariant).
        assertFalse(isAddTaskOperationalFailure(IllegalStateException("response parsing invariant failed")))
        assertFalse(isAddTaskOperationalFailure(NullPointerException("unexpected null field")))
    }
}
