package app.buzer.android.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidAlarmSchedulerTest {
    @Test
    fun blockerAndMorningReviewAlarmsUseAlarmClockWhilePreStartWarningDoesNot() {
        assertEquals(AlarmSchedulingPath.AlarmClock, AlarmKind.MORNING_REVIEW.schedulingPath())
        assertEquals(AlarmSchedulingPath.AlarmClock, AlarmKind.START_BLOCKER.schedulingPath())
        assertEquals(AlarmSchedulingPath.AlarmClock, AlarmKind.DEADLINE_BLOCKER.schedulingPath())
        assertEquals(AlarmSchedulingPath.ExactAndAllowWhileIdle, AlarmKind.PRE_START_WARNING.schedulingPath())
    }
}
