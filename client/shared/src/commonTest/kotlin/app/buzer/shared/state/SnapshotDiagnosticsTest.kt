package app.buzer.shared.state

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthLevel
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.ReconciliationStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SnapshotDiagnosticsTest {
    @Test
    fun describesSnapshotWithCountsAndWithoutTaskTitles() {
        val snapshot = TasksSnapshotResponse(
            scheduleVersion = 7,
            serverTime = "2026-06-02T20:20:00Z",
            tasks = listOf(
                task("invalid-task", "Private invalid title", CommitmentState.invalid),
                task("planned-task", "Private planned title", CommitmentState.planned, hierarchyStatus = HierarchyStatus.unsupported),
                task("completed-task", "Private completed title", CommitmentState.completed, isCompleted = true)
            ),
            health = listOf(
                HealthStatus("backend", HealthLevel.ok, "Backend OK"),
                HealthStatus("whatsapp", HealthLevel.warning, "WhatsApp disconnected")
            )
        )

        val description = SnapshotDiagnostics.describe(snapshot)

        assertEquals(
            "scheduleVersion=7 serverTime=2026-06-02T20:20:00Z tasks=3 visible=2 invalid=2 active=0 startingToday=1 completed=1 deleted=0 unsupported=1 pending=0 healthWarnings=1",
            description
        )
        assertFalse(description.contains("Private"))
    }

    private fun task(
        id: String,
        title: String,
        state: CommitmentState,
        isCompleted: Boolean = false,
        hierarchyStatus: HierarchyStatus = HierarchyStatus.valid,
        reconciliationStatus: ReconciliationStatus = ReconciliationStatus.none
    ): TaskSnapshotItem =
        TaskSnapshotItem(
            id = id,
            todoistTaskId = "todoist-$id",
            title = title,
            projectId = null,
            assigneeId = "self",
            creatorId = "self",
            dueDatetime = "2026-06-02T22:00:00Z",
            durationMinutes = 60,
            startAt = "2026-06-02T22:00:00Z",
            state = state,
            isAssignedToUser = true,
            isCreatedByUser = true,
            isCompleted = isCompleted,
            isDeleted = false,
            validationErrors = if (state == CommitmentState.invalid) listOf("duration_required") else emptyList(),
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-06-02T20:00:00Z",
            hierarchyStatus = hierarchyStatus,
            reconciliationStatus = reconciliationStatus
        )
}
