package app.buzer.shared.state

import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthLevel
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotReducerTest {
    @Test
    fun checklistChildrenNeverBecomeIndependentTopLevelReducerTasks() {
        val root = task("root", CommitmentState.start_blocker_active).copy(
            checklistItems = listOf(
                ChecklistItem(
                    id = "child-1",
                    title = "Confirm notes",
                    checked = false,
                    order = 1,
                    childRevision = "r:2"
                )
            )
        )

        val result = SnapshotReducer.reduce(
            TasksSnapshotResponse(
                scheduleVersion = 7,
                serverTime = "2026-05-08T07:00:00Z",
                tasks = listOf(root),
                health = emptyList(),
                snapshotGeneration = 1
            )
        )

        assertEquals(listOf("root"), result.tasks.map { it.id })
        assertEquals(emptyList(), result.invalidTasks.map { it.id })
    }

    @Test
    fun morningReviewIncludesUnsupportedRoots() {
        val snapshot = TasksSnapshotResponse(
            scheduleVersion = 8,
            serverTime = "2026-05-08T07:00:00Z",
            tasks = listOf(
                task("unsupported", CommitmentState.planned, hierarchyStatus = HierarchyStatus.unsupported)
            ),
            health = emptyList(),
            snapshotGeneration = 3
        )

        val result = SnapshotReducer.reduce(snapshot)

        assertEquals(3L, result.snapshotGeneration)
        assertEquals(listOf("unsupported"), result.invalidTasks.map { it.id })
    }

    @Test
    fun filtersTerminalFromInvalidTasksExcludesDeletedFromTasksAndExtractsHealthWarnings() {
        val snapshot = TasksSnapshotResponse(
            scheduleVersion = 7,
            serverTime = "2026-05-08T07:00:00Z",
            tasks = listOf(
                task("invalid", CommitmentState.invalid, validationErrors = listOf("duration_required")),
                task("completed-invalid", CommitmentState.completed, validationErrors = listOf("duration_required")),
                task("deleted", CommitmentState.deleted, isDeleted = true),
                task("start", CommitmentState.start_blocker_active),
                task("started", CommitmentState.started),
                task("later", CommitmentState.planned, startAt = "2026-05-08T15:00:00Z"),
                task("tomorrow", CommitmentState.planned, startAt = "2026-05-09T08:00:00Z")
            ),
            health = listOf(
                HealthStatus("backend", HealthLevel.ok, "Backend OK"),
                HealthStatus("whatsapp", HealthLevel.warning, "WhatsApp disconnected")
            ),
            snapshotGeneration = 12
        )

        val result = SnapshotReducer.reduce(snapshot)

        assertEquals(7L, result.scheduleVersion)
        assertEquals(12L, result.snapshotGeneration)
        assertEquals(listOf("invalid"), result.invalidTasks.map { it.id })
        assertEquals(
            listOf("invalid", "completed-invalid", "start", "started", "later", "tomorrow"),
            result.tasks.map { it.id }
        )
        assertEquals(listOf("whatsapp"), result.healthWarnings.map { it.key })
    }

    private fun task(
        id: String,
        state: CommitmentState,
        startAt: String? = "2026-05-08T06:30:00Z",
        validationErrors: List<String> = emptyList(),
        hierarchyStatus: HierarchyStatus = HierarchyStatus.valid,
        occurrenceKey: String = "",
        isDeleted: Boolean = false
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
            isDeleted = isDeleted,
            validationErrors = validationErrors,
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-05-08T06:00:00Z",
            hierarchyStatus = hierarchyStatus,
            occurrenceKey = occurrenceKey
        )
}
