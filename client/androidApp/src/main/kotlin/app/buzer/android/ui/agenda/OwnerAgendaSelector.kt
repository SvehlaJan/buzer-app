package app.buzer.android.ui.agenda

import app.buzer.android.time.parseSnapshotJavaInstantOrNull
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.TaskSnapshotItem
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
        .filterNot(::isTerminal)
        .filter { it.isAssignedToUser }

    val blocking = activeOwnerTasks
        .filter { task ->
            task.state == CommitmentState.invalid ||
                task.id in invalidIds
        }
        .sortedBy { it.id }
    val blockingIds = blocking.mapTo(mutableSetOf()) { it.id }
    val timed = activeOwnerTasks
        .asSequence()
        .filterNot { it.id in blockingIds }
        .filter { it.hierarchyStatus != HierarchyStatus.unsupported }
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

private fun isTerminal(task: TaskSnapshotItem): Boolean =
    task.isDeleted ||
        task.isCompleted ||
        task.state == CommitmentState.completed ||
        task.state == CommitmentState.deleted

private fun TaskSnapshotItem.toAgendaEntry(): OwnerAgendaEntry? {
    val due = dueDatetime?.let(::parseSnapshotJavaInstantOrNull) ?: return null
    val duration = durationMinutes?.takeIf { it > 0 } ?: return null
    val end = runCatching { due.plusSeconds(duration * 60L) }.getOrNull() ?: return null
    return OwnerAgendaEntry(task = this, start = due, end = end)
}
