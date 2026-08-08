package app.buzer.android.morningreview

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.screens.MorningReviewPhase
import app.buzer.android.ui.screens.buildMorningReviewScreenState
import app.buzer.shared.state.SnapshotViewState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MorningReviewSessionStateTest {
    @Test
    fun cacheAppearsBeforeForcedSyncAndFailureRetainsIt() {
        val cached = snapshot()
        val session = MorningReviewSessionState().cache(cached).beginForcedSync().forceSyncFailed("offline")

        assertEquals(cached, session.snapshot)
        assertEquals("offline", session.refreshError)
        assertFalse(session.refreshInFlight)
    }

    @Test
    fun forceSyncSuccessReplacesCachedAuthority() {
        val cached = snapshot()
        val fresh = snapshot(scheduleVersion = 2)

        assertEquals(fresh, MorningReviewSessionState().cache(cached).beginForcedSync().forceSyncSucceeded(fresh).snapshot)
    }

    @Test
    fun screenStateKeepsLoadingDistinctFromNoDataStartEligibility() {
        val loading = buildMorningReviewScreenState(
            null, loading = true, now = NOW, zoneId = ZONE
        )
        val unavailable = buildMorningReviewScreenState(
            null, refreshError = "offline", now = NOW, zoneId = ZONE
        )

        assertEquals(MorningReviewPhase.Loading, loading.phase)
        assertFalse(loading.startDayEnabled)
        assertEquals(MorningReviewPhase.NoData, unavailable.phase)
        assertTrue(unavailable.startDayEnabled)
    }

    @Test
    fun overdueAuthorityResultsReloadAndRetryUsesReturnedClosureWithoutNewAction() = runBlocking {
        var reloads = 0
        var retries = 0
        val controller = MorningReviewSessionController(publish = { })

        controller.handleOverdueResult(CoordinatorActionResult.Pending(response()), { reloads++ })
        controller.handleOverdueResult(
            CoordinatorActionResult.RetryAfter(1, "wait") {
                retries++
                CoordinatorActionResult.Success
            },
            { reloads++ }
        )
        controller.retryAction()

        assertEquals(2, reloads)
        assertEquals(1, retries)
    }

    @Test
    fun guardRejectsDuplicateAndLocalFailureRemainsRetryable() {
        val controller = MorningReviewSessionController(publish = { })

        assertTrue(controller.beginAction())
        assertFalse(controller.beginAction())
        controller.localActionFailed("storage failed") { }

        assertFalse(controller.state.actionInFlight)
        assertEquals("storage failed", controller.state.actionError)
        assertTrue(controller.hasRetryAction)
    }

    private fun response() = app.buzer.shared.model.MutationResponse(
        actionId = "action",
        outcome = app.buzer.shared.model.MutationOutcome.reconciliation_pending,
        snapshot = app.buzer.shared.model.TasksSnapshotResponse(1, "2026-08-02T10:00:00Z", emptyList(), emptyList(), 1)
    )

    private fun snapshot(scheduleVersion: Long = 1) = SnapshotViewState(
        scheduleVersion = scheduleVersion,
        serverTime = "2026-08-02T10:00:00Z",
        invalidTasks = emptyList(),
        activeBlockers = emptyList(),
        inProgressTasks = emptyList(),
        startingToday = emptyList(),
        healthWarnings = emptyList()
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-02T10:00:00Z")
        val ZONE: ZoneId = ZoneId.of("UTC")
    }
}
