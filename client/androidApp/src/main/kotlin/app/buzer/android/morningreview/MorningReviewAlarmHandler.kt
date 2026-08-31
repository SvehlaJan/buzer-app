package app.buzer.android.morningreview

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal const val MORNING_REVIEW_TASK_ID = "morning-review"

internal fun morningReviewOccurrenceKey(
    reason: MorningReviewAlarmReason,
    reviewDate: LocalDate,
    triggerAt: Instant
): String = "morning-review:${reason.name.lowercase(Locale.ROOT)}:$reviewDate:${triggerAt.toEpochMilli()}"

enum class MorningReviewAlarmDecision { Launch, Deferred, Ignore }

enum class ActiveBlockerPriority { None, Start, Deadline }

/** Resolves the task blocker that must take precedence over a Morning Review delivery. */
object BlockerPriorityResolver {
    fun resolve(snapshot: SnapshotViewState, now: Instant): ActiveBlockerPriority {
        var hasStartBlocker = false
        val authoritativeInvalidIds = snapshot.invalidTasks.mapTo(mutableSetOf()) { it.id }
        for (task in snapshot.tasks) {
            if (task.id in authoritativeInvalidIds) continue
            val interval = task.activeIntervalOrNull() ?: continue
            if (!now.isBefore(interval.end)) return ActiveBlockerPriority.Deadline
            if (
                task.startedAt == null &&
                task.state != CommitmentState.started &&
                !now.isBefore(interval.start)
            ) {
                hasStartBlocker = true
            }
        }
        return if (hasStartBlocker) ActiveBlockerPriority.Start else ActiveBlockerPriority.None
    }

    private fun TaskSnapshotItem.activeIntervalOrNull(): ActiveInterval? {
        if (
            !isAssignedToUser ||
            occurrenceKey.isBlank() ||
            isCompleted ||
            isDeleted ||
            state == CommitmentState.completed ||
            state == CommitmentState.deleted ||
            state == CommitmentState.invalid ||
            validationErrors.isNotEmpty() ||
            hierarchyStatus != HierarchyStatus.valid
        ) {
            return null
        }
        val start = dueDatetime?.let { value -> runCatching { Instant.parse(value) }.getOrNull() } ?: return null
        val duration = durationMinutes?.takeIf { it > 0 } ?: return null
        return ActiveInterval(start, start.plusSeconds(duration * 60L))
    }

    private data class ActiveInterval(val start: Instant, val end: Instant)
}

internal interface MorningReviewAlarmActions {
    fun reconcile(now: Instant, zoneId: ZoneId)
    fun consume(fired: MorningReviewScheduledAlarm, now: Instant, zoneId: ZoneId)
    fun deferForPriority(fired: MorningReviewScheduledAlarm, now: Instant, zoneId: ZoneId)
}

internal class MorningReviewSchedulerActions(
    private val scheduler: MorningReviewScheduler
) : MorningReviewAlarmActions {
    override fun reconcile(now: Instant, zoneId: ZoneId) = scheduler.reconcile(now, zoneId)

    override fun consume(fired: MorningReviewScheduledAlarm, now: Instant, zoneId: ZoneId) =
        scheduler.consume(fired, now, zoneId)

    override fun deferForPriority(fired: MorningReviewScheduledAlarm, now: Instant, zoneId: ZoneId) =
        scheduler.deferForPriority(fired, now, zoneId)
}

/** Validates an occurrence before the existing notification and overlay delivery path runs. */
internal class MorningReviewAlarmHandler(
    private val stateStore: MorningReviewLocalStateStore,
    private val actions: MorningReviewAlarmActions,
    private val cachedSnapshotLoader: () -> SnapshotViewState?,
    private val nowProvider: () -> Instant,
    private val zoneProvider: () -> ZoneId,
    private val errorReporter: (operation: String, context: String, error: Exception) -> Unit
) {
    fun handle(delivered: app.buzer.android.enforcement.ScheduledAlarmIdentity): MorningReviewAlarmDecision {
        val now = nowProvider()
        val zoneId = zoneProvider()
        val state = stateStore.read()
        val fired = state.scheduledAlarm ?: return MorningReviewAlarmDecision.Ignore

        val persistedIdentity = app.buzer.android.enforcement.ScheduledAlarmIdentity(
            taskId = MORNING_REVIEW_TASK_ID,
            occurrenceKey = fired.occurrenceKey,
            kind = app.buzer.android.enforcement.AlarmKind.MORNING_REVIEW
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
        if (priority != ActiveBlockerPriority.None) {
            try {
                actions.deferForPriority(fired, now, zoneId)
            } catch (error: Exception) {
                report("defer", fired, error)
            }
            return MorningReviewAlarmDecision.Deferred
        }

        try {
            actions.consume(fired, now, zoneId)
        } catch (error: Exception) {
            report("consume", fired, error)
        }
        return MorningReviewAlarmDecision.Launch
    }

    private fun reconcile(fired: MorningReviewScheduledAlarm, now: Instant, zoneId: ZoneId) {
        try {
            actions.reconcile(now, zoneId)
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
