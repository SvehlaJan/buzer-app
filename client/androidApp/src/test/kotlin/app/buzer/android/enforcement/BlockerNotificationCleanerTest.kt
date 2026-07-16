package app.buzer.android.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockerNotificationCleanerTest {
    @Test
    fun currentNotificationIdsIncludeTheAlarmOccurrenceForEveryBlockerKind() {
        val ids = currentBlockerNotificationIds("task-1", "occurrence-current")

        assertEquals(
            setOf(
                AlarmIntentFactory.requestCode("task-1", "occurrence-current", AlarmKind.MORNING_REVIEW),
                AlarmIntentFactory.requestCode("task-1", "occurrence-current", AlarmKind.START_BLOCKER),
                AlarmIntentFactory.requestCode("task-1", "occurrence-current", AlarmKind.DEADLINE_BLOCKER)
            ),
            ids
        )
    }
}
