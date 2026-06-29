package app.buzer.android.ui

import app.buzer.android.ui.screens.MorningReviewContentState
import app.buzer.android.ui.screens.buildMorningReviewContentState
import kotlin.test.Test
import kotlin.test.assertEquals

class MorningReviewScreenStateTest {
    @Test
    fun showsLoadingWhenRefreshingWithoutCachedTasks() {
        val state = buildMorningReviewContentState(
            invalidTaskCount = 0,
            refreshing = true,
            refreshError = null,
            hasLoadedOnce = false
        )

        assertEquals(MorningReviewContentState.Loading, state)
    }

    @Test
    fun showsReadyWithRefreshWarningWhenCachedTasksExistAndRefreshFails() {
        val state = buildMorningReviewContentState(
            invalidTaskCount = 2,
            refreshing = false,
            refreshError = "Network request failed",
            hasLoadedOnce = true
        )

        assertEquals(
            MorningReviewContentState.Ready(warning = "Refresh failed. Showing last known tasks."),
            state
        )
    }

    @Test
    fun showsBlockingErrorWhenNoCachedDataAndRefreshFails() {
        val state = buildMorningReviewContentState(
            invalidTaskCount = 0,
            refreshing = false,
            refreshError = "Backend unavailable",
            hasLoadedOnce = false
        )

        assertEquals(MorningReviewContentState.Error("Backend unavailable"), state)
    }
}
