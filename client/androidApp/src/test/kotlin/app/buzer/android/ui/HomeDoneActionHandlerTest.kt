package app.buzer.android.ui

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.shared.model.MutationOutcome
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.state.SnapshotViewState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun pendingCompletionAppliesAuthoritativeSnapshotAndShowsPendingStatus() = runBlocking {
        var statusMessage: String? = null
        val appliedSnapshots = mutableListOf<SnapshotViewState>()
        val pendingSnapshot = emptySnapshot(scheduleVersion = 23)
        val handler = HomeDoneActionHandler(
            refreshSnapshot = { _ -> error("refresh should not run") },
            completeTask = { _, _ -> CoordinatorActionResult.Pending(response()) },
            latestSnapshot = { pendingSnapshot },
            applySnapshot = appliedSnapshots::add,
            setStatusMessage = { statusMessage = it }
        )

        handler.completeTask("task-current", action())

        assertEquals(listOf(pendingSnapshot), appliedSnapshots)
        assertEquals("Saved; waiting for Todoist confirmation", statusMessage)
    }

    @Test
    fun retryUsesCapturedCoordinatorClosureInsteadOfCreatingAnotherRequest() = runBlocking {
        var completeCalls = 0
        var retryCalls = 0
        var statusMessage: String? = null
        val handler = HomeDoneActionHandler(
            refreshSnapshot = { _ -> error("refresh should not run") },
            completeTask = { _, _ ->
                completeCalls += 1
                CoordinatorActionResult.RetryAfter(
                    retryAfterSeconds = 9,
                    message = "rate limited",
                    retry = {
                        retryCalls += 1
                        CoordinatorActionResult.Success
                    }
                )
            },
            latestSnapshot = { emptySnapshot(scheduleVersion = 33) },
            applySnapshot = {},
            setStatusMessage = { statusMessage = it }
        )

        handler.completeTask("task-current", action())
        handler.retryCompletion()

        assertEquals(1, completeCalls)
        assertEquals(1, retryCalls)
        assertNull(statusMessage)
    }

    @Test
    fun failedCompletionLeavesActionErrorVisibleAndDoesNotRefresh() = runBlocking {
        var statusMessage: String? = null
        var latestSnapshotCalls = 0
        val appliedSnapshots = mutableListOf<SnapshotViewState>()
        val handler = HomeDoneActionHandler(
            refreshSnapshot = { _ -> error("refresh should not run after failed completion") },
            completeTask = { _, _ -> CoordinatorActionResult.FailedAndHidden("500 forced failure") },
            latestSnapshot = {
                latestSnapshotCalls += 1
                emptySnapshot()
            },
            applySnapshot = appliedSnapshots::add,
            setStatusMessage = { statusMessage = it }
        )

        handler.completeTask("task-current", action())

        assertEquals("500 forced failure", statusMessage)
        assertEquals(0, latestSnapshotCalls)
        assertEquals(emptyList(), appliedSnapshots)
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
        assertEquals("Existing action status", actionStatus)
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
    fun completionWorksForAnyHomeRowUsingOneRetainedTaskActionRequestPerRetryChain() = runBlocking {
        val receivedRequests = mutableListOf<TaskActionRequest>()
        var retryCalls = 0
        val initialRequest = action()

        val handler = HomeDoneActionHandler(
            refreshSnapshot = { _ -> emptySnapshot() },
            completeTask = { _, request ->
                receivedRequests.add(request)
                CoordinatorActionResult.RetryAfter(
                    retryAfterSeconds = 5,
                    message = "Rate limited",
                    retry = {
                        retryCalls += 1
                        CoordinatorActionResult.Success
                    }
                )
            },
            latestSnapshot = { emptySnapshot() },
            applySnapshot = {}
        )

        handler.completeTask("unassigned-task-99", initialRequest)
        handler.retryCompletion()

        assertEquals(1, receivedRequests.size)
        assertEquals(initialRequest, receivedRequests.first())
        assertEquals(1, retryCalls)
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
        actionId = "action-current",
        deviceId = "device-current",
        expectedOccurrenceKey = "occurrence-current"
    )

    private fun response(): MutationResponse = MutationResponse(
        actionId = "action-current",
        outcome = MutationOutcome.reconciliation_pending,
        snapshot = app.buzer.shared.model.TasksSnapshotResponse(
            scheduleVersion = 23,
            serverTime = "2026-07-10T12:00:00Z",
            tasks = emptyList(),
            health = emptyList(),
            snapshotGeneration = 23
        )
    )
}

