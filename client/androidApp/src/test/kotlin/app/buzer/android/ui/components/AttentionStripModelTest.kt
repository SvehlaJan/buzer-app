package app.buzer.android.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AttentionStripModelTest {
    @Test
    fun modelDefaultsActionLabelToNull() {
        val model = AttentionStripModel(message = "Exact alarm permission is off")

        assertEquals("Exact alarm permission is off", model.message)
        assertNull(model.actionLabel)
    }

    @Test
    fun modelPreservesOptionalActionLabel() {
        val model = AttentionStripModel(
            message = "Refresh failed. Showing last known tasks.",
            actionLabel = "Retry"
        )

        assertEquals("Refresh failed. Showing last known tasks.", model.message)
        assertEquals("Retry", model.actionLabel)
    }
}
