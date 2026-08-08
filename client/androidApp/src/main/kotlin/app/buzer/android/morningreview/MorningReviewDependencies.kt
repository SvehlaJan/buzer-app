package app.buzer.android.morningreview

import android.content.Context
import app.buzer.android.enforcement.AndroidAlarmScheduler
import app.buzer.android.sync.SnapshotStore
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.state.SnapshotReducer
import java.time.Instant
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

object MorningReviewDependencies {
    fun scheduler(context: Context): MorningReviewScheduler = MorningReviewScheduler(
        stateStore = MorningReviewLocalStateStore.fromContext(context),
        alarmScheduler = AndroidAlarmScheduler(context),
        calculator = MorningReviewScheduleCalculator(reviewTime = LocalTime.of(8, 0)),
        snoozeDuration = Duration.ofMinutes(5),
        priorityRetryDuration = Duration.ofMinutes(1)
    )

    internal fun alarmHandler(context: Context): MorningReviewAlarmHandler = MorningReviewAlarmHandler(
        stateStore = MorningReviewLocalStateStore.fromContext(context),
        actions = MorningReviewSchedulerActions(scheduler(context)),
        cachedSnapshotLoader = { SnapshotStore(context.applicationContext).load()?.let(SnapshotReducer::reduce) },
        nowProvider = MorningReviewRuntimeClock::now,
        zoneProvider = MorningReviewRuntimeClock::zoneId,
        errorReporter = { operation, failureContext, error ->
            AppLog.warn("morning_review_alarm $operation $failureContext", error)
        }
    )
}
