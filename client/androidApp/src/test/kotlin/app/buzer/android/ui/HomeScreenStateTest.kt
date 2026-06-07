package app.buzer.android.ui

import app.buzer.android.ui.screens.buildHomeScreenState
import app.buzer.shared.model.HealthLevel
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.state.SnapshotViewState
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeScreenStateTest {
    @Test
    fun summarizesSnapshotCountsForHome() {
        val snapshot = SnapshotViewState(
            scheduleVersion = 2,
            serverTime = "2026-05-08T07:00:00Z",
            invalidTasks = listOf(task("invalid")),
            activeBlockers = listOf(task("start"), task("deadline")),
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

    private fun task(id: String): app.buzer.shared.model.TaskSnapshotItem =
        app.buzer.shared.model.TaskSnapshotItem(
            id = id,
            todoistTaskId = "todoist-$id",
            title = id,
            projectId = null,
            assigneeId = null,
            creatorId = null,
            dueDatetime = null,
            durationMinutes = null,
            startAt = null,
            state = app.buzer.shared.model.CommitmentState.planned,
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
