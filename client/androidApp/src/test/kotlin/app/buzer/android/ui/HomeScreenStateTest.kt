package app.buzer.android.ui

import app.buzer.android.ui.screens.HomeContentState
import app.buzer.android.ui.screens.buildHomeScreenState
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthLevel
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.state.SnapshotViewState
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.state.SnapshotReducer
import app.buzer.shared.ui.home.HomeAgendaRowState
import app.buzer.shared.ui.home.HomeAttentionAction
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HomeScreenStateTest {
    @Test
    fun showsInitialLoadingBeforeFirstSnapshot() {
        val state = buildHomeScreenState(
            snapshot = null,
            refreshInFlight = true,
            refreshError = null,
            now = FIXED_NOW
        )

        assertEquals(HomeContentState.Loading, state.contentState)
        assertEquals(null, state.statusMessage)
    }

    @Test
    fun showsBlockingErrorWhenRefreshFailsBeforeFirstSnapshot() {
        val state = buildHomeScreenState(
            snapshot = null,
            refreshInFlight = false,
            refreshError = "Backend unavailable",
            now = FIXED_NOW
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
            refreshError = null,
            now = FIXED_NOW
        )

        assertEquals(HomeContentState.Ready, state.contentState)
        assertEquals("Refreshing...", state.statusMessage)
    }

    @Test
    fun keepsExistingDataVisibleWithSingleStaleWarningAfterRefreshFailure() {
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
            refreshError = "Network request failed",
            now = FIXED_NOW
        )

        assertEquals(HomeContentState.Ready, state.contentState)
        assertNull(state.statusMessage)
        val attention = assertNotNull(state.attention)
        assertEquals("Refresh failed. Showing last known tasks.", attention.message)
        assertEquals(HomeAttentionAction.RetryRefresh, attention.action)
    }

    @Test
    fun appHomeStateUsesBlockingErrorWithoutStatusBeforeFirstSnapshot() {
        val state = buildBuzerHomeState(
            snapshot = null,
            homeRefreshInFlight = false,
            homeRefreshError = "Backend unavailable",
            now = FIXED_NOW
        )

        assertEquals(HomeContentState.Error("Backend unavailable"), state.contentState)
        assertEquals(null, state.statusMessage)
    }

    @Test
    fun appHomeStateUsesSingleAttentionForStaleWarningAfterRefreshFailure() {
        val snapshot = SnapshotViewState(
            scheduleVersion = 2,
            serverTime = "2026-05-08T07:00:00Z",
            invalidTasks = emptyList(),
            activeBlockers = emptyList(),
            inProgressTasks = emptyList(),
            startingToday = emptyList(),
            healthWarnings = emptyList()
        )

        val state = buildBuzerHomeState(
            snapshot = snapshot,
            homeRefreshInFlight = false,
            homeRefreshError = "Network request failed",
            now = FIXED_NOW
        )

        assertEquals(HomeContentState.Ready, state.contentState)
        assertNull(state.statusMessage)
        val attention = assertNotNull(state.attention)
        assertEquals("Refresh failed. Showing last known tasks.", attention.message)
        assertEquals(HomeAttentionAction.RetryRefresh, attention.action)
    }

    @Test
    fun buildsDateFirstHeaderFromDeviceClock() {
        val snapshot = SnapshotViewState(
            scheduleVersion = 2,
            serverTime = "2026-05-08T07:00:00Z",
            invalidTasks = emptyList(),
            activeBlockers = emptyList(),
            inProgressTasks = emptyList(),
            startingToday = emptyList(),
            healthWarnings = emptyList()
        )

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW)

        assertEquals("Wednesday", state.weekdayLabel)
        assertEquals("July 8", state.dateLabel)
    }

    @Test
    fun derivesPlannedAgendaFromDeviceClockWhenServerDateDiffers() {
        val reduced = SnapshotReducer.reduce(
            TasksSnapshotResponse(
                scheduleVersion = 4,
                serverTime = "2026-07-07T23:30:00Z",
                tasks = listOf(
                    task(
                        "planned-device-today",
                        title = "Device today task",
                        dueDatetime = "2026-07-08T14:00:00Z",
                        durationMinutes = 30
                    )
                ),
                health = emptyList()
            )
        )

        assertEquals(emptyList(), reduced.startingToday.map { it.id })

        val state = buildHomeScreenState(reduced, now = FIXED_NOW)

        assertEquals(listOf("planned-device-today"), state.agendaRows.map { it.taskId })
        assertEquals(HomeAgendaRowState.Upcoming, state.agendaRows.single().state)
    }

    @Test
    fun exposesDoneAgendaRowsFromReducerOutput() {
        val reduced = SnapshotReducer.reduce(
            TasksSnapshotResponse(
                scheduleVersion = 5,
                serverTime = "2026-07-08T12:00:00Z",
                tasks = listOf(
                    task(
                        "completed",
                        title = "Completed fixture",
                        dueDatetime = "2026-07-08T08:30:00Z",
                        durationMinutes = 30,
                        state = CommitmentState.completed,
                        isCompleted = true
                    )
                ),
                health = emptyList()
            )
        )

        val state = buildHomeScreenState(reduced, now = FIXED_NOW)

        assertEquals(listOf(HomeAgendaRowState.Done), state.agendaRows.map { it.state })
        assertEquals("completed", state.agendaRows.single().taskId)
        assertEquals("Done", state.agendaRows.single().subline)
    }

    @Test
    fun exposesOverdueDoneAndUpcomingAgendaRows() {
        val snapshot = SnapshotViewState(
            scheduleVersion = 2,
            serverTime = "2026-05-08T07:00:00Z",
            invalidTasks = emptyList(),
            activeBlockers = emptyList(),
            inProgressTasks = emptyList(),
            startingToday = listOf(
                task(
                    "overdue",
                    title = "Send report",
                    description = "Include numbers",
                    dueDatetime = "2026-07-08T10:30:00Z",
                    durationMinutes = 30
                ),
                task(
                    "upcoming",
                    title = "Call plumber",
                    dueDatetime = "2026-07-08T14:00:00Z",
                    durationMinutes = 30
                ),
                task(
                    "done",
                    title = "Morning admin",
                    dueDatetime = "2026-07-08T08:30:00Z",
                    durationMinutes = 30,
                    state = CommitmentState.completed,
                    isCompleted = true
                )
            ),
            healthWarnings = emptyList()
        )

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW)

        assertEquals(
            listOf(HomeAgendaRowState.Done, HomeAgendaRowState.Overdue, HomeAgendaRowState.Upcoming),
            state.agendaRows.map { it.state }
        )
        assertEquals("08:30-09:00", state.agendaRows[0].timeLabel)
        assertEquals("Done", state.agendaRows[0].subline)
        assertEquals("10:30-11:00", state.agendaRows[1].timeLabel)
        assertEquals("Overdue - ended 1h ago", state.agendaRows[1].subline)
        assertEquals("Include numbers", state.agendaRows[1].description)
        assertEquals("14:00-14:30", state.agendaRows[2].timeLabel)
        assertEquals("Upcoming - starts in 2h", state.agendaRows[2].subline)
    }

    @Test
    fun exposesCurrentBlockDoneActionModel() {
        val snapshot = SnapshotViewState(
            scheduleVersion = 2,
            serverTime = "2026-05-08T07:00:00Z",
            invalidTasks = emptyList(),
            activeBlockers = emptyList(),
            inProgressTasks = listOf(
                task(
                    "current",
                    title = "Write review notes",
                    description = "Cover release risk",
                    dueDatetime = "2026-07-08T11:30:00Z",
                    durationMinutes = 60,
                    state = CommitmentState.started
                )
            ),
            startingToday = emptyList(),
            healthWarnings = emptyList()
        )

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW)

        val currentBlock = assertNotNull(state.currentBlock)
        assertEquals("current", currentBlock.taskId)
        assertEquals("Write review notes", currentBlock.title)
        assertEquals("11:30-12:30", currentBlock.blockWindow)
        assertEquals("30m remaining", currentBlock.remainingLabel)
        assertEquals("Cover release risk", currentBlock.description)
        assertEquals("Finish current task", currentBlock.doneContentDescription)
        assertEquals(
            listOf(HomeAgendaRowState.Current),
            state.agendaRows.map { it.state }
        )
    }

    @Test
    fun attentionStripPrefersInvalidTasksOverHealthWarnings() {
        val snapshot = SnapshotViewState(
            scheduleVersion = 2,
            serverTime = "2026-05-08T07:00:00Z",
            invalidTasks = listOf(task("invalid", state = CommitmentState.invalid)),
            activeBlockers = emptyList(),
            inProgressTasks = emptyList(),
            startingToday = emptyList(),
            healthWarnings = listOf(HealthStatus("exact_alarm", HealthLevel.warning, "Exact alarm disabled"))
        )

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW)

        val attention = assertNotNull(state.attention)
        assertEquals("1 invalid task needs review", attention.message)
        assertEquals(HomeAttentionAction.OpenMorningReview, attention.action)
        assertEquals(true, state.canOpenMorningReview)
    }

    @Test
    fun showsEmptyPlaceholderWhenNoTasksToday() {
        val snapshot = SnapshotViewState(
            scheduleVersion = 2,
            serverTime = "2026-05-08T07:00:00Z",
            invalidTasks = emptyList(),
            activeBlockers = emptyList(),
            inProgressTasks = emptyList(),
            startingToday = emptyList(),
            healthWarnings = emptyList()
        )

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW)

        assertEquals(HomeContentState.Ready, state.contentState)
        assertEquals(emptyList(), state.agendaRows)
        assertNull(state.currentBlock)
    }

    @Test
    fun emitsNoHealthyFreshnessText() {
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
            refreshError = null,
            now = FIXED_NOW
        )

        assertNull(state.statusMessage)
        assertNull(state.attention)
        val visibleStateText = listOfNotNull(state.statusMessage, state.attention?.message)
            .joinToString(" ")
            .lowercase()
        listOf("now", "last updated", "refresh").forEach { forbidden ->
            assertFalse(
                visibleStateText.contains(forbidden),
                "Healthy Home state should not expose permanent freshness text: $forbidden"
            )
        }
    }

    private fun task(
        id: String,
        title: String = id,
        description: String? = null,
        dueDatetime: String? = null,
        durationMinutes: Int? = null,
        state: CommitmentState = CommitmentState.planned,
        isCompleted: Boolean = false
    ): app.buzer.shared.model.TaskSnapshotItem =
        app.buzer.shared.model.TaskSnapshotItem(
            id = id,
            todoistTaskId = "todoist-$id",
            title = title,
            description = description,
            projectId = null,
            assigneeId = null,
            creatorId = null,
            dueDatetime = dueDatetime,
            durationMinutes = durationMinutes,
            startAt = null,
            state = state,
            isAssignedToUser = false,
            isCreatedByUser = false,
            isCompleted = isCompleted,
            isDeleted = false,
            validationErrors = emptyList(),
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-05-08T07:00:00Z"
        )

    private companion object {
        val FIXED_NOW: OffsetDateTime = OffsetDateTime.parse("2026-07-08T12:00:00Z")
    }
}
