package app.buzer.shared.state

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.HealthLevel
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.ReconciliationStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
        assertEquals(listOf("root"), result.activeBlockers.map { it.id })
        assertEquals(emptyList(), result.invalidTasks.map { it.id })
    }

    @Test
    fun morningReviewIncludesUnsupportedAndReconciliationPendingRoots() {
        val snapshot = TasksSnapshotResponse(
            scheduleVersion = 8,
            serverTime = "2026-05-08T07:00:00Z",
            tasks = listOf(
                task("unsupported", CommitmentState.planned, hierarchyStatus = HierarchyStatus.unsupported),
                task("pending", CommitmentState.planned, reconciliationStatus = ReconciliationStatus.pending)
            ),
            health = emptyList(),
            snapshotGeneration = 1
        )

        val result = SnapshotReducer.reduce(snapshot)

        assertEquals(listOf("unsupported", "pending"), result.invalidTasks.map { it.id })
    }

    @Test
    fun authoritativeStaleSnapshotReplacesCachedTasksWithoutDiscardingCallerDraft() {
        val draft = CallerDraft(title = "Keep editing this", description = "Unsaved detail")
        val cached = SnapshotWithCallerDraft(
            viewState = SnapshotReducer.reduce(
                TasksSnapshotResponse(
                    scheduleVersion = 1,
                    serverTime = "2026-05-08T07:00:00Z",
                    tasks = listOf(task("task", CommitmentState.planned, occurrenceKey = "old-occurrence")),
                    health = emptyList(),
                    snapshotGeneration = 1
                )
            ),
            callerDraft = draft
        )
        val authoritative = TasksSnapshotResponse(
            scheduleVersion = 2,
            serverTime = "2026-05-08T07:01:00Z",
            tasks = listOf(task("task", CommitmentState.planned, occurrenceKey = "next-occurrence")),
            health = emptyList(),
            snapshotGeneration = 2
        )

        val replaced = SnapshotReducer.replaceAuthoritative(cached, authoritative)

        assertEquals(listOf("task"), replaced.viewState.tasks.map { it.id })
        assertEquals("next-occurrence", replaced.viewState.tasks.single().occurrenceKey)
        assertSame(draft, replaced.callerDraft)
    }

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
            ),
            snapshotGeneration = 1
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
        validationErrors: List<String> = emptyList(),
        hierarchyStatus: HierarchyStatus = HierarchyStatus.valid,
        reconciliationStatus: ReconciliationStatus = ReconciliationStatus.none,
        occurrenceKey: String = ""
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
            updatedAt = "2026-05-08T06:00:00Z",
            hierarchyStatus = hierarchyStatus,
            reconciliationStatus = reconciliationStatus,
            occurrenceKey = occurrenceKey
        )

    private data class CallerDraft(
        val title: String,
        val description: String
    )
}
