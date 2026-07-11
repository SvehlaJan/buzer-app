package app.buzer.android.enforcement.overlay

import android.view.View
import app.buzer.android.enforcement.AlarmKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockerOverlayControllerTest {
    @Test
    fun showCleansUpHostAndReturnsFalseWhenWindowAddFails() {
        val host = FakeOverlayHost()
        val controller = BlockerOverlayController(
            window = FailingOverlayWindow(),
            hostFactory = { host }
        )

        val shown = controller.show(
            request = OverlayBlockerRequest(
                kind = AlarmKind.START_BLOCKER,
                taskId = "task-1",
                title = "Pay apartment deposit"
            ),
            onAction = {}
        )

        assertFalse(shown)
        assertTrue(host.destroyed)
        assertFalse(host.attached)
        assertFalse(controller.isShowing)
    }

    private class FailingOverlayWindow : OverlayWindow {
        override fun add(host: OverlayHostHandle) {
            throw RuntimeException("overlay permission revoked")
        }

        override fun remove(host: OverlayHostHandle) = Unit
    }

    private class FakeOverlayHost : OverlayHostHandle {
        var attached = false
            private set
        var destroyed = false
            private set

        override val view: View
            get() = error("Fake host view should not be read by failing add test")

        override fun markAttached() {
            attached = true
        }

        override fun destroy() {
            destroyed = true
        }
    }
}
