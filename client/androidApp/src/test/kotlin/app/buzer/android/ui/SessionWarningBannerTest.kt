package app.buzer.android.ui

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionWarningBannerTest {

    private fun sampleTask(id: String) = TaskSnapshotItem(
        id = id,
        todoistTaskId = "todoist-$id",
        title = "Task $id",
        projectId = "p1",
        assigneeId = "u1",
        creatorId = "u1",
        dueDatetime = "2026-07-10T10:00:00Z",
        durationMinutes = 30,
        startAt = null,
        state = CommitmentState.started,
        isAssignedToUser = true,
        isCreatedByUser = true,
        isCompleted = false,
        isDeleted = false,
        validationErrors = emptyList(),
        startedAt = "2026-07-10T10:00:00Z",
        startDismissCount = 0,
        deadlineExtensionCount = 0,
        escalationSentAt = null,
        updatedAt = "2026-07-10T10:00:00Z",
        occurrenceKey = "key-$id",
        taskRevision = "rev-$id"
    )

    private fun sampleSnapshot(vararg tasks: TaskSnapshotItem) = SnapshotViewState(
        scheduleVersion = 1,
        serverTime = "2026-07-10T10:00:00Z",
        invalidTasks = emptyList(),
        activeBlockers = emptyList(),
        inProgressTasks = tasks.toList(),
        startingToday = emptyList(),
        healthWarnings = emptyList(),
        tasks = tasks.toList()
    )

    @Test
    fun successfulForcedRefreshClearsSessionWarning() = runBlocking {
        var warning: String? = "Delivery unknown: backend timed out"
        var refreshInFlight = false
        var interactionInFlight = false
        var appliedSnapshot: SnapshotViewState? = null

        val handler = HomeDoneActionHandler(
            refreshSnapshot = { force ->
                assertTrue(force)
                sampleSnapshot(sampleTask("task-1"))
            },
            completeTask = { _, _ -> CoordinatorActionResult.Success },
            latestSnapshot = { sampleSnapshot(sampleTask("task-1")) },
            applySnapshot = { appliedSnapshot = it },
            setStatusMessage = { warning = it },
            setRefreshInFlight = { refreshInFlight = it },
            setInteractionInFlight = { interactionInFlight = it }
        )

        handler.refresh(forceTodoist = true)

        assertNull(warning, "Successful refresh must clear the session warning")
        assertFalse(refreshInFlight)
        assertFalse(interactionInFlight)
        assertEquals(listOf("task-1"), appliedSnapshot?.tasks?.map { it.id })
    }

    @Test
    fun failedForcedRefreshUpdatesWarningToRefreshFailed() = runBlocking {
        var warning: String? = "Initial mutation warning"
        var refreshInFlight = false
        var interactionInFlight = false

        val handler = HomeDoneActionHandler(
            refreshSnapshot = { _ ->
                throw RuntimeException("Network unreachable")
            },
            completeTask = { _, _ -> CoordinatorActionResult.Success },
            latestSnapshot = { null },
            applySnapshot = {},
            setStatusMessage = { warning = it },
            setRefreshInFlight = { refreshInFlight = it },
            setInteractionInFlight = { interactionInFlight = it }
        )

        handler.refresh(forceTodoist = true)

        assertEquals("Network unreachable", warning)
        assertFalse(refreshInFlight)
        assertFalse(interactionInFlight)
    }

    @Test
    fun mutationResultSetsSessionWarningAndUnlocksImmediately() = runBlocking {
        var warning: String? = null
        val inFlightStates = mutableListOf<Boolean>()

        val handler = HomeDoneActionHandler(
            refreshSnapshot = { sampleSnapshot() },
            completeTask = { _, _ ->
                CoordinatorActionResult.Warning("rejected", "Task was rejected by Todoist", null)
            },
            latestSnapshot = { sampleSnapshot() },
            applySnapshot = {},
            setStatusMessage = { warning = it },
            setInteractionInFlight = { inFlightStates.add(it) }
        )

        handler.completeTask("task-1", TaskActionRequest("key-1"))

        assertEquals("Task was rejected by Todoist", warning)
        assertEquals(listOf(true, false), inFlightStates)
    }

    @Test
    fun transportFailureSetsSessionWarningAndUnlocksImmediately() = runBlocking {
        var warning: String? = null
        val inFlightStates = mutableListOf<Boolean>()

        val handler = HomeDoneActionHandler(
            refreshSnapshot = { sampleSnapshot() },
            completeTask = { _, _ ->
                CoordinatorActionResult.TransportFailure(false, "Delivery unknown. Refresh to inspect state.")
            },
            latestSnapshot = { sampleSnapshot() },
            applySnapshot = {},
            setStatusMessage = { warning = it },
            setInteractionInFlight = { inFlightStates.add(it) }
        )

        handler.completeTask("task-1", TaskActionRequest("key-1"))

        assertEquals("Delivery unknown. Refresh to inspect state.", warning)
        assertEquals(listOf(true, false), inFlightStates)
    }
}
