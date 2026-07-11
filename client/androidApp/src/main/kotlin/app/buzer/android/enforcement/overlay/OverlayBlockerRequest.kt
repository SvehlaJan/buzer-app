package app.buzer.android.enforcement.overlay

import android.content.Intent
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.ui.blockers.BlockerKind

/**
 * Describes a blocker the overlay service should display. Built from the same alarm extras the
 * blocker activities consume, then enriched from the latest cached task snapshot before rendering.
 * Task 9 intentionally supports only the compact Start overlay; other blocker kinds continue to use
 * the full-screen notification/activity path.
 */
data class OverlayBlockerRequest(
    val kind: AlarmKind,
    val taskId: String,
    val blockerKind: BlockerKind = BlockerKind.OverlayStart,
    val title: String? = null,
    val description: String? = null,
    val dueDatetime: String? = null,
    val durationMinutes: Int? = null,
    val startAt: String? = null
) {
    fun writeTo(intent: Intent): Intent =
        intent.apply {
            putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
            putExtra(AlarmIntentFactory.EXTRA_ALARM_KIND, kind.name)
            title?.let { putExtra(EXTRA_TITLE, it) }
            description?.let { putExtra(EXTRA_DESCRIPTION, it) }
            dueDatetime?.let { putExtra(EXTRA_DUE_DATETIME, it) }
            durationMinutes?.let { putExtra(EXTRA_DURATION_MINUTES, it) }
            startAt?.let { putExtra(EXTRA_START_AT, it) }
        }

    fun withTask(task: TaskSnapshotItem?): OverlayBlockerRequest {
        if (task == null) return this
        return copy(
            title = task.title,
            description = task.description,
            dueDatetime = task.dueDatetime,
            durationMinutes = task.durationMinutes,
            startAt = task.startAt
        )
    }

    fun toTaskSnapshotItem(): TaskSnapshotItem =
        TaskSnapshotItem(
            id = taskId,
            todoistTaskId = taskId,
            title = title.orEmpty(),
            description = description,
            projectId = null,
            assigneeId = null,
            creatorId = null,
            dueDatetime = dueDatetime,
            durationMinutes = durationMinutes,
            startAt = startAt,
            state = CommitmentState.start_blocker_active,
            isAssignedToUser = true,
            isCreatedByUser = false,
            isCompleted = false,
            isDeleted = false,
            validationErrors = emptyList(),
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = ""
        )

    companion object {
        const val EXTRA_TITLE = "app.buzer.extra.OVERLAY_TASK_TITLE"
        const val EXTRA_DESCRIPTION = "app.buzer.extra.OVERLAY_TASK_DESCRIPTION"
        const val EXTRA_DUE_DATETIME = "app.buzer.extra.OVERLAY_DUE_DATETIME"
        const val EXTRA_DURATION_MINUTES = "app.buzer.extra.OVERLAY_DURATION_MINUTES"
        const val EXTRA_START_AT = "app.buzer.extra.OVERLAY_START_AT"

        fun fromExtras(
            taskId: String?,
            kindName: String?,
            title: String? = null,
            description: String? = null,
            dueDatetime: String? = null,
            durationMinutes: Int? = null,
            startAt: String? = null
        ): OverlayBlockerRequest? {
            val kind = kindName?.let { runCatching { AlarmKind.valueOf(it) }.getOrNull() } ?: return null
            return when (kind) {
                AlarmKind.START_BLOCKER -> taskId?.let {
                    OverlayBlockerRequest(
                        kind = kind,
                        taskId = it,
                        title = title,
                        description = description,
                        dueDatetime = dueDatetime,
                        durationMinutes = durationMinutes,
                        startAt = startAt
                    )
                }
                AlarmKind.DEADLINE_BLOCKER,
                AlarmKind.MORNING_REVIEW,
                AlarmKind.PRE_START_WARNING -> null
            }
        }

        fun fromIntent(intent: Intent): OverlayBlockerRequest? =
            fromExtras(
                taskId = intent.getStringExtra(AlarmIntentFactory.EXTRA_TASK_ID),
                kindName = intent.getStringExtra(AlarmIntentFactory.EXTRA_ALARM_KIND),
                title = intent.getStringExtra(EXTRA_TITLE),
                description = intent.getStringExtra(EXTRA_DESCRIPTION),
                dueDatetime = intent.getStringExtra(EXTRA_DUE_DATETIME),
                durationMinutes = intent
                    .takeIf { it.hasExtra(EXTRA_DURATION_MINUTES) }
                    ?.getIntExtra(EXTRA_DURATION_MINUTES, 0),
                startAt = intent.getStringExtra(EXTRA_START_AT)
            )
    }
}
