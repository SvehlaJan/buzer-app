package app.buzer.shared.model

import kotlinx.serialization.Serializable

@Serializable
enum class CommitmentState {
    planned,
    pre_start_warning,
    start_blocker_active,
    started,
    deadline_blocker_active,
    completed,
    invalid,
    deleted
}

@Serializable
data class TaskSnapshotItem(
    val id: String,
    val todoistTaskId: String,
    val title: String,
    val projectId: String?,
    val assigneeId: String?,
    val creatorId: String?,
    val dueDatetime: String?,
    val durationMinutes: Int?,
    val startAt: String?,
    val state: CommitmentState,
    val isAssignedToUser: Boolean,
    val isCreatedByUser: Boolean,
    val isCompleted: Boolean,
    val isDeleted: Boolean,
    val validationErrors: List<String>,
    val startedAt: String?,
    val startDismissCount: Int,
    val deadlineExtensionCount: Int,
    val escalationSentAt: String?,
    val updatedAt: String
)

@Serializable
data class TasksSnapshotResponse(
    val scheduleVersion: Long,
    val serverTime: String,
    val tasks: List<TaskSnapshotItem>,
    val health: List<HealthStatus>
)

@Serializable
data class CreateTaskRequest(
    val title: String,
    val projectId: String?,
    val assigneeId: String,
    val dueDatetime: String?,
    val durationMinutes: Int?
)

@Serializable
data class RescheduleTaskRequest(
    val dueDatetime: String,
    val durationMinutes: Int
)
