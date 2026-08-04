package app.buzer.android.ui

import app.buzer.android.ui.screens.MorningReviewBlockingCard
import app.buzer.android.ui.screens.MorningReviewPhase
import app.buzer.android.ui.screens.buildMorningReviewScreenState
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.ReconciliationStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MorningReviewScreenStateTest {
    @Test
    fun cachedSnapshotRemainsVisibleDuringLoadingAndRefreshFailure() {
        val cached = snapshot(task("today", due = "2026-08-02T12:00:00Z", duration = 30))

        val loading = buildMorningReviewScreenState(cached, refreshInFlight = true, now = NOW, zoneId = ZONE)
        val failed = buildMorningReviewScreenState(cached, refreshError = "Offline", now = NOW, zoneId = ZONE)

        assertEquals(MorningReviewPhase.Summary, loading.phase)
        assertTrue(loading.refreshing)
        assertEquals(listOf("today"), loading.todayRows.map { it.taskId })
        assertEquals("Refresh failed. Showing last known tasks.", failed.warning)
        assertEquals(listOf("today"), failed.todayRows.map { it.taskId })
    }

    @Test
    fun noDataShowsErrorAndAllowsNormalAcknowledgement() {
        val state = buildMorningReviewScreenState(
            snapshot = null,
            refreshInFlight = false,
            refreshError = "Backend unavailable",
            now = NOW,
            zoneId = ZONE
        )

        assertEquals(MorningReviewPhase.NoData, state.phase)
        assertEquals("Backend unavailable", state.warning)
        assertEquals("Start day without data", state.startDayLabel)
        assertTrue(state.startDayEnabled)
        assertTrue(state.snoozeEnabled)
    }

    @Test
    fun noCachedDataLoadsBeforeAResult() {
        val state = buildMorningReviewScreenState(null, refreshInFlight = true, now = NOW, zoneId = ZONE)

        assertEquals(MorningReviewPhase.Loading, state.phase)
        assertFalse(state.startDayEnabled)
        assertFalse(state.snoozeEnabled)
    }

    @Test
    fun invalidOwnerGetsRepairAndPendingOwnerGetsSyncPendingRetry() {
        val state = buildMorningReviewScreenState(
            snapshot(
                task("invalid", state = CommitmentState.invalid),
                task("pending", reconciliationStatus = ReconciliationStatus.pending)
            ),
            now = NOW,
            zoneId = ZONE
        )

        assertEquals(MorningReviewPhase.Blocking, state.phase)
        assertIs<MorningReviewBlockingCard.Repair>(state.blockingCards[0])
        assertIs<MorningReviewBlockingCard.SyncPending>(state.blockingCards[1])
        assertEquals(listOf("invalid", "pending"), state.blockingCards.map { it.taskId })
    }

    @Test
    fun unassignedAndAssignedOtherTasksNeverBlockOrEnterSummary() {
        val state = buildMorningReviewScreenState(
            snapshot(
                task("unassigned", assignedToUser = false, assigneeId = null, state = CommitmentState.invalid),
                task("other", assignedToUser = false, assigneeId = "other", reconciliationStatus = ReconciliationStatus.pending)
            ),
            now = NOW,
            zoneId = ZONE
        )

        assertEquals(MorningReviewPhase.Summary, state.phase)
        assertTrue(state.blockingCards.isEmpty())
        assertTrue(state.overdueRows.isEmpty())
        assertTrue(state.todayRows.isEmpty())
        assertTrue(state.startDayEnabled)
    }

    @Test
    fun blockingHidesSummaryAndDisablesStartDay() {
        val state = buildMorningReviewScreenState(
            snapshot(
                task("invalid", state = CommitmentState.invalid),
                task("overdue", due = "2026-08-02T08:00:00Z", duration = 30),
                task("today", due = "2026-08-02T12:00:00Z", duration = 30)
            ),
            now = NOW,
            zoneId = ZONE
        )

        assertEquals(MorningReviewPhase.Blocking, state.phase)
        assertTrue(state.overdueRows.isEmpty())
        assertTrue(state.todayRows.isEmpty())
        assertFalse(state.startDayEnabled)
        assertTrue(state.snoozeEnabled)
    }

    @Test
    fun clearReviewShowsAllOverdueNewestFirstAndDoesNotBlockStartDay() {
        val state = buildMorningReviewScreenState(
            snapshot(
                task("old", due = "2026-08-02T07:00:00Z", duration = 30),
                task("new", due = "2026-08-02T09:00:00Z", duration = 30)
            ),
            now = NOW,
            zoneId = ZONE
        )

        assertEquals(MorningReviewPhase.Summary, state.phase)
        assertEquals(listOf("new", "old"), state.overdueRows.map { it.taskId })
        assertTrue(state.startDayEnabled)
    }

    @Test
    fun todayContainsCurrentAndTodaysStartsInStartEndIdOrderOnly() {
        val state = buildMorningReviewScreenState(
            snapshot(
                task("current", due = "2026-08-02T09:30:00Z", duration = 90),
                task("today-b", due = "2026-08-02T12:00:00Z", duration = 60),
                task("today-a", due = "2026-08-02T12:00:00Z", duration = 30),
                task("tomorrow", due = "2026-08-03T09:00:00Z", duration = 30),
                task("completed", due = "2026-08-02T13:00:00Z", duration = 30, isCompleted = true)
            ),
            now = NOW,
            zoneId = ZONE
        )

        assertEquals(listOf("current", "today-a", "today-b"), state.todayRows.map { it.taskId })
        assertTrue(state.todayRows.none { it.taskId == "tomorrow" || it.taskId == "completed" })
    }

    @Test
    fun taskFreeDayEnablesStartAndOffersOneGlobalSnooze() {
        val state = buildMorningReviewScreenState(snapshot(), now = NOW, zoneId = ZONE)

        assertEquals(MorningReviewPhase.Summary, state.phase)
        assertEquals("Start day", state.startDayLabel)
        assertTrue(state.startDayEnabled)
        assertTrue(state.snoozeEnabled)
        assertTrue(state.blockingCards.isEmpty())
    }

    @Test
    fun actionInFlightDisablesDuplicateActionsButKeepsCachedContent() {
        val state = buildMorningReviewScreenState(
            snapshot(task("today", due = "2026-08-02T12:00:00Z", duration = 30)),
            actionInFlight = true,
            now = NOW,
            zoneId = ZONE
        )

        assertEquals(MorningReviewPhase.Summary, state.phase)
        assertTrue(state.actionInFlight)
        assertFalse(state.startDayEnabled)
        assertFalse(state.snoozeEnabled)
        assertEquals(listOf("today"), state.todayRows.map { it.taskId })
    }

    private fun snapshot(vararg items: TaskSnapshotItem) = SnapshotViewState(
        scheduleVersion = 1,
        serverTime = "2026-08-02T10:00:00Z",
        invalidTasks = emptyList(),
        activeBlockers = emptyList(),
        inProgressTasks = emptyList(),
        startingToday = emptyList(),
        healthWarnings = emptyList(),
        tasks = items.toList()
    )

    private fun task(
        id: String,
        assignedToUser: Boolean = true,
        assigneeId: String? = "user-1",
        due: String? = null,
        duration: Int? = null,
        state: CommitmentState = CommitmentState.planned,
        reconciliationStatus: ReconciliationStatus = ReconciliationStatus.none,
        isCompleted: Boolean = false
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
        isDeleted = false,
        validationErrors = emptyList(),
        startedAt = null,
        startDismissCount = 0,
        deadlineExtensionCount = 0,
        escalationSentAt = null,
        updatedAt = "2026-08-02T09:00:00Z",
        checklistItems = listOf(ChecklistItem("child", "Child", checked = false, order = 0, childRevision = "r1")),
        checkedCount = 0,
        totalCount = 1,
        reconciliationStatus = reconciliationStatus
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-02T10:00:00Z")
        val ZONE: ZoneId = ZoneId.of("Europe/Bratislava")
    }
}
