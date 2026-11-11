package app.buzer.android.morningreview

import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.ScheduledAlarmIdentity
import app.buzer.shared.scheduling.CommitmentEligibility
import app.buzer.shared.state.SnapshotViewState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

internal const val MORNING_REVIEW_TASK_ID = "morning-review"

internal fun morningReviewOccurrenceKey(
    reason: MorningReviewAlarmReason,
    reviewDate: LocalDate,
    triggerAt: Instant
): String = "morning-review:${reason.name.lowercase(Locale.ROOT)}:$reviewDate:${triggerAt.toEpochMilli()}"

enum class MorningReviewAlarmDecision { Launch, Deferred, Ignore }

/** How long after the review time an active Start blocker may still delay it (decision 2026-09-29). */
internal val MORNING_REVIEW_PRIORITY_CAP: Duration = Duration.ofMinutes(15)

enum class ActiveBlockerPriority { None, Start }

/**
 * Only an unstarted owner task inside its active interval (a Start blocker) outranks the
 * Morning Review. Overdue work never does: a Deadline blocker is only armed for a future
 * deadline (decision 2026-09-29-reminder-reliability).
 */
object BlockerPriorityResolver {
    fun resolve(snapshot: SnapshotViewState, now: Instant): ActiveBlockerPriority {
        val authoritativeInvalidIds = snapshot.invalidTasks.mapTo(mutableSetOf()) { it.id }
        val candidates = snapshot.tasks.filter { task ->
            task.id !in authoritativeInvalidIds && task.occurrenceKey.isNotBlank()
        }
        val sharedNow = kotlin.time.Instant.fromEpochSeconds(now.epochSecond, now.nano)
        val hasStartBlocker = CommitmentEligibility.hasActiveStartPriorityAt(candidates, sharedNow)
        return if (hasStartBlocker) ActiveBlockerPriority.Start else ActiveBlockerPriority.None
    }
}

/** Validates an occurrence before the existing notification and overlay delivery path runs. */
internal class MorningReviewAlarmHandler(
    private val stateStore: MorningReviewLocalStateStore,
    private val scheduler: MorningReviewScheduler,
    private val cachedSnapshotLoader: () -> SnapshotViewState?,
    private val nowProvider: () -> Instant,
    private val zoneProvider: () -> ZoneId,
    private val errorReporter: (operation: String, context: String, error: Exception) -> Unit,
    private val reviewTime: LocalTime = DEFAULT_MORNING_REVIEW_TIME
) {
    fun handle(delivered: ScheduledAlarmIdentity): MorningReviewAlarmDecision {
        val now = nowProvider()
        val zoneId = zoneProvider()
        val state = stateStore.read()
        val fired = state.scheduledAlarm ?: return MorningReviewAlarmDecision.Ignore

        val persistedIdentity = ScheduledAlarmIdentity(
            taskId = MORNING_REVIEW_TASK_ID,
            occurrenceKey = fired.occurrenceKey,
            kind = AlarmKind.MORNING_REVIEW
        )
        if (delivered != persistedIdentity) return MorningReviewAlarmDecision.Ignore
        if (fired.occurrenceKey != morningReviewOccurrenceKey(fired.reason, fired.reviewDate, fired.triggerAt)) {
            reconcile(fired, now, zoneId)
            return MorningReviewAlarmDecision.Ignore
        }

        val today = now.atZone(zoneId).toLocalDate()
        if (fired.reviewDate != today) {
            reconcile(fired, now, zoneId)
            return MorningReviewAlarmDecision.Ignore
        }
        if (fired.reason == MorningReviewAlarmReason.Daily && state.lastAcknowledgedDate == today) {
            reconcile(fired, now, zoneId)
            return MorningReviewAlarmDecision.Ignore
        }

        val priority = try {
            cachedSnapshotLoader()?.let { BlockerPriorityResolver.resolve(it, now) }
                ?: ActiveBlockerPriority.None
        } catch (error: Exception) {
            report("priority", fired, error)
            ActiveBlockerPriority.None
        }
        val priorityCapEnd = fired.reviewDate.atTime(reviewTime).atZone(zoneId).toInstant()
            .plus(MORNING_REVIEW_PRIORITY_CAP)
        if (priority == ActiveBlockerPriority.Start && now.isBefore(priorityCapEnd)) {
            try {
                scheduler.deferForPriority(fired, now, zoneId)
            } catch (error: Exception) {
                report("defer", fired, error)
            }
            return MorningReviewAlarmDecision.Deferred
        }

        try {
            scheduler.consume(fired, now, zoneId)
        } catch (error: Exception) {
            report("consume", fired, error)
        }
        return MorningReviewAlarmDecision.Launch
    }

    private fun reconcile(fired: MorningReviewScheduledAlarm, now: Instant, zoneId: ZoneId) {
        try {
            scheduler.reconcile(now, zoneId)
        } catch (error: Exception) {
            report("reconcile", fired, error)
        }
    }

    private fun report(operation: String, fired: MorningReviewScheduledAlarm, error: Exception) {
        errorReporter(
            operation,
            "occurrence=${fired.occurrenceKey} reason=${fired.reason} reviewDate=${fired.reviewDate}",
            error
        )
    }
}
