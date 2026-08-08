package app.buzer.android.ui

import app.buzer.android.ui.screens.MorningReviewBlockingCard
import app.buzer.android.ui.screens.MorningReviewPhase
import app.buzer.android.ui.screens.MorningReviewRetryTarget
import app.buzer.android.ui.screens.buildMorningReviewScreenState
import app.buzer.android.ui.screens.shouldCloseMorningReviewEditor
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
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
    fun editorSelectionClosesOnlyWhenAuthorityRemovesOrChangesIdentity() {
        val opened = task("edit").copy(occurrenceKey = "one", taskRevision = "r1")
        assertFalse(shouldCloseMorningReviewEditor(opened, opened.copy()))
        assertTrue(shouldCloseMorningReviewEditor(opened, null))
        assertTrue(shouldCloseMorningReviewEditor(opened, opened.copy(occurrenceKey = "two")))
        assertTrue(shouldCloseMorningReviewEditor(opened, opened.copy(taskRevision = "r2")))
    }

    @Test
    fun cachedSnapshotRemainsVisibleDuringLoadingAndRefreshFailure() {
        val cached = snapshot(task("today", due = "2026-08-02T12:00:00Z", duration = 30))

        val loading = buildMorningReviewScreenState(cached, refreshInFlight = true, now = NOW, zoneId = ZONE)
        val failed = buildMorningReviewScreenState(cached, refreshError = "Offline", now = NOW, zoneId = ZONE)

        assertEquals(MorningReviewPhase.Summary, loading.phase)
        assertTrue(loading.refreshing)
        assertEquals(listOf("today"), loading.todayRows.map { it.taskId })
        assertEquals("Refresh failed. Showing last known tasks: Offline", failed.warning)
        assertEquals(listOf(MorningReviewRetryTarget.Refresh), failed.retryNotices.map { it.target })
        assertEquals(listOf("today"), failed.todayRows.map { it.taskId })
    }

    @Test
    fun cachedSnapshotRemainsVisibleDuringInitialLoadingFlag() {
        val state = buildMorningReviewScreenState(
            snapshot(task("today", due = "2026-08-02T12:00:00Z", duration = 30)),
            loading = true,
            now = NOW,
            zoneId = ZONE
        )

        assertEquals(MorningReviewPhase.Summary, state.phase)
        assertEquals(listOf("today"), state.todayRows.map { it.taskId })
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
        assertEquals("Refresh failed. Task data is unavailable: Backend unavailable", state.warning)
        assertEquals(listOf(MorningReviewRetryTarget.Refresh), state.retryNotices.map { it.target })
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
    fun authoritativeUnsupportedInvalidOwnerUsesRepairAndDisablesStartDay() {
        val unsupported = task(
            "unsupported",
            hierarchyStatus = HierarchyStatus.unsupported,
            validationErrors = listOf("missing_due_datetime", "missing_duration"),
            description = "  Bring the form.  "
        )
        val state = buildMorningReviewScreenState(snapshotWithInvalid(unsupported), now = NOW, zoneId = ZONE)

        val card = assertIs<MorningReviewBlockingCard.Repair>(state.blockingCards.single())
        assertEquals("No start time or duration set", card.problemText)
        assertEquals("Bring the form.", card.description)
        assertFalse(state.startDayEnabled)
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
    fun todayIncludesSameDayOverdueAndCrossMidnightCurrentEntries() {
        val state = buildMorningReviewScreenState(
            snapshot(
                task("same-day-overdue", due = "2026-08-02T08:00:00Z", duration = 30),
                task("cross-midnight-current", due = "2026-08-01T21:30:00Z", duration = 780)
            ),
            now = NOW,
            zoneId = ZONE
        )

        assertEquals(listOf("same-day-overdue"), state.overdueRows.map { it.taskId })
        assertEquals(listOf("cross-midnight-current", "same-day-overdue"), state.todayRows.map { it.taskId })
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
        assertTrue(state.todayRows.all { !it.doneEnabled })
    }

    @Test
    fun actionInFlightRetainsBlockingAndNoDataContentWhileDisablingGlobalActions() {
        val blocking = buildMorningReviewScreenState(
            snapshot(task("invalid", state = CommitmentState.invalid)),
            actionInFlight = true,
            now = NOW,
            zoneId = ZONE
        )
        val noData = buildMorningReviewScreenState(
            snapshot = null,
            refreshError = "Offline",
            actionInFlight = true,
            now = NOW,
            zoneId = ZONE
        )

        assertEquals(MorningReviewPhase.Blocking, blocking.phase)
        assertEquals(listOf("invalid"), blocking.blockingCards.map { it.taskId })
        assertFalse(blocking.snoozeEnabled)
        assertEquals(MorningReviewPhase.NoData, noData.phase)
        assertEquals("Refresh failed. Task data is unavailable: Offline", noData.warning)
        assertFalse(noData.startDayEnabled)
        assertFalse(noData.snoozeEnabled)
    }

    @Test
    fun retryNoticesPreserveRefreshAndActionTargetsWithoutCachedSnapshot() {
        val refresh = buildMorningReviewScreenState(
            snapshot = null,
            refreshError = "Offline",
            now = NOW,
            zoneId = ZONE
        )
        val action = buildMorningReviewScreenState(
            snapshot = null,
            actionError = "Could not start day",
            now = NOW,
            zoneId = ZONE
        )
        val both = buildMorningReviewScreenState(
            snapshot = null,
            refreshError = "Offline",
            actionError = "Could not start day",
            now = NOW,
            zoneId = ZONE
        )

        assertEquals(listOf(MorningReviewRetryTarget.Refresh), refresh.retryNotices.map { it.target })
        assertEquals("Refresh failed. Task data is unavailable: Offline", refresh.retryNotices.single().message)
        assertEquals(listOf(MorningReviewRetryTarget.Action), action.retryNotices.map { it.target })
        assertEquals("Action failed: Could not start day", action.retryNotices.single().message)
        assertEquals(listOf(MorningReviewRetryTarget.Refresh, MorningReviewRetryTarget.Action), both.retryNotices.map { it.target })
        assertEquals(
            "Refresh failed. Task data is unavailable: Offline Action failed: Could not start day",
            both.warning
        )
    }

    @Test
    fun retryNoticesUseCachedDataWording() {
        val state = buildMorningReviewScreenState(
            snapshot(task("today", due = "2026-08-02T12:00:00Z", duration = 30)),
            refreshError = "Offline",
            actionError = "Could not start day",
            now = NOW,
            zoneId = ZONE
        )

        assertEquals(
            listOf(
                "Refresh failed. Showing last known tasks: Offline",
                "Action failed: Could not start day"
            ),
            state.retryNotices.map { it.message }
        )
    }

    @Test
    fun reviewRowsClarifyCrossMidnightAndDstOffsetChanges() {
        val crossMidnight = buildMorningReviewScreenState(
            snapshot(task("overnight", due = "2026-08-02T20:30:00Z", duration = 240)),
            now = NOW,
            zoneId = ZONE
        )
        val fallback = buildMorningReviewScreenState(
            snapshot(task("fallback", due = "2026-10-25T00:30:00Z", duration = 120)),
            now = Instant.parse("2026-10-25T00:00:00Z"),
            zoneId = ZONE
        )

        assertEquals("22:30-02:30 (+1d)", crossMidnight.todayRows.single().timeLabel)
        assertEquals("02:30 (UTC+02:00)-03:30 (UTC+01:00)", fallback.todayRows.single().timeLabel)
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

    private fun snapshotWithInvalid(vararg items: TaskSnapshotItem) = SnapshotViewState(
        scheduleVersion = 1,
        serverTime = "2026-08-02T10:00:00Z",
        invalidTasks = items.toList(),
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
        isCompleted: Boolean = false,
        hierarchyStatus: HierarchyStatus = HierarchyStatus.valid,
        validationErrors: List<String> = emptyList(),
        description: String? = null
    ) = TaskSnapshotItem(
        id = id,
        todoistTaskId = "todoist-$id",
        title = id,
        description = description,
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
        validationErrors = validationErrors,
        startedAt = null,
        startDismissCount = 0,
        deadlineExtensionCount = 0,
        escalationSentAt = null,
        updatedAt = "2026-08-02T09:00:00Z",
        checklistItems = listOf(ChecklistItem("child", "Child", checked = false, order = 0, childRevision = "r1")),
        checkedCount = 0,
        totalCount = 1,
        hierarchyStatus = hierarchyStatus,
        reconciliationStatus = reconciliationStatus
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-02T10:00:00Z")
        val ZONE: ZoneId = ZoneId.of("Europe/Bratislava")
    }
}
