package app.buzer.android.ui.screens

import app.buzer.shared.api.BuzerApiException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Add Task must classify errors exactly like [app.buzer.android.enforcement.EnforcementCoordinator]:
 * expected backend/Todoist/network uncertainty stays warning-only, everything else is Add Task's
 * own owned local defect (captured once via Sentry).
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
    fun aBuzerApiExceptionOutsideTheOperationalRangeIsAnOwnedDefect() {
        // Only 4xx statuses are treated as operational; a stray non-4xx status without a
        // recognized code is Add Task's own local defect, not expected backend uncertainty.
        assertFalse(isAddTaskOperationalFailure(BuzerApiException(status = 200, code = "unexpected")))
    }

    @Test
    fun transportAndTimeoutFailuresAreOperational() {
        assertTrue(isAddTaskOperationalFailure(IOException("Connection reset")))
        assertTrue(isAddTaskOperationalFailure(SocketTimeoutException("timeout")))
        assertTrue(isAddTaskOperationalFailure(ConnectException("Failed to connect")))
    }

    @Test
    fun anUnexpectedRuntimeDefectIsOwnedByAddTaskNotOperational() {
        assertFalse(isAddTaskOperationalFailure(IllegalStateException("response parsing invariant failed")))
        assertFalse(isAddTaskOperationalFailure(NullPointerException("unexpected null field")))
    }
}
