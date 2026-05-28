package app.buzer.android.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AlarmIntentFactoryTest {
    @Test
    fun createsStableRequestCodes() {
        assertEquals(
            AlarmIntentFactory.requestCode("task-1", AlarmKind.START_BLOCKER),
            AlarmIntentFactory.requestCode("task-1", AlarmKind.START_BLOCKER)
        )
    }

    @Test
    fun usesDifferentCodesForDifferentKinds() {
        val start = AlarmIntentFactory.requestCode("task-1", AlarmKind.START_BLOCKER)
        val deadline = AlarmIntentFactory.requestCode("task-1", AlarmKind.DEADLINE_BLOCKER)

        assertNotEquals(start, deadline)
    }
}
