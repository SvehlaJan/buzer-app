package app.buzer.android.enforcement.overlay

import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.EnforcementCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverlayBlockerRequestTest {
    @Test
    fun parsesStartBlocker() {
        assertEquals(
            OverlayBlockerRequest(AlarmKind.START_BLOCKER, "task-1"),
            OverlayBlockerRequest.fromExtras("task-1", "START_BLOCKER")
        )
    }

    @Test
    fun parsesDeadlineBlocker() {
        assertEquals(
            OverlayBlockerRequest(AlarmKind.DEADLINE_BLOCKER, "task-2"),
            OverlayBlockerRequest.fromExtras("task-2", "DEADLINE_BLOCKER")
        )
    }

    @Test
    fun parsesMorningReviewWithDefaultTaskId() {
        assertEquals(
            OverlayBlockerRequest(AlarmKind.MORNING_REVIEW, EnforcementCoordinator.MORNING_REVIEW_TASK_ID),
            OverlayBlockerRequest.fromExtras(null, "MORNING_REVIEW")
        )
    }

    @Test
    fun rejectsTaskBlockerWithoutTaskId() {
        assertNull(OverlayBlockerRequest.fromExtras(null, "START_BLOCKER"))
        assertNull(OverlayBlockerRequest.fromExtras(null, "DEADLINE_BLOCKER"))
    }

    @Test
    fun rejectsPreStartUnknownAndMissingKind() {
        assertNull(OverlayBlockerRequest.fromExtras("task-1", "PRE_START_WARNING"))
        assertNull(OverlayBlockerRequest.fromExtras("task-1", "BOGUS"))
        assertNull(OverlayBlockerRequest.fromExtras("task-1", null))
    }
}
