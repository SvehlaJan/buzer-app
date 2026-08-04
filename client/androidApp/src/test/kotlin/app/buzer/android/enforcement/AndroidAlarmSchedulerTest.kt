package app.buzer.android.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidAlarmSchedulerTest {
    @Test
    fun morningReviewUsesAlarmClockWhilePreStartWarningDoesNot() {
        assertEquals(AlarmSchedulingPath.AlarmClock, AlarmKind.MORNING_REVIEW.schedulingPath())
        assertEquals(AlarmSchedulingPath.ExactAndAllowWhileIdle, AlarmKind.PRE_START_WARNING.schedulingPath())
    }
}
