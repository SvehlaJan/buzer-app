package app.buzer.android.ui

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.state.SnapshotViewState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeDoneActionHandlerTest {

    @Test
    fun automaticRefreshForwardsFalseToSnapshotProvider() = runBlocking {
        var capturedForce: Boolean? = null
        val handler = HomeDoneActionHandler(
            refreshSnapshot = { force ->
                capturedForce = force
                emptySnapshot()
            },
            completeTask = { _, _ -> CoordinatorActionResult.Success },
            latestSnapshot = { emptySnapshot() },
            applySnapshot = {}
        )

        handler.refresh(forceTodoist = false)

        assertEquals(false, capturedForce)
    }

    @Test
    fun forcedRefreshForwardsTrueToSnapshotProvider() = runBlocking {
        var capturedForce: Boolean? = null
        val handler = HomeDoneActionHandler(
            refreshSnapshot = { force ->
                capturedForce = force
                emptySnapshot()
            },
            completeTask = { _, _ -> CoordinatorActionResult.Success },
            latestSnapshot = { emptySnapshot() },
            applySnapshot = {}
        )

        handler.refresh(forceTodoist = true)

        assertEquals(true, capturedForce)
    }

    @Test
    fun conflictCompletionAppliesAuthoritativeSnapshotAndShowsMessage() = runBlocking {
        var statusMessage: String? = null
        val appliedSnapshots = mutableListOf<SnapshotViewState>()
        val conflictSnapshot = TasksSnapshotResponse(
            scheduleVersion = 23,
            serverTime = "2026-07-10T12:00:00Z",
            tasks = emptyList(),
            health = emptyList(),
            snapshotGeneration = 23
        )
        val handler = HomeDoneActionHandler(
            refreshSnapshot = { _ -> error("refresh should not run") },
            completeTask = { _, _ -> CoordinatorActionResult.Conflict("conflict", "Task changed remotely", conflictSnapshot) },
            latestSnapshot = { emptySnapshot(scheduleVersion = 23) },
            applySnapshot = appliedSnapshots::add,
            setStatusMessage = { statusMessage = it }
        )

        handler.completeTask("task-current", action())

        assertEquals(1, appliedSnapshots.size)
        assertEquals(23L, appliedSnapshots.first().scheduleVersion)
        assertEquals("Task changed remotely", statusMessage)
    }

    @Test
    fun failedCompletionLeavesActionErrorVisibleAndDoesNotRefresh() = runBlocking {
        var statusMessage: String? = null
        var latestSnapshotCalls = 0
        val appliedSnapshots = mutableListOf<SnapshotViewState>()
        val handler = HomeDoneActionHandler(
            refreshSnapshot = { _ -> error("refresh should not run after failed completion") },
            completeTask = { _, _ -> CoordinatorActionResult.Warning("error", "500 forced failure", null) },
            latestSnapshot = {
                latestSnapshotCalls += 1
                emptySnapshot()
            },
            applySnapshot = appliedSnapshots::add,
            setStatusMessage = { statusMessage = it }
        )

        handler.completeTask("task-current", action())

        assertEquals("500 forced failure", statusMessage)
        assertEquals(1, latestSnapshotCalls)
    }

    @Test
    fun successfulCompletionAppliesLatestCachedSnapshotWithoutUsingRefreshRunner() = runBlocking {
        var statusMessage: String? = "Refreshing..."
        var latestSnapshotCalls = 0
        val refreshedSnapshot = emptySnapshot(scheduleVersion = 42)
        val appliedSnapshots = mutableListOf<SnapshotViewState>()
        val handler = HomeDoneActionHandler(
            refreshSnapshot = { _ -> emptySnapshot(scheduleVersion = 10) },
            completeTask = { _, _ -> CoordinatorActionResult.Success },
            latestSnapshot = {
                latestSnapshotCalls += 1
                refreshedSnapshot
            },
            applySnapshot = appliedSnapshots::add,
            setStatusMessage = { statusMessage = it }
        )

        handler.completeTask("task-current", action())

        assertNull(statusMessage)
        assertEquals(1, latestSnapshotCalls)
        assertEquals(listOf(refreshedSnapshot), appliedSnapshots)
    }

    @Test
    fun mutexPreventsCompletionAndRefreshFromApplyingSnapshotsOutOfOrder() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshCanFinish = CompletableDeferred<Unit>()
        val appliedSnapshots = mutableListOf<SnapshotViewState>()
        val handler = HomeDoneActionHandler(
            refreshSnapshot = { _ ->
                refreshStarted.complete(Unit)
                refreshCanFinish.await()
                emptySnapshot(scheduleVersion = 10)
            },
            completeTask = { _, _ -> CoordinatorActionResult.Success },
            latestSnapshot = { emptySnapshot(scheduleVersion = 42) },
            applySnapshot = { appliedSnapshots += it },
            setStatusMessage = {}
        )

        val refreshJob = launch { handler.refresh(forceTodoist = false) }
        refreshStarted.await()

        val completionJob = launch { handler.completeTask("task-current", action()) }
        yield()
        assertEquals(emptyList(), appliedSnapshots)

        refreshCanFinish.complete(Unit)
        refreshJob.join()
        completionJob.join()

        assertEquals(listOf(10L, 42L), appliedSnapshots.map { it.scheduleVersion })
    }

    @Test
    fun failedForcedRefreshDoesNotCallApplySnapshotAndReportsFailure() = runBlocking {
        var appliedSnapshot: SnapshotViewState? = null
        var reportedError: Throwable? = null
        val inFlightStates = mutableListOf<Boolean>()
        var actionStatus: String? = "Existing action status"

        val handler = HomeDoneActionHandler(
            refreshSnapshot = { _ -> throw IllegalStateException("Network error") },
            completeTask = { _, _ -> CoordinatorActionResult.Success },
            latestSnapshot = { emptySnapshot() },
            applySnapshot = { appliedSnapshot = it },
            setStatusMessage = { actionStatus = it },
            setRefreshInFlight = { inFlightStates.add(it) },
            reportRefreshFailure = { reportedError = it }
        )

        handler.refresh(forceTodoist = true)

        assertNull(appliedSnapshot)
        assertEquals("Network error", reportedError?.message)
        assertEquals(listOf(true, false), inFlightStates)
        assertEquals("Network error", actionStatus)
    }

    @Test
    fun failedForcedRefreshWithBlankMessageFallsBackToDefaultMessage() = runBlocking {
        var actionStatus: String? = null

        val handler = HomeDoneActionHandler(
            refreshSnapshot = { _ -> throw IllegalStateException("   ") },
            completeTask = { _, _ -> CoordinatorActionResult.Success },
            latestSnapshot = { emptySnapshot() },
            applySnapshot = {},
            setStatusMessage = { actionStatus = it }
        )

        handler.refresh(forceTodoist = true)

        assertEquals("Refresh failed", actionStatus)
    }

    @Test
    fun successfulRetryClearsRefreshError() = runBlocking {
        var refreshErrorCleared = false
        val handler = HomeDoneActionHandler(
            refreshSnapshot = { _ -> emptySnapshot() },
            completeTask = { _, _ -> CoordinatorActionResult.Success },
            latestSnapshot = { emptySnapshot() },
            applySnapshot = {},
            clearRefreshFailure = { refreshErrorCleared = true }
        )

        handler.refresh(forceTodoist = true)

        assertTrue(refreshErrorCleared)
    }

    @Test
    fun completionWorksForAnyHomeRow() = runBlocking {
        val receivedRequests = mutableListOf<TaskActionRequest>()
        val initialRequest = action()

        val handler = HomeDoneActionHandler(
            refreshSnapshot = { _ -> emptySnapshot() },
            completeTask = { _, request ->
                receivedRequests.add(request)
                CoordinatorActionResult.Success
            },
            latestSnapshot = { emptySnapshot() },
            applySnapshot = {}
        )

        handler.completeTask("unassigned-task-99", initialRequest)

        assertEquals(1, receivedRequests.size)
        assertEquals(initialRequest, receivedRequests.first())
    }

    @Test
    fun completeTaskLocksInteractionsDuringExecutionAndUnlocksImmediatelyOnResult() = runBlocking {
        val interactionStates = mutableListOf<Boolean>()
        val handler = HomeDoneActionHandler(
            refreshSnapshot = { emptySnapshot() },
            completeTask = { _, _ -> CoordinatorActionResult.Warning("error", "Failed", null) },
            latestSnapshot = { emptySnapshot() },
            applySnapshot = {},
            setInteractionInFlight = { interactionStates.add(it) }
        )

        handler.completeTask("task-1", action())

        assertEquals(listOf(true, false), interactionStates)
    }

    private fun emptySnapshot(scheduleVersion: Long = 1): SnapshotViewState =
        SnapshotViewState(
            scheduleVersion = scheduleVersion,
            serverTime = "2026-07-10T12:00:00Z",
            invalidTasks = emptyList(),
            activeBlockers = emptyList(),
            inProgressTasks = emptyList(),
            startingToday = emptyList(),
            healthWarnings = emptyList(),
            tasks = emptyList()
        )

    private fun action(): TaskActionRequest = TaskActionRequest(
        expectedOccurrenceKey = "occurrence-current"
    )
}
