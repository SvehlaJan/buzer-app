package app.buzer.android.morningreview

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.ReconciliationStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class BlockerPriorityResolverTest {
    private val now = Instant.parse("2026-08-03T08:00:00Z")

    @Test
    fun deadlineBeatsAnActiveStartBlocker() {
        val snapshot = snapshot(
            task(id = "start", start = "2026-08-03T07:00:00Z", durationMinutes = 120),
            task(id = "deadline", start = "2026-08-03T06:00:00Z", durationMinutes = 120)
        )

        assertEquals(ActiveBlockerPriority.Deadline, BlockerPriorityResolver.resolve(snapshot, now))
    }

    @Test
    fun activeUnstartedTaskProducesStartPriorityOverMorningReview() {
        val snapshot = snapshot(task(start = "2026-08-03T07:00:00Z", durationMinutes = 120))

        assertEquals(ActiveBlockerPriority.Start, BlockerPriorityResolver.resolve(snapshot, now))
    }

    @Test
    fun intervalEndIsDeadlineRatherThanStart() {
        val snapshot = snapshot(task(start = "2026-08-03T07:00:00Z", durationMinutes = 60))

        assertEquals(ActiveBlockerPriority.Deadline, BlockerPriorityResolver.resolve(snapshot, now))
    }

    @Test
    fun startedTasksDoNotProduceStartPriority() {
        val snapshot = snapshot(
            task(
                start = "2026-08-03T07:00:00Z",
                durationMinutes = 120,
                state = CommitmentState.started
            )
        )

        assertEquals(ActiveBlockerPriority.None, BlockerPriorityResolver.resolve(snapshot, now))
    }

    @Test
    fun invalidPendingUnassignedCompletedAndDeletedTasksNeverSuppressReview() {
        val snapshot = snapshot(
            task(id = "invalid", validationErrors = listOf("duration required")),
            task(id = "pending", reconciliationStatus = ReconciliationStatus.pending),
            task(id = "unassigned", isAssignedToUser = false),
            task(id = "completed", isCompleted = true),
            task(id = "deleted", isDeleted = true)
        )

        assertEquals(ActiveBlockerPriority.None, BlockerPriorityResolver.resolve(snapshot, now))
    }

    @Test
    fun authoritativeInvalidProjectionNeverSuppressesReview() {
        val task = task()
        val snapshot = snapshot(tasks = listOf(task), invalidTasks = listOf(task))

        assertEquals(ActiveBlockerPriority.None, BlockerPriorityResolver.resolve(snapshot, now))
    }

    @Test
    fun blankOccurrenceKeyNeverSuppressesReview() {
        val snapshot = snapshot(task(occurrenceKey = ""))

        assertEquals(ActiveBlockerPriority.None, BlockerPriorityResolver.resolve(snapshot, now))
    }

    private fun snapshot(
        vararg taskItems: TaskSnapshotItem,
        tasks: List<TaskSnapshotItem> = taskItems.toList(),
        invalidTasks: List<TaskSnapshotItem>? = null
    ): SnapshotViewState = SnapshotViewState(
        scheduleVersion = 1,
        serverTime = now.toString(),
        invalidTasks = invalidTasks ?: tasks.filter {
            it.state == CommitmentState.invalid ||
                it.validationErrors.isNotEmpty() ||
                it.hierarchyStatus != HierarchyStatus.valid ||
                it.reconciliationStatus == ReconciliationStatus.pending
        },
        activeBlockers = emptyList(),
        inProgressTasks = emptyList(),
        startingToday = emptyList(),
        healthWarnings = emptyList(),
        tasks = tasks
    )

    private fun task(
        id: String = "task",
        start: String = "2026-08-03T07:00:00Z",
        durationMinutes: Int = 120,
        state: CommitmentState = CommitmentState.planned,
        startedAt: String? = null,
        isAssignedToUser: Boolean = true,
        isCompleted: Boolean = false,
        isDeleted: Boolean = false,
        validationErrors: List<String> = emptyList(),
        reconciliationStatus: ReconciliationStatus = ReconciliationStatus.none,
        occurrenceKey: String = "occurrence-$id"
    ): TaskSnapshotItem = TaskSnapshotItem(
        id = id,
        todoistTaskId = "todoist-$id",
        title = "Task $id",
        projectId = "project",
        assigneeId = if (isAssignedToUser) "user" else null,
        creatorId = "user",
        dueDatetime = start,
        durationMinutes = durationMinutes,
        startAt = start,
        state = state,
        isAssignedToUser = isAssignedToUser,
        isCreatedByUser = true,
        isCompleted = isCompleted,
        isDeleted = isDeleted,
        validationErrors = validationErrors,
        startedAt = startedAt,
        startDismissCount = 0,
        deadlineExtensionCount = 0,
        escalationSentAt = null,
        updatedAt = now.toString(),
        occurrenceKey = occurrenceKey,
        hierarchyStatus = HierarchyStatus.valid,
        reconciliationStatus = reconciliationStatus
    )
}
