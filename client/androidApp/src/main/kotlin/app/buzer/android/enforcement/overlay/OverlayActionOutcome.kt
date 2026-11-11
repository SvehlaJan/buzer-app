package app.buzer.android.enforcement.overlay

import app.buzer.android.enforcement.CoordinatorActionResult
import kotlinx.coroutines.TimeoutCancellationException

/** What [OverlayBlockerService] does after a Start or Postpone action from the native overlay. */
internal sealed interface OverlayActionDisposition {
    /** The action landed; the overlay can go away. */
    data object Dismiss : OverlayActionDisposition

    /**
     * The action did not clearly land. The overlay hands off to `StartBlockerActivity`, which shows
     * [warningMessage] in its sticky warning banner. [reportDefect] is set only for unexpected errors.
     */
    data class EscalateToStartBlocker(
        val warningMessage: String,
        val reportDefect: Throwable?
    ) : OverlayActionDisposition
}

internal const val OVERLAY_ACTION_TIMEOUT_MESSAGE = "Action timed out. Refresh or check Todoist before retrying."
private const val OVERLAY_INTERNAL_ERROR_MESSAGE = "Internal error. Refresh or check Todoist."
private const val OVERLAY_ACTION_IN_FLIGHT_MESSAGE = "Action already in progress"

internal fun resolveOverlayActionOutcome(result: Result<CoordinatorActionResult>): OverlayActionDisposition =
    result.fold(
        onSuccess = { outcome ->
            when (outcome) {
                CoordinatorActionResult.Success -> OverlayActionDisposition.Dismiss
                is CoordinatorActionResult.Warning -> escalate(outcome.message)
                is CoordinatorActionResult.Conflict -> escalate(outcome.message)
                is CoordinatorActionResult.TransportFailure -> escalate(outcome.message)
                is CoordinatorActionResult.LocalReconciliationFailed -> escalate(outcome.message)
                CoordinatorActionResult.ChildActionInFlight -> escalate(OVERLAY_ACTION_IN_FLIGHT_MESSAGE)
            }
        },
        onFailure = { error ->
            if (error is TimeoutCancellationException) {
                escalate(OVERLAY_ACTION_TIMEOUT_MESSAGE)
            } else {
                OverlayActionDisposition.EscalateToStartBlocker(
                    warningMessage = error.message ?: OVERLAY_INTERNAL_ERROR_MESSAGE,
                    reportDefect = error
                )
            }
        }
    )

private fun escalate(message: String) =
    OverlayActionDisposition.EscalateToStartBlocker(warningMessage = message, reportDefect = null)
