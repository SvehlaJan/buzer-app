package app.buzer.android.ui.agenda

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OwnerAgendaSelectorTest {
    @Test
    fun ownerTimedTasksClassifyIntoOverdueCurrentAndFutureWithHalfOpenBoundaries() {
        val snapshot = snapshot(
            task("overdue", due = "2026-08-02T09:00:00Z", duration = 30),
            task("current", due = "2026-08-02T10:00:00Z", duration = 60),
            task("future", due = "2026-08-02T12:00:00Z", duration = 30)
        )

        val atStart = selectOwnerAgenda(snapshot, now = Instant.parse("2026-08-02T10:00:00Z"), zoneId = ZONE)
        val atEnd = selectOwnerAgenda(snapshot, now = Instant.parse("2026-08-02T11:00:00Z"), zoneId = ZONE)

        assertEquals(listOf("overdue"), atStart.overdue.map { it.task.id })
        assertEquals(listOf("current"), atStart.current.map { it.task.id })
        assertEquals(listOf("future"), atStart.future.map { it.task.id })
        assertEquals(listOf("current", "overdue"), atEnd.overdue.map { it.task.id })
        assertTrue(atEnd.current.isEmpty())
    }

    @Test
    fun excludesUnassignedAndAssignedOtherTasks() {
        val snapshot = snapshot(
            task("owner", due = "2026-08-02T12:00:00Z", duration = 30),
            task("unassigned", assignedToUser = false, assigneeId = null, due = "2026-08-02T12:00:00Z", duration = 30),
            task("other", assignedToUser = false, assigneeId = "other", due = "2026-08-02T12:00:00Z", duration = 30)
        )

        val buckets = selectOwnerAgenda(snapshot, now = NOW, zoneId = ZONE)

        assertEquals(listOf("owner"), buckets.future.map { it.task.id })
        assertTrue(buckets.blocking.isEmpty())
    }

    @Test
    fun authoritativeInvalidProjectionOwnerTasksAreBlockingBeforeTimeClassification() {
        val projectionOnly = task("projection", due = "2026-08-02T12:00:00Z", duration = 30)
        val invalidTask = task("invalid", state = CommitmentState.invalid)
        val snapshot = snapshot(
            tasks = listOf(projectionOnly, invalidTask),
            invalidTasks = listOf(projectionOnly)
        )

        val buckets = selectOwnerAgenda(snapshot, now = NOW, zoneId = ZONE)

        assertEquals(listOf("invalid", "projection"), buckets.blocking.map { it.id })
        assertTrue(buckets.future.isEmpty())
    }

    @Test
    fun authoritativeUnsupportedInvalidOwnerIsBlockingButNeverTimed() {
        val unsupported = task(
            "unsupported-invalid",
            due = "2026-08-02T12:00:00Z",
            duration = 30,
            hierarchyStatus = HierarchyStatus.unsupported
        )

        val buckets = selectOwnerAgenda(
            snapshot(tasks = listOf(unsupported), invalidTasks = listOf(unsupported)),
            now = NOW,
            zoneId = ZONE
        )

        assertEquals(listOf("unsupported-invalid"), buckets.blocking.map { it.id })
        assertTrue(buckets.overdue.isEmpty())
        assertTrue(buckets.current.isEmpty())
        assertTrue(buckets.future.isEmpty())
    }

    @Test
    fun excludesCompletedDeletedUnsupportedAndMalformedOwnerTasks() {
        val snapshot = snapshot(
            task("completed", due = "2026-08-02T12:00:00Z", duration = 30, isCompleted = true),
            task("deleted", due = "2026-08-02T12:00:00Z", duration = 30, isDeleted = true),
            task("unsupported", due = "2026-08-02T12:00:00Z", duration = 30, hierarchyStatus = HierarchyStatus.unsupported),
            task("bad-time", due = "not-a-time", duration = 30),
            task("bad-duration", due = "2026-08-02T12:00:00Z", duration = 0),
            task("state-deleted", due = "2026-08-02T12:00:00Z", duration = 30, state = CommitmentState.deleted)
        )

        val buckets = selectOwnerAgenda(snapshot, now = NOW, zoneId = ZONE)

        assertTrue(buckets.blocking.isEmpty())
        assertTrue(buckets.overdue.isEmpty())
        assertTrue(buckets.current.isEmpty())
        assertTrue(buckets.future.isEmpty())
    }

    @Test
    fun ordersEachTimedBucketDeterministically() {
        val snapshot = snapshot(
            task("overdue-a", due = "2026-08-02T08:00:00Z", duration = 30),
            task("overdue-b", due = "2026-08-02T08:30:00Z", duration = 30),
            task("current-b", due = "2026-08-02T09:30:00Z", duration = 90),
            task("current-a", due = "2026-08-02T09:30:00Z", duration = 60),
            task("future-b", due = "2026-08-02T12:00:00Z", duration = 60),
            task("future-a", due = "2026-08-02T12:00:00Z", duration = 30)
        )

        val buckets = selectOwnerAgenda(snapshot, now = NOW, zoneId = ZONE)

        assertEquals(listOf("overdue-b", "overdue-a"), buckets.overdue.map { it.task.id })
        assertEquals(listOf("current-a", "current-b"), buckets.current.map { it.task.id })
        assertEquals(listOf("future-a", "future-b"), buckets.future.map { it.task.id })
    }

    @Test
    fun equalTimeEntriesUseStableTaskIdTieBreakers() {
        val snapshot = snapshot(
            task("current-b", due = "2026-08-02T09:00:00Z", duration = 120),
            task("current-a", due = "2026-08-02T09:00:00Z", duration = 120),
            task("future-b", due = "2026-08-02T12:00:00Z", duration = 30),
            task("future-a", due = "2026-08-02T12:00:00Z", duration = 30)
        )

        val buckets = selectOwnerAgenda(snapshot, now = NOW, zoneId = ZONE)

        assertEquals(listOf("current-a", "current-b"), buckets.current.map { it.task.id })
        assertEquals(listOf("future-a", "future-b"), buckets.future.map { it.task.id })
    }

    @Test
    fun excludesTimedTaskWhoseEndWouldOverflowInstantRange() {
        val buckets = selectOwnerAgenda(
            snapshot(task("extreme", due = Instant.MAX.toString(), duration = 1)),
            now = NOW,
            zoneId = ZONE
        )

        assertTrue(buckets.overdue.isEmpty())
        assertTrue(buckets.current.isEmpty())
        assertTrue(buckets.future.isEmpty())
    }

    private fun snapshot(
        vararg items: TaskSnapshotItem,
        tasks: List<TaskSnapshotItem> = items.toList(),
        invalidTasks: List<TaskSnapshotItem> = emptyList()
    ) = SnapshotViewState(
        scheduleVersion = 1,
        serverTime = "2026-08-02T10:00:00Z",
        invalidTasks = invalidTasks,
        activeBlockers = emptyList(),
        inProgressTasks = emptyList(),
        startingToday = emptyList(),
        healthWarnings = emptyList(),
        tasks = tasks
    )

    private fun task(
        id: String,
        assignedToUser: Boolean = true,
        assigneeId: String? = "user-1",
        due: String? = null,
        duration: Int? = null,
        state: CommitmentState = CommitmentState.planned,
        isCompleted: Boolean = false,
        isDeleted: Boolean = false,
        hierarchyStatus: HierarchyStatus = HierarchyStatus.valid
    ) = TaskSnapshotItem(
        id = id,
        todoistTaskId = "todoist-$id",
        title = id,
        projectId = "project",
        assigneeId = assigneeId,
        creatorId = "creator",
        dueDatetime = due,
        durationMinutes = duration,
        startAt = null,
        state = state,
        isAssignedToUser = assignedToUser,
        isCreatedByUser = true,
        isCompleted = isCompleted,
        isDeleted = isDeleted,
        validationErrors = emptyList(),
        startedAt = null,
        startDismissCount = 0,
        deadlineExtensionCount = 0,
        escalationSentAt = null,
        updatedAt = "2026-08-02T09:00:00Z",
        hierarchyStatus = hierarchyStatus
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-02T10:00:00Z")
        val ZONE: ZoneId = ZoneId.of("Europe/Bratislava")
    }
}
