package app.buzer.android.enforcement.overlay

import android.content.Intent
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.EnforcementCoordinator

/**
 * Describes a blocker the overlay service should display. Built from the same alarm extras the
 * blocker activities consume (task id + alarm kind). Only blocker kinds are valid:
 * PRE_START warnings, unknown kinds, and task blockers without a task id all yield null.
 */
data class OverlayBlockerRequest(
    val kind: AlarmKind,
    val taskId: String
) {
    fun writeTo(intent: Intent): Intent =
        intent.apply {
            putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
            putExtra(AlarmIntentFactory.EXTRA_ALARM_KIND, kind.name)
        }

    companion object {
        fun fromExtras(taskId: String?, kindName: String?): OverlayBlockerRequest? {
            val kind = kindName?.let { runCatching { AlarmKind.valueOf(it) }.getOrNull() } ?: return null
            return when (kind) {
                AlarmKind.START_BLOCKER,
                AlarmKind.DEADLINE_BLOCKER -> taskId?.let { OverlayBlockerRequest(kind, it) }
                AlarmKind.MORNING_REVIEW ->
                    OverlayBlockerRequest(kind, taskId ?: EnforcementCoordinator.MORNING_REVIEW_TASK_ID)
                AlarmKind.PRE_START_WARNING -> null
            }
        }

        fun fromIntent(intent: Intent): OverlayBlockerRequest? =
            fromExtras(
                taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID),
                kindName = intent.getStringExtra(AlarmIntentFactory.EXTRA_ALARM_KIND)
            )
    }
}
