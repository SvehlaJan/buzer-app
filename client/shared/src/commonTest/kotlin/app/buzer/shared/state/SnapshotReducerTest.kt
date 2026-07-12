package app.buzer.shared.state

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthLevel
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotReducerTest {
    @Test
    fun separatesInvalidActiveAndTodayTasks() {
        val snapshot = TasksSnapshotResponse(
            scheduleVersion = 7,
            serverTime = "2026-05-08T07:00:00Z",
            tasks = listOf(
                task("invalid", CommitmentState.invalid, validationErrors = listOf("duration_required")),
                task("start", CommitmentState.start_blocker_active),
                task("started", CommitmentState.started),
                task("later", CommitmentState.planned, startAt = "2026-05-08T15:00:00Z"),
                task("tomorrow", CommitmentState.planned, startAt = "2026-05-09T08:00:00Z")
            ),
            health = listOf(
                HealthStatus("backend", HealthLevel.ok, "Backend OK"),
                HealthStatus("whatsapp", HealthLevel.warning, "WhatsApp disconnected")
            )
        )

        val result = SnapshotReducer.reduce(snapshot)

        assertEquals(7, result.scheduleVersion)
        assertEquals(listOf("invalid"), result.invalidTasks.map { it.id })
        assertEquals(listOf("start"), result.activeBlockers.map { it.id })
        assertEquals(listOf("started"), result.inProgressTasks.map { it.id })
        assertEquals(listOf("later"), result.startingToday.map { it.id })
        assertEquals(listOf("whatsapp"), result.healthWarnings.map { it.key })
    }

    private fun task(
        id: String,
        state: CommitmentState,
        startAt: String? = "2026-05-08T06:30:00Z",
        validationErrors: List<String> = emptyList()
    ): TaskSnapshotItem =
        TaskSnapshotItem(
            id = id,
            todoistTaskId = "todoist-$id",
            title = "Task $id",
            projectId = null,
            assigneeId = "self",
            creatorId = "self",
            dueDatetime = startAt,
            durationMinutes = 30,
            startAt = startAt,
            state = state,
            isAssignedToUser = true,
            isCreatedByUser = true,
            isCompleted = false,
            isDeleted = false,
            validationErrors = validationErrors,
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-05-08T06:00:00Z"
        )
}
