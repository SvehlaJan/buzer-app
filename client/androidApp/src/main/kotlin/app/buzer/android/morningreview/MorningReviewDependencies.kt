package app.buzer.android.morningreview

import android.content.Context
import app.buzer.android.enforcement.AndroidAlarmScheduler
import java.time.Duration
import java.time.LocalTime

object MorningReviewDependencies {
    fun scheduler(context: Context): MorningReviewScheduler = MorningReviewScheduler(
        stateStore = MorningReviewLocalStateStore.fromContext(context),
        alarmScheduler = AndroidAlarmScheduler(context),
        calculator = MorningReviewScheduleCalculator(reviewTime = LocalTime.of(8, 0)),
        snoozeDuration = Duration.ofMinutes(5),
        priorityRetryDuration = Duration.ofMinutes(1)
    )
}
