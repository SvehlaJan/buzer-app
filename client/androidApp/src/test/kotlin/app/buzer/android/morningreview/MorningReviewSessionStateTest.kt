package app.buzer.android.morningreview

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.screens.MorningReviewPhase
import app.buzer.android.ui.screens.buildMorningReviewScreenState
import app.buzer.android.ui.screens.completeMorningReviewEditorSelection
import app.buzer.shared.state.SnapshotViewState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

        controller.handleOverdueResult(CoordinatorActionResult.Success, { reloads++ })
        controller.handleOverdueResult(
            CoordinatorActionResult.TransportFailure(isConnected = false, message = "offline"),
            { reloads++ },
            {
                retries++
                CoordinatorActionResult.Success
            }
        )
        controller.retryAction()

        assertEquals(2, reloads)
        assertEquals(1, retries)
    }

    @Test
    fun overdueReturnedAuthorityReplacesSessionForConflictWarningAndLocalFailure() = runBlocking {
        val controller = MorningReviewSessionController(publish = { })
        val authority = app.buzer.shared.model.TasksSnapshotResponse(7, "now", emptyList(), emptyList(), 1)

        controller.handleOverdueResult(CoordinatorActionResult.Conflict("conflict", "stale", authority), {})
        assertEquals(7, controller.state.snapshot?.scheduleVersion)
        controller.handleOverdueResult(CoordinatorActionResult.Warning("warning", "stale", authority.copy(scheduleVersion = 8)), {})
        assertEquals(8, controller.state.snapshot?.scheduleVersion)
        controller.handleOverdueResult(CoordinatorActionResult.LocalReconciliationFailed(authority.copy(scheduleVersion = 9), "disk"), {})
        assertEquals(9, controller.state.snapshot?.scheduleVersion)
    }

    @Test
    fun terminalActionsFinishDistinctResultsAndRetryFailureWithoutFinishing() = runBlocking {
        val controller = MorningReviewSessionController(publish = { })
        val finished = mutableListOf<MorningReviewResult>()
        controller.runTerminalAction(MorningReviewResult.Acknowledged, {}, finished::add) {}
        controller.runTerminalAction(MorningReviewResult.Snoozed, {}, finished::add) {}
        controller.runTerminalAction(MorningReviewResult.Snoozed, { error("storage") }, finished::add) {}
        assertEquals(listOf(MorningReviewResult.Acknowledged, MorningReviewResult.Snoozed), finished)
        assertTrue(controller.hasRetryAction)
        assertFalse(controller.state.actionInFlight)
    }

    @Test
    fun editorCompletionClearsSelectionBeforeAuthoritativeSummary() {
        assertEquals(null, completeMorningReviewEditorSelection())
        assertEquals(MorningReviewPhase.Summary, buildMorningReviewScreenState(snapshot(), now = NOW, zoneId = ZONE).phase)
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

    @Test
    fun forcedSyncRetriesOnceSilentlyAfterAFailure() = runBlocking {
        val fresh = snapshot(scheduleVersion = 2)
        val published = mutableListOf<MorningReviewSessionState>()
        val controller = MorningReviewSessionController({ published += it }, MorningReviewSessionState().cache(snapshot()))
        var calls = 0

        val failure = controller.forceSyncWithRetry(retryDelayMillis = 1) {
            calls += 1
            if (calls == 1) error("todoist 503") else fresh
        }

        assertNull(failure)
        assertEquals(2, calls)
        assertEquals(fresh, controller.state.snapshot)
        assertTrue(published.none { it.refreshError != null })
    }

    @Test
    fun forcedSyncShowsTheErrorOnlyAfterTheRetryFails() = runBlocking {
        val cached = snapshot()
        val controller = MorningReviewSessionController({}, MorningReviewSessionState().cache(cached))
        var calls = 0

        val failure = controller.forceSyncWithRetry(retryDelayMillis = 1) {
            calls += 1
            error("todoist 503 #$calls")
        }

        assertEquals("todoist 503 #2", failure?.message)
        assertEquals(2, calls)
        assertEquals("todoist 503 #2", controller.state.refreshError)
        assertEquals(cached, controller.state.snapshot)
        assertFalse(controller.state.refreshInFlight)
    }

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
