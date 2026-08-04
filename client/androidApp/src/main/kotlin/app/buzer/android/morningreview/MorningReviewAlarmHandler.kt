package app.buzer.android.morningreview

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.ReconciliationStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import java.time.Instant
import java.time.ZoneId

enum class MorningReviewAlarmDecision { Launch, Deferred, Ignore }

enum class ActiveBlockerPriority { None, Start, Deadline }

/** Resolves the task blocker that must take precedence over a Morning Review delivery. */
object BlockerPriorityResolver {
    fun resolve(snapshot: SnapshotViewState, now: Instant): ActiveBlockerPriority {
        var hasStartBlocker = false
        for (task in snapshot.tasks) {
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
            isCompleted ||
            isDeleted ||
            state == CommitmentState.completed ||
            state == CommitmentState.deleted ||
            state == CommitmentState.invalid ||
            validationErrors.isNotEmpty() ||
            hierarchyStatus != HierarchyStatus.valid ||
            reconciliationStatus == ReconciliationStatus.pending
        ) {
            return null
        }
        val start = dueDatetime?.let { value -> runCatching { Instant.parse(value) }.getOrNull() } ?: return null
        val duration = durationMinutes?.takeIf { it > 0 } ?: return null
        return ActiveInterval(start, start.plusSeconds(duration * 60L))
    }

    private data class ActiveInterval(val start: Instant, val end: Instant)
}

/** Validates an occurrence before the existing notification and overlay delivery path runs. */
class MorningReviewAlarmHandler(
    private val stateStore: MorningReviewLocalStateStore,
    private val scheduler: MorningReviewScheduler,
    private val cachedSnapshotLoader: () -> SnapshotViewState?,
    private val nowProvider: () -> Instant,
    private val zoneProvider: () -> ZoneId
) {
    fun handle(occurrenceKey: String): MorningReviewAlarmDecision {
        val now = nowProvider()
        val zoneId = zoneProvider()
        val state = stateStore.read()
        val fired = state.scheduledAlarm ?: return MorningReviewAlarmDecision.Ignore

        if (fired.occurrenceKey != occurrenceKey) return MorningReviewAlarmDecision.Ignore

        val today = now.atZone(zoneId).toLocalDate()
        if (fired.reviewDate != today) {
            scheduler.reconcile(now, zoneId)
            return MorningReviewAlarmDecision.Ignore
        }
        if (fired.reason == MorningReviewAlarmReason.Daily && state.lastAcknowledgedDate == today) {
            scheduler.reconcile(now, zoneId)
            return MorningReviewAlarmDecision.Ignore
        }

        val priority = cachedSnapshotLoader()?.let { BlockerPriorityResolver.resolve(it, now) }
            ?: ActiveBlockerPriority.None
        if (priority != ActiveBlockerPriority.None) {
            scheduler.deferForPriority(fired, now, zoneId)
            return MorningReviewAlarmDecision.Deferred
        }

        scheduler.consume(fired, now, zoneId)
        return MorningReviewAlarmDecision.Launch
    }
}
