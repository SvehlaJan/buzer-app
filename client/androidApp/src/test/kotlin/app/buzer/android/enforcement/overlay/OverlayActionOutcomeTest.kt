package app.buzer.android.enforcement.overlay

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.TasksSnapshotResponse
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class OverlayActionOutcomeTest {
    @Test
    fun successDismissesOverlay() {
        assertEquals(
            OverlayActionDisposition.Dismiss,
            resolveOverlayActionOutcome(Result.success(CoordinatorActionResult.Success))
        )
    }

    @Test
    fun nonSuccessResultsEscalateWithTheirMessageAndNoDefect() {
        val snapshot = TasksSnapshotResponse(1, "2026-05-28T09:00:00Z", emptyList(), emptyList<HealthStatus>(), 1)
        val cases = listOf(
            CoordinatorActionResult.Warning("todoist_delivery_unknown", "Delivery unknown", null) to "Delivery unknown",
            CoordinatorActionResult.Conflict("stale_occurrence", "Task changed", snapshot) to "Task changed",
            CoordinatorActionResult.TransportFailure(isConnected = false, message = "Offline") to "Offline",
            CoordinatorActionResult.LocalReconciliationFailed(snapshot, "Alarms not updated") to "Alarms not updated",
            CoordinatorActionResult.ChildActionInFlight to "Action already in progress"
        )

        cases.forEach { (result, message) ->
            assertEquals(
                OverlayActionDisposition.EscalateToStartBlocker(warningMessage = message, reportDefect = null),
                resolveOverlayActionOutcome(Result.success(result))
            )
        }
    }

    @Test
    fun timeoutEscalatesWithTimeoutCopyAndNoDefect() {
        val timeout = runBlocking {
            runCatching { withTimeout(1) { delay(1_000) } }.exceptionOrNull()
        }
        check(timeout is TimeoutCancellationException)

        assertEquals(
            OverlayActionDisposition.EscalateToStartBlocker(
                warningMessage = "Action timed out. Refresh or check Todoist before retrying.",
                reportDefect = null
            ),
            resolveOverlayActionOutcome(Result.failure(timeout))
        )
    }

    @Test
    fun unexpectedErrorEscalatesWithItsMessageAndReportsTheDefect() {
        val error = IllegalStateException("boom")

        val disposition = resolveOverlayActionOutcome(Result.failure(error))

        disposition as OverlayActionDisposition.EscalateToStartBlocker
        assertEquals("boom", disposition.warningMessage)
        assertSame(error, disposition.reportDefect)
    }

    @Test
    fun unexpectedErrorWithoutMessageUsesFallbackCopy() {
        val error = IllegalStateException()

        val disposition = resolveOverlayActionOutcome(Result.failure(error))

        disposition as OverlayActionDisposition.EscalateToStartBlocker
        assertEquals("Internal error. Refresh or check Todoist.", disposition.warningMessage)
    }
}
