package app.buzer.android.ui.blockers

import app.buzer.android.ui.screens.BlockerActionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockerActionStateTest {
    @Test
    fun loadingActionDisablesAllBlockerActions() {
        val state = BlockerActionState.Loading("Starting...")

        assertTrue(state.actionsDisabled)
        assertEquals("Starting...", state.loadingLabel)
    }

    @Test
    fun idleActionAllowsButtons() {
        val state = BlockerActionState.Idle

        assertFalse(state.actionsDisabled)
        assertEquals(null, state.loadingLabel)
    }
}
