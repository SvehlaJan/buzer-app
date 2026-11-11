package app.buzer.android.ui.agenda

import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.scheduling.CommitmentEligibility
import app.buzer.shared.state.SnapshotViewState
import java.time.Instant
import java.time.ZoneId

data class OwnerAgendaEntry(
    val task: TaskSnapshotItem,
    val start: Instant,
    val end: Instant
)

data class OwnerAgendaBuckets(
    val blocking: List<TaskSnapshotItem>,
    val overdue: List<OwnerAgendaEntry>,
    val current: List<OwnerAgendaEntry>,
    val future: List<OwnerAgendaEntry>
)

fun selectOwnerAgenda(
    snapshot: SnapshotViewState,
    now: Instant,
    zoneId: ZoneId
): OwnerAgendaBuckets {
    val invalidIds = snapshot.invalidTasks.mapTo(mutableSetOf()) { it.id }
    val activeOwnerTasks = snapshot.tasks
        .distinctBy { it.id }
        .filterNot(CommitmentEligibility::isTerminalCommitment)
        .filter { it.isAssignedToUser }

    val blocking = activeOwnerTasks
        .filter { task ->
            CommitmentEligibility.isOwnerBlockingTask(task) ||
                task.id in invalidIds
        }
        .sortedBy { it.id }
    val blockingIds = blocking.mapTo(mutableSetOf()) { it.id }
    val timed = activeOwnerTasks
        .asSequence()
        .filterNot { it.id in blockingIds }
        .mapNotNull { task -> task.toAgendaEntry() }
        .toList()

    return OwnerAgendaBuckets(
        blocking = blocking,
        overdue = timed
            .filter { it.end <= now }
            .sortedWith(compareByDescending<OwnerAgendaEntry> { it.end }.thenBy { it.task.id }),
        current = timed
            .filter { it.start <= now && now < it.end }
            .sortedWith(compareBy<OwnerAgendaEntry> { it.end }.thenBy { it.task.id }),
        future = timed
            .filter { it.start > now }
            .sortedWith(
                compareBy<OwnerAgendaEntry> { it.start }
                    .thenBy { it.end }
                    .thenBy { it.task.id }
            )
    )
}

private fun TaskSnapshotItem.toAgendaEntry(): OwnerAgendaEntry? {
    val window = CommitmentEligibility.timedWindowOrNull(this) ?: return null
    val start = window.start.toJavaInstantOrNull() ?: return null
    val end = window.end.toJavaInstantOrNull() ?: return null
    return OwnerAgendaEntry(task = this, start = start, end = end)
}

private fun kotlin.time.Instant.toJavaInstantOrNull(): Instant? =
    runCatching { Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()) }.getOrNull()
