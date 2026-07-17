package app.buzer.android.enforcement.overlay

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayRequestGenerationTest {
    @Test
    fun newerRequestInvalidatesOlderRefreshWatcherAndActionCompletion() {
        val generations = OverlayRequestGeneration()
        val oldRequest = generations.next()
        val newRequest = generations.next()

        assertFalse(generations.isCurrent(oldRequest))
        assertTrue(generations.isCurrent(newRequest))
    }
}
