package app.buzer.android.enforcement.overlay

import app.buzer.android.enforcement.AlarmKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverlayBlockerRequestTest {
    @Test
    fun parsesStartBlockerFromAlarmExtras() {
        val request = OverlayBlockerRequest.fromAlarm(
            kind = AlarmKind.START_BLOCKER,
            taskId = "task-1"
        )

        assertEquals(OverlayBlockerRequest(AlarmKind.START_BLOCKER, "task-1"), request)
    }

    @Test
    fun rejectsTaskBlockerWithoutTaskId() {
        assertNull(OverlayBlockerRequest.fromAlarm(AlarmKind.START_BLOCKER, null))
        assertNull(OverlayBlockerRequest.fromAlarm(AlarmKind.DEADLINE_BLOCKER, null))
    }

    @Test
    fun acceptsMorningReviewWithoutTaskId() {
        val request = OverlayBlockerRequest.fromAlarm(
            kind = AlarmKind.MORNING_REVIEW,
            taskId = null
        )

        assertEquals(OverlayBlockerRequest(AlarmKind.MORNING_REVIEW, null), request)
    }

    @Test
    fun parsesServiceIntentExtras() {
        val request = OverlayBlockerRequest.fromExtras(
            kindName = AlarmKind.DEADLINE_BLOCKER.name,
            taskId = "task-2"
        )

        assertEquals(OverlayBlockerRequest(AlarmKind.DEADLINE_BLOCKER, "task-2"), request)
    }
}
