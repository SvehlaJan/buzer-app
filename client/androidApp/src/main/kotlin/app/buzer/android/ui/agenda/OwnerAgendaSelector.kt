package app.buzer.android.ui.agenda

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.ReconciliationStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
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
        .filterNot(::isTerminalOrUnsupported)
        .filter { it.isAssignedToUser }

    val blocking = activeOwnerTasks
        .filter { task ->
            task.state == CommitmentState.invalid ||
                task.reconciliationStatus == ReconciliationStatus.pending ||
                task.id in invalidIds
        }
        .sortedBy { it.id }
    val blockingIds = blocking.mapTo(mutableSetOf()) { it.id }
    val timed = activeOwnerTasks
        .asSequence()
        .filterNot { it.id in blockingIds }
        .mapNotNull { task -> task.toAgendaEntry(zoneId) }
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

private fun isTerminalOrUnsupported(task: TaskSnapshotItem): Boolean =
    task.isDeleted ||
        task.isCompleted ||
        task.state == CommitmentState.completed ||
        task.hierarchyStatus == HierarchyStatus.unsupported

private fun TaskSnapshotItem.toAgendaEntry(zoneId: ZoneId): OwnerAgendaEntry? {
    val due = dueDatetime?.let { parseAgendaInstant(it, zoneId) } ?: return null
    val duration = durationMinutes?.takeIf { it > 0 } ?: return null
    return OwnerAgendaEntry(task = this, start = due, end = due.plusSeconds(duration * 60L))
}

private fun parseAgendaInstant(value: String, zoneId: ZoneId): Instant? {
    val normalized = value.substringBefore('[')
    return runCatching { Instant.parse(normalized) }
        .getOrElse {
            runCatching { OffsetDateTime.parse(normalized).toInstant() }
                .getOrElse {
                    runCatching { LocalDateTime.parse(normalized).atZone(zoneId).toInstant() }
                        .getOrNull()
                }
        }
}
