package app.buzer.android.ui

import app.buzer.android.ui.screens.HomeContentState
import app.buzer.android.ui.screens.buildHomeScreenState
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthLevel
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.state.SnapshotViewState
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeScreenStateTest {
    @Test
    fun showsInitialLoadingBeforeFirstSnapshot() {
        val state = buildHomeScreenState(
            snapshot = null,
            refreshInFlight = true,
            refreshError = null
        )

        assertEquals(HomeContentState.Loading, state.contentState)
        assertEquals(null, state.statusMessage)
    }

    @Test
    fun showsBlockingErrorWhenRefreshFailsBeforeFirstSnapshot() {
        val state = buildHomeScreenState(
            snapshot = null,
            refreshInFlight = false,
            refreshError = "Backend unavailable"
        )

        assertEquals(HomeContentState.Error("Backend unavailable"), state.contentState)
        assertEquals(null, state.statusMessage)
    }

    @Test
    fun keepsExistingDataVisibleWhileRefreshing() {
        val snapshot = SnapshotViewState(
            scheduleVersion = 2,
            serverTime = "2026-05-08T07:00:00Z",
            invalidTasks = emptyList(),
            activeBlockers = emptyList(),
            inProgressTasks = emptyList(),
            startingToday = emptyList(),
            healthWarnings = emptyList()
        )

        val state = buildHomeScreenState(
            snapshot = snapshot,
            refreshInFlight = true,
            refreshError = null
        )

        assertEquals(HomeContentState.Ready, state.contentState)
        assertEquals("Refreshing...", state.statusMessage)
    }

    @Test
    fun keepsExistingDataVisibleWithStaleWarningAfterRefreshFailure() {
        val snapshot = SnapshotViewState(
            scheduleVersion = 2,
            serverTime = "2026-05-08T07:00:00Z",
            invalidTasks = emptyList(),
            activeBlockers = emptyList(),
            inProgressTasks = emptyList(),
            startingToday = emptyList(),
            healthWarnings = emptyList()
        )

        val state = buildHomeScreenState(
            snapshot = snapshot,
            refreshInFlight = false,
            refreshError = "Network request failed"
        )

        assertEquals(HomeContentState.Ready, state.contentState)
        assertEquals("Refresh failed. Showing last known data.", state.statusMessage)
    }

    @Test
    fun summarizesSnapshotCountsForHome() {
        val snapshot = SnapshotViewState(
            scheduleVersion = 2,
            serverTime = "2026-05-08T07:00:00Z",
            invalidTasks = listOf(task("invalid")),
            activeBlockers = listOf(task("start"), task("deadline")),
            inProgressTasks = emptyList(),
            startingToday = listOf(task("later")),
            healthWarnings = listOf(HealthStatus("whatsapp", HealthLevel.warning, "Disconnected"))
        )

        val state = buildHomeScreenState(snapshot)

        assertEquals("Today", state.headline)
        assertEquals(null, state.statusMessage)
        assertEquals(1, state.invalidCount)
        assertEquals(2, state.activeBlockerCount)
        assertEquals(1, state.startingTodayCount)
        assertEquals(1, state.healthWarningCount)
    }

    @Test
    fun exposesSoonestInProgressTaskForHome() {
        val snapshot = SnapshotViewState(
            scheduleVersion = 3,
            serverTime = "2026-05-08T07:00:00Z",
            invalidTasks = emptyList(),
            activeBlockers = emptyList(),
            inProgressTasks = listOf(
                task(
                    "later",
                    title = "Later started task",
                    dueDatetime = "2026-05-08T11:00:00Z",
                    state = CommitmentState.started
                ),
                task(
                    "current",
                    title = "Current started task",
                    dueDatetime = "2026-05-08T09:00:00Z",
                    state = CommitmentState.started
                ),
                task("unscheduled", title = "No due task", dueDatetime = null, state = CommitmentState.started)
            ),
            startingToday = emptyList(),
            healthWarnings = emptyList()
        )

        val state = buildHomeScreenState(snapshot)

        assertEquals(3, state.inProgressCount)
        assertEquals("Current started task", state.currentInProgressTask?.title)
        assertEquals("2026-05-08T09:00:00Z", state.currentInProgressTask?.dueDatetime)
    }

    private fun task(
        id: String,
        title: String = id,
        dueDatetime: String? = null,
        state: CommitmentState = CommitmentState.planned
    ): app.buzer.shared.model.TaskSnapshotItem =
        app.buzer.shared.model.TaskSnapshotItem(
            id = id,
            todoistTaskId = "todoist-$id",
            title = title,
            projectId = null,
            assigneeId = null,
            creatorId = null,
            dueDatetime = dueDatetime,
            durationMinutes = null,
            startAt = null,
            state = state,
            isAssignedToUser = false,
            isCreatedByUser = false,
            isCompleted = false,
            isDeleted = false,
            validationErrors = emptyList(),
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-05-08T07:00:00Z"
        )
}
