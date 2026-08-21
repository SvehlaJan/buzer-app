package app.buzer.android.morningreview

import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.AlarmScheduler
import app.buzer.android.enforcement.ScheduledAlarm
import app.buzer.android.enforcement.ScheduledAlarmIdentity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Maintains the one durable AlarmManager identity owned by Morning Review. */
class MorningReviewScheduler(
    private val stateStore: MorningReviewLocalStateStore,
    private val alarmScheduler: AlarmScheduler,
    private val calculator: MorningReviewScheduleCalculator,
    private val snoozeDuration: Duration,
    private val priorityRetryDuration: Duration
) {
    fun reconcile(now: Instant, zoneId: ZoneId) {
        val previous = stateStore.read()
        val normalized = calculator.normalize(previous, now, zoneId)
        replaceAlarm(previous, normalized, calculator.desiredAlarm(normalized, now, zoneId))
    }

    fun acknowledge(now: Instant, zoneId: ZoneId) {
        val previous = stateStore.read()
        val acknowledged = calculator.normalize(previous, now, zoneId).copy(
            lastAcknowledgedDate = now.atZone(zoneId).toLocalDate(),
            pendingSnoozeDate = null,
            pendingSnoozeAt = null
        )
        replaceAlarm(previous, acknowledged, calculator.desiredAlarm(acknowledged, now, zoneId))
    }

    fun snooze(now: Instant, zoneId: ZoneId) {
        val previous = stateStore.read()
        val snoozed = calculator.normalize(previous, now, zoneId).copy(
            pendingSnoozeDate = now.atZone(zoneId).toLocalDate(),
            pendingSnoozeAt = now.plus(snoozeDuration)
        )
        replaceAlarm(previous, snoozed, calculator.desiredAlarm(snoozed, now, zoneId))
    }

    /** Consumes only the currently persisted occurrence; stale deliveries have no side effects. */
    fun consume(fired: MorningReviewScheduledAlarm, now: Instant, zoneId: ZoneId) {
        val previous = stateStore.read()
        if (previous.scheduledAlarm != fired) return

        val consumed = calculator.normalize(previous, now, zoneId).copy(
            scheduledAlarm = null
        )
        replaceAlarm(previous, consumed, calculator.desiredAlarm(consumed, now, zoneId))
    }

    /** Defers only the currently persisted occurrence while Start/Deadline blocker priority exists. */
    fun deferForPriority(fired: MorningReviewScheduledAlarm, now: Instant, zoneId: ZoneId) {
        val previous = stateStore.read()
        if (previous.scheduledAlarm != fired) return

        val retry = MorningReviewScheduledAlarm(
            reviewDate = fired.reviewDate,
            reason = MorningReviewAlarmReason.PriorityRetry,
            triggerAt = now.plus(priorityRetryDuration),
            occurrenceKey = morningReviewOccurrenceKey(
                reason = MorningReviewAlarmReason.PriorityRetry,
                reviewDate = fired.reviewDate,
                triggerAt = now.plus(priorityRetryDuration)
            )
        )
        replaceAlarm(previous, calculator.normalize(previous, now, zoneId), retry)
    }

    private fun replaceAlarm(
        previous: MorningReviewLocalState,
        next: MorningReviewLocalState,
        desired: MorningReviewScheduledAlarm
    ) {
        val previousAlarm = previous.scheduledAlarm
        val desiredScheduledAlarm = desired.asScheduledAlarm()
        val replacementRequired = previousAlarm?.identity() != desiredScheduledAlarm.identity
        val persisted = next.copy(scheduledAlarm = desired)

        // This legacy identity predates occurrence-keyed Morning Review alarms.
        alarmScheduler.cancel(legacyIdentity)
        if (replacementRequired) previousAlarm?.let { alarmScheduler.cancel(it.identity()) }

        try {
            // AlarmManager is cleared on reboot/package replacement while the persisted
            // identity remains. Re-scheduling the same PendingIntent replaces/re-arms it.
            alarmScheduler.schedule(desiredScheduledAlarm)
        } catch (scheduleFailure: Throwable) {
            recoverScheduleFailure(
                primaryFailure = scheduleFailure,
                previousAlarm = previousAlarm,
                desiredAlarm = desiredScheduledAlarm,
                replacementRequired = replacementRequired
            )
            throw scheduleFailure
        }

        try {
            stateStore.replace(persisted)
            app.buzer.android.observability.DiagnosticContextStore.instance.updateScheduling { current ->
                current.copy(morningReviewScheduledAt = desired.triggerAt.toString())
            }
        } catch (persistenceFailure: Throwable) {
            recoverPersistenceFailure(persistenceFailure, desiredScheduledAlarm)
            throw persistenceFailure
        }
    }

    private fun recoverScheduleFailure(
        primaryFailure: Throwable,
        previousAlarm: MorningReviewScheduledAlarm?,
        desiredAlarm: ScheduledAlarm,
        replacementRequired: Boolean
    ) {
        // A failed re-arm for the same identity may leave the previously valid alarm in place.
        // Never cancel it merely because the replacement call reported a failure.
        if (!replacementRequired) return

        recover(primaryFailure) { alarmScheduler.cancel(desiredAlarm.identity) }
        previousAlarm?.let { previous ->
            recover(primaryFailure) { alarmScheduler.schedule(previous.asScheduledAlarm()) }
        }
    }

    private fun recoverPersistenceFailure(
        primaryFailure: Throwable,
        desiredAlarm: ScheduledAlarm
    ) {
        val observedState = try {
            stateStore.read()
        } catch (readFailure: Throwable) {
            primaryFailure.addSuppressed(readFailure)
            // Scheduling succeeded. Without an observable state authority, retain that armed
            // identity rather than cancelling an alarm the store may already have recorded.
            return
        }
        val observedAlarm = observedState.scheduledAlarm
        if (observedAlarm?.identity() == desiredAlarm.identity) return

        recover(primaryFailure) { alarmScheduler.cancel(desiredAlarm.identity) }
        observedAlarm?.let { observed ->
            recover(primaryFailure) { alarmScheduler.schedule(observed.asScheduledAlarm()) }
        }
    }

    private inline fun recover(primaryFailure: Throwable, action: () -> Unit) {
        try {
            action()
        } catch (recoveryFailure: Throwable) {
            primaryFailure.addSuppressed(recoveryFailure)
        }
    }

    private fun MorningReviewScheduledAlarm.asScheduledAlarm(): ScheduledAlarm = ScheduledAlarm(
        taskId = MORNING_REVIEW_TASK_ID,
        occurrenceKey = occurrenceKey,
        kind = AlarmKind.MORNING_REVIEW,
        triggerAtEpochMillis = triggerAt.toEpochMilli()
    )

    private fun MorningReviewScheduledAlarm.identity(): ScheduledAlarmIdentity = asScheduledAlarm().identity

    private companion object {
        val legacyIdentity = ScheduledAlarmIdentity(
            taskId = MORNING_REVIEW_TASK_ID,
            occurrenceKey = MORNING_REVIEW_TASK_ID,
            kind = AlarmKind.MORNING_REVIEW
        )
    }
}
