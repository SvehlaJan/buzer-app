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

class HomeDoneActionHandlerTest {
    @Test
    fun pendingCompletionAppliesAuthoritativeSnapshotAndShowsPendingStatus() = runBlocking {
        var statusMessage: String? = null
        val appliedSnapshots = mutableListOf<SnapshotViewState>()
        val pendingSnapshot = emptySnapshot(scheduleVersion = 23)
        val handler = HomeDoneActionHandler(
            refreshSnapshot = { error("refresh should not run") },
            completeTask = { _, _ -> CoordinatorActionResult.Pending(response()) },
            latestSnapshot = { pendingSnapshot },
            applySnapshot = appliedSnapshots::add,
            setStatusMessage = { statusMessage = it }
        )

        handler.completeCurrentTask("task-current", action())

        assertEquals(listOf(pendingSnapshot), appliedSnapshots)
        assertEquals("Saved; waiting for Todoist confirmation", statusMessage)
    }

    @Test
    fun retryUsesCapturedCoordinatorClosureInsteadOfCreatingAnotherRequest() = runBlocking {
        var completeCalls = 0
        var retryCalls = 0
        var statusMessage: String? = null
        val handler = HomeDoneActionHandler(
            refreshSnapshot = { error("refresh should not run") },
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

        handler.completeCurrentTask("task-current", action())
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
            refreshSnapshot = { error("refresh should not run after failed completion") },
            completeTask = { _, _ -> CoordinatorActionResult.FailedAndHidden("500 forced failure") },
            latestSnapshot = {
                latestSnapshotCalls += 1
                emptySnapshot()
            },
            applySnapshot = appliedSnapshots::add,
            setStatusMessage = { statusMessage = it }
        )

        handler.completeCurrentTask("task-current", action())

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
            refreshSnapshot = { emptySnapshot(scheduleVersion = 10) },
            completeTask = { _, _ -> CoordinatorActionResult.Success },
            latestSnapshot = {
                latestSnapshotCalls += 1
                refreshedSnapshot
            },
            applySnapshot = appliedSnapshots::add,
            setStatusMessage = { statusMessage = it }
        )

        handler.completeCurrentTask("task-current", action())

        assertNull(statusMessage)
        assertEquals(1, latestSnapshotCalls)
        assertEquals(listOf(refreshedSnapshot), appliedSnapshots)
    }

    @Test
    fun successfulCompletionWaitsForActiveRefreshThenAppliesLatestCachedSnapshot() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshCanFinish = CompletableDeferred<Unit>()
        val appliedSnapshots = mutableListOf<SnapshotViewState>()
        val handler = HomeDoneActionHandler(
            refreshSnapshot = {
                refreshStarted.complete(Unit)
                refreshCanFinish.await()
                emptySnapshot(scheduleVersion = 10)
            },
            completeTask = { _, _ -> CoordinatorActionResult.Success },
            latestSnapshot = { emptySnapshot(scheduleVersion = 42) },
            applySnapshot = { appliedSnapshots += it },
            setStatusMessage = {}
        )

        val refreshJob = launch { handler.refresh() }
        refreshStarted.await()

        val completionJob = launch { handler.completeCurrentTask("task-current", action()) }
        yield()
        assertEquals(emptyList(), appliedSnapshots)

        refreshCanFinish.complete(Unit)
        refreshJob.join()
        completionJob.join()

        assertEquals(listOf(10L, 42L), appliedSnapshots.map { it.scheduleVersion })
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
