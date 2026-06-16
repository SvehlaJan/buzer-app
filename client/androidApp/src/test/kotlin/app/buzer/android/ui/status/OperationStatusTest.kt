package app.buzer.android.ui.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationStatusTest {
    @Test
    fun loadingOperationIsInFlightAndHasNoMessage() {
        val status = OperationStatus.Loading("Saving...")

        assertTrue(status.isInFlight)
        assertEquals("Saving...", status.loadingLabel)
        assertEquals(null, status.errorMessage)
    }

    @Test
    fun failedOperationExposesErrorMessage() {
        val status = OperationStatus.Failed("Backend unavailable")

        assertFalse(status.isInFlight)
        assertEquals(null, status.loadingLabel)
        assertEquals("Backend unavailable", status.errorMessage)
    }

    @Test
    fun screenStateReportsBlockingWhenLoadingOrFailedWithoutData() {
        assertTrue(ScreenLoadState.Loading.isBlocking)
        assertTrue(ScreenLoadState.Failed("No backend").isBlocking)
        assertFalse(ScreenLoadState.Ready(data = "snapshot", warning = "Stale").isBlocking)
    }
}
