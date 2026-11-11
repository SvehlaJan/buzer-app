package app.buzer.android.morningreview

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import app.buzer.android.enforcement.AndroidAlarmScheduler
import app.buzer.android.sync.SnapshotStore
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.state.SnapshotReducer
import java.time.Duration
import java.time.LocalTime

internal object ExactAlarmCapability {
    fun canScheduleExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
}

object MorningReviewDependencies {
    fun scheduler(context: Context): MorningReviewScheduler {
        val appContext = context.applicationContext ?: context
        return MorningReviewScheduler(
            stateStore = MorningReviewLocalStateStore.fromContext(appContext),
            alarmScheduler = AndroidAlarmScheduler(appContext),
            calculator = MorningReviewScheduleCalculator(reviewTime = LocalTime.of(8, 0)),
            snoozeDuration = Duration.ofMinutes(5),
            priorityRetryDuration = Duration.ofMinutes(1),
            canScheduleExactAlarms = { ExactAlarmCapability.canScheduleExactAlarms(appContext) }
        )
    }

    fun coordinator(context: Context): MorningReviewCoordinator {
        val appContext = context.applicationContext ?: context
        return MorningReviewCoordinator(
            stateStore = MorningReviewLocalStateStore.fromContext(appContext),
            scheduler = scheduler(appContext),
            calculator = MorningReviewScheduleCalculator(reviewTime = LocalTime.of(8, 0))
        )
    }

    internal fun alarmHandler(context: Context): MorningReviewAlarmHandler {
        val appContext = context.applicationContext ?: context
        return MorningReviewAlarmHandler(
            stateStore = MorningReviewLocalStateStore.fromContext(appContext),
            scheduler = scheduler(appContext),
            cachedSnapshotLoader = { SnapshotStore(appContext).load()?.let(SnapshotReducer::reduce) },
            nowProvider = MorningReviewRuntimeClock::now,
            zoneProvider = MorningReviewRuntimeClock::zoneId,
            errorReporter = { operation, failureContext, error ->
                AppLog.warn("morning_review_alarm $operation $failureContext", error)
            }
        )
    }
}
