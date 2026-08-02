package app.buzer.android.ui.screens

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.ReconciliationStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import app.buzer.shared.ui.home.HomeAgendaRow
import app.buzer.shared.ui.home.HomeAgendaRowState
import app.buzer.shared.ui.home.HomeAttention
import app.buzer.shared.ui.home.HomeAttentionAction
import app.buzer.shared.ui.home.HomeCurrentBlock
import app.buzer.shared.ui.home.homeTaskProgress
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface HomeContentState {
    data object Loading : HomeContentState
    data object Ready : HomeContentState
    data class Error(val message: String) : HomeContentState
}

data class HomeDateGroup(
    val date: LocalDate,
    val heading: String,
    val rows: List<HomeAgendaRow>
)

data class HomeExpandableSection(
    val totalCount: Int,
    val rows: List<HomeAgendaRow>,
    val expanded: Boolean
)

data class HomeScreenState(
    val weekdayLabel: String,
    val dateLabel: String,
    val updatedLabel: String?,
    val contentState: HomeContentState,
    val refreshInFlight: Boolean,
    val actionStatusMessage: String?,
    val reviewAttention: HomeAttention?,
    val refreshAttention: HomeAttention?,
    val currentBlock: HomeCurrentBlock?,
    val overlappingCurrentRows: List<HomeAgendaRow>,
    val overdue: HomeExpandableSection,
    val unassigned: HomeExpandableSection,
    val futureGroups: List<HomeDateGroup>,
    val allClear: Boolean
) {
    val canOpenMorningReview: Boolean get() = reviewAttention != null
    val attention: HomeAttention? get() = reviewAttention ?: refreshAttention
    val statusMessage: String? get() = actionStatusMessage ?: if (refreshInFlight) "Refreshing..." else null
    val agendaRows: List<HomeAgendaRow>
        get() = overdue.rows +
            overlappingCurrentRows +
            futureGroups.flatMap { it.rows } +
            unassigned.rows
}

fun buildHomeScreenState(
    snapshot: SnapshotViewState?,
    refreshInFlight: Boolean = false,
    refreshError: String? = null,
    actionStatusMessage: String? = null,
    now: Instant,
    zoneId: ZoneId,
    overdueExpanded: Boolean = false,
    unassignedExpanded: Boolean = false
): HomeScreenState {
    val nowZoned = now.atZone(zoneId)
    val nowDate = nowZoned.toLocalDate()
    val weekdayLabel = DateTimeFormatter.ofPattern("EEEE", Locale.US).format(nowZoned)
    val dateLabel = DateTimeFormatter.ofPattern("MMMM d", Locale.US).format(nowZoned)

    if (snapshot == null) {
        val content = if (refreshError != null) {
            HomeContentState.Error(refreshError)
        } else {
            HomeContentState.Loading
        }
        return HomeScreenState(
            weekdayLabel = weekdayLabel,
            dateLabel = dateLabel,
            updatedLabel = null,
            contentState = content,
            refreshInFlight = refreshInFlight,
            actionStatusMessage = actionStatusMessage,
            reviewAttention = null,
            refreshAttention = null,
            currentBlock = null,
            overlappingCurrentRows = emptyList(),
            overdue = HomeExpandableSection(0, emptyList(), overdueExpanded),
            unassigned = HomeExpandableSection(0, emptyList(), unassignedExpanded),
            futureGroups = emptyList(),
            allClear = false
        )
    }

    val updatedLabel = parseInstant(snapshot.serverTime, zoneId)?.let {
        "Updated " + DateTimeFormatter.ofPattern("HH:mm", Locale.US).format(it.atZone(zoneId))
    }
    val refreshAttention = refreshError?.let {
        HomeAttention("Refresh failed. Showing last known tasks.", HomeAttentionAction.RetryRefresh)
    }

    val activeTasks = snapshot.tasks
        .distinctBy { it.id }
        .filterNot { it.isDeleted || it.isCompleted || it.state == CommitmentState.completed || it.hierarchyStatus == HierarchyStatus.unsupported }

    val invalidIdsFromSnapshot = snapshot.invalidTasks.map { it.id }.toSet()
    val reviewTasks = activeTasks.filter { task ->
        val isOwner = task.isAssignedToUser
        isOwner && (
            task.state == CommitmentState.invalid ||
                task.reconciliationStatus == ReconciliationStatus.pending ||
                invalidIdsFromSnapshot.contains(task.id)
            )
    }
    val reviewIds = reviewTasks.map { it.id }.toSet()
    val reviewAttention = if (reviewTasks.isNotEmpty()) {
        val count = reviewTasks.size
        val msg = if (count == 1) "1 invalid task needs review" else "$count invalid tasks need review"
        HomeAttention(msg, HomeAttentionAction.OpenMorningReview)
    } else {
        null
    }

    val unassignedTasks = activeTasks
        .filter { !it.isAssignedToUser && it.assigneeId == null }
        .filterNot { reviewIds.contains(it.id) }
        .sortedWith(
            compareByDescending<TaskSnapshotItem> { parseInstant(it.updatedAt, zoneId) ?: Instant.EPOCH }
                .thenBy { it.id }
        )
    val unassignedRows = unassignedTasks.map { task ->
        val progress = homeTaskProgress(task)
        val dueInstant = task.dueDatetime?.let { parseInstant(it, zoneId) }
        val duration = task.durationMinutes
        val timeLabel = if (dueInstant != null && duration != null && duration > 0) {
            formatTimeWindow(dueInstant, dueInstant.plusSeconds(duration * 60L), zoneId)
        } else {
            ""
        }
        HomeAgendaRow(
            taskId = task.id,
            timeLabel = timeLabel,
            title = task.title,
            state = HomeAgendaRowState.Unassigned,
            subline = "Unassigned",
            description = task.description,
            checkedCount = progress.checkedCount,
            totalCount = progress.totalCount,
            nextUncheckedChildTitle = progress.nextUncheckedChildTitle,
            doneEnabled = progress.doneEnabled,
            pendingLabel = null,
            actionAvailable = true
        )
    }
    val unassignedSection = HomeExpandableSection(
        totalCount = unassignedRows.size,
        rows = if (unassignedExpanded) unassignedRows else emptyList(),
        expanded = unassignedExpanded
    )

    val ownerTasks = activeTasks
        .filter { it.isAssignedToUser }
        .filterNot { reviewIds.contains(it.id) }

    data class TimedOwnerTask(
        val task: TaskSnapshotItem,
        val startInstant: Instant,
        val endInstant: Instant
    )

    val timedOwnerTasks = ownerTasks.mapNotNull { task ->
        val due = task.dueDatetime ?: return@mapNotNull null
        val start = parseInstant(due, zoneId) ?: return@mapNotNull null
        val dur = task.durationMinutes ?: return@mapNotNull null
        if (dur <= 0) return@mapNotNull null
        val end = start.plusSeconds(dur * 60L)
        TimedOwnerTask(task, start, end)
    }

    val overdueTasks = timedOwnerTasks
        .filter { it.endInstant <= now }
        .sortedWith(
            compareByDescending<TimedOwnerTask> { it.endInstant }
                .thenBy { it.task.id }
        )
    val overdueRows = overdueTasks.map { item ->
        val progress = homeTaskProgress(item.task)
        val timeLabel = formatTimeWindow(item.startInstant, item.endInstant, zoneId)
        val endedAgo = formatDuration(Duration.between(item.endInstant, now))
        HomeAgendaRow(
            taskId = item.task.id,
            timeLabel = timeLabel,
            title = item.task.title,
            state = HomeAgendaRowState.Overdue,
            subline = "Overdue - ended $endedAgo ago",
            description = item.task.description,
            checkedCount = progress.checkedCount,
            totalCount = progress.totalCount,
            nextUncheckedChildTitle = progress.nextUncheckedChildTitle,
            doneEnabled = progress.doneEnabled,
            pendingLabel = null,
            actionAvailable = true
        )
    }
    val overdueSection = HomeExpandableSection(
        totalCount = overdueRows.size,
        rows = if (overdueExpanded) overdueRows else overdueRows.take(3),
        expanded = overdueExpanded
    )

    val currentTasks = timedOwnerTasks
        .filter { it.startInstant <= now && now < it.endInstant }

    val heroItem = currentTasks.minWithOrNull(
        compareBy<TimedOwnerTask> { it.endInstant }
            .thenBy { it.task.id }
    )
    val heroBlock = heroItem?.let { item ->
        val progress = homeTaskProgress(item.task)
        val windowStr = formatTimeWindow(item.startInstant, item.endInstant, zoneId)
        val remainingStr = "${formatDuration(Duration.between(now, item.endInstant))} remaining"
        HomeCurrentBlock(
            taskId = item.task.id,
            title = item.task.title,
            blockWindow = windowStr,
            remainingLabel = remainingStr,
            description = item.task.description,
            doneContentDescription = "Finish current task",
            checkedCount = progress.checkedCount,
            totalCount = progress.totalCount,
            nextUncheckedChildTitle = progress.nextUncheckedChildTitle,
            checklistItems = item.task.checklistItems,
            doneEnabled = progress.doneEnabled,
            extendEnabled = progress.extendEnabled
        )
    }

    val overlappingCurrentRows = if (heroItem != null) {
        currentTasks
            .filterNot { it.task.id == heroItem.task.id }
            .sortedWith(compareBy<TimedOwnerTask> { it.endInstant }.thenBy { it.task.id })
            .map { item ->
                val progress = homeTaskProgress(item.task)
                val windowStr = formatTimeWindow(item.startInstant, item.endInstant, zoneId)
                val remainingStr = "${formatDuration(Duration.between(now, item.endInstant))} remaining"
                HomeAgendaRow(
                    taskId = item.task.id,
                    timeLabel = windowStr,
                    title = item.task.title,
                    state = HomeAgendaRowState.Current,
                    subline = remainingStr,
                    description = item.task.description,
                    checkedCount = progress.checkedCount,
                    totalCount = progress.totalCount,
                    nextUncheckedChildTitle = progress.nextUncheckedChildTitle,
                    doneEnabled = progress.doneEnabled,
                    pendingLabel = null,
                    actionAvailable = true
                )
            }
    } else {
        emptyList()
    }

    val futureTasks = timedOwnerTasks
        .filter { it.startInstant > now }
        .sortedWith(
            compareBy<TimedOwnerTask> { it.startInstant }
                .thenBy { it.endInstant }
                .thenBy { it.task.id }
        )
    val groupedByDate = futureTasks.groupBy { item ->
        item.startInstant.atZone(zoneId).toLocalDate()
    }
    val futureGroups = groupedByDate.entries
        .sortedBy { it.key }
        .map { (date, items) ->
            val heading = formatGroupHeading(date, nowDate)
            val rows = items.map { item ->
                val progress = homeTaskProgress(item.task)
                val windowStr = formatTimeWindow(item.startInstant, item.endInstant, zoneId)
                val startsInStr = formatDuration(Duration.between(now, item.startInstant))
                HomeAgendaRow(
                    taskId = item.task.id,
                    timeLabel = windowStr,
                    title = item.task.title,
                    state = HomeAgendaRowState.Upcoming,
                    subline = "Upcoming - starts in $startsInStr",
                    description = item.task.description,
                    checkedCount = progress.checkedCount,
                    totalCount = progress.totalCount,
                    nextUncheckedChildTitle = progress.nextUncheckedChildTitle,
                    doneEnabled = progress.doneEnabled,
                    pendingLabel = null,
                    actionAvailable = true
                )
            }
            HomeDateGroup(date = date, heading = heading, rows = rows)
        }

    val allClear = heroBlock == null &&
        overlappingCurrentRows.isEmpty() &&
        overdueSection.totalCount == 0 &&
        unassignedSection.totalCount == 0 &&
        futureGroups.isEmpty() &&
        reviewAttention == null

    return HomeScreenState(
        weekdayLabel = weekdayLabel,
        dateLabel = dateLabel,
        updatedLabel = updatedLabel,
        contentState = HomeContentState.Ready,
        refreshInFlight = refreshInFlight,
        actionStatusMessage = actionStatusMessage,
        reviewAttention = reviewAttention,
        refreshAttention = refreshAttention,
        currentBlock = heroBlock,
        overlappingCurrentRows = overlappingCurrentRows,
        overdue = overdueSection,
        unassigned = unassignedSection,
        futureGroups = futureGroups,
        allClear = allClear
    )
}

private fun parseInstant(value: String, zoneId: ZoneId = ZoneId.of("UTC")): Instant? {
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

private fun formatTimeWindow(start: Instant, end: Instant, zoneId: ZoneId): String {
    val startZ = start.atZone(zoneId)
    val endZ = end.atZone(zoneId)
    val fmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    val startStr = fmt.format(startZ)
    val endStr = fmt.format(endZ)
    val crossMidnight = endZ.toLocalDate().isAfter(startZ.toLocalDate())
    return if (crossMidnight) "$startStr-$endStr (+1d)" else "$startStr-$endStr"
}

private fun formatDuration(duration: Duration): String {
    val minutes = duration.toMinutes().coerceAtLeast(0)
    if (minutes < 1) return "<1m"
    val hours = minutes / 60
    val remMin = minutes % 60
    return when {
        hours == 0L -> "${remMin}m"
        remMin == 0L -> "${hours}h"
        else -> "${hours}h ${remMin}m"
    }
}

private fun formatGroupHeading(date: LocalDate, nowDate: LocalDate): String {
    val pattern = when {
        date == nowDate -> "'Today · 'EEEE, MMMM d"
        date == nowDate.plusDays(1) -> "'Tomorrow · 'EEEE, MMMM d"
        date.year == nowDate.year -> "EEEE, MMMM d"
        else -> "EEEE, MMMM d, yyyy"
    }
    return date.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
}
