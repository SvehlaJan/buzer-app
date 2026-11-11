package app.buzer.android.ui.screens

import app.buzer.android.ui.agenda.OwnerAgendaEntry
import app.buzer.android.ui.agenda.selectOwnerAgenda
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import app.buzer.shared.time.parseSnapshotInstantOrNull
import app.buzer.shared.ui.home.homeTaskProgress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

sealed interface MorningReviewPhase {
    data object Loading : MorningReviewPhase
    data object Blocking : MorningReviewPhase
    data object Summary : MorningReviewPhase
    data object NoData : MorningReviewPhase
}

sealed interface MorningReviewBlockingCard {
    val taskId: String
    val title: String
    val problemText: String
    val description: String?

    data class Repair(
        override val taskId: String,
        override val title: String,
        override val problemText: String,
        override val description: String?
    ) : MorningReviewBlockingCard

    data class UnsupportedHierarchy(
        override val taskId: String,
        override val title: String,
        override val problemText: String,
        override val description: String?
    ) : MorningReviewBlockingCard
}

data class MorningReviewAgendaRow(
    val taskId: String,
    val title: String,
    val timeLabel: String,
    val subline: String,
    val doneEnabled: Boolean
)

data class MorningReviewScreenState(
    val phase: MorningReviewPhase,
    val headerText: String,
    val absoluteDateLabel: String,
    val warning: String?,
    val refreshing: Boolean,
    val blockingCards: List<MorningReviewBlockingCard>,
    val overdueRows: List<MorningReviewAgendaRow>,
    val todayRows: List<MorningReviewAgendaRow>,
    val startDayLabel: String,
    val startDayEnabled: Boolean,
    val snoozeEnabled: Boolean,
    val showBack: Boolean,
    val actionInFlight: Boolean
)

fun buildMorningReviewScreenState(
    snapshot: SnapshotViewState?,
    loading: Boolean = false,
    refreshInFlight: Boolean = false,
    refreshError: String? = null,
    actionInFlight: Boolean = false,
    actionError: String? = null,
    now: Instant,
    zoneId: ZoneId,
    showBack: Boolean = true
): MorningReviewScreenState {
    val dateLabel = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)
        .format(now.atZone(zoneId))
    val warning = buildList {
        refreshError?.let {
            add(
                if (snapshot == null) {
                    "Refresh failed. Task data is unavailable: $it"
                } else {
                    "Refresh failed. Showing last known tasks: $it"
                }
            )
        }
        actionError?.let {
            add("Action failed: $it")
        }
    }.joinToString(" ").ifEmpty { null }

    if (snapshot == null) {
        if (loading || refreshInFlight) {
            return screenState(
                phase = MorningReviewPhase.Loading,
                headerText = "Loading morning review",
                absoluteDateLabel = dateLabel,
                warning = warning,
                refreshing = refreshInFlight,
                showBack = showBack,
                actionInFlight = actionInFlight
            )
        }
        return screenState(
            phase = MorningReviewPhase.NoData,
            headerText = "Task data unavailable",
            absoluteDateLabel = dateLabel,
            warning = warning,
            refreshing = false,
            startDayLabel = "Start day without data",
            startDayEnabled = !actionInFlight,
            snoozeEnabled = !actionInFlight,
            showBack = showBack,
            actionInFlight = actionInFlight
        )
    }

    val agenda = selectOwnerAgenda(snapshot, now, zoneId)
    val blockingCards = agenda.blocking.map(::blockingCard)
    if (blockingCards.isNotEmpty()) {
        return screenState(
            phase = MorningReviewPhase.Blocking,
            headerText = "${blockingCards.size} ${if (blockingCards.size == 1) "task needs" else "tasks need"} attention",
            absoluteDateLabel = dateLabel,
            warning = warning,
            refreshing = refreshInFlight,
            blockingCards = blockingCards,
            startDayEnabled = false,
            snoozeEnabled = !actionInFlight,
            showBack = showBack,
            actionInFlight = actionInFlight
        )
    }

    val today = now.atZone(zoneId).toLocalDate()
    val todayEntries = (
        agenda.overdue.filter { it.start.atZone(zoneId).toLocalDate() == today } +
            agenda.current +
            agenda.future.filter { it.start.atZone(zoneId).toLocalDate() == today }
        )
        .distinctBy { it.task.id }
        .sortedWith(compareBy<OwnerAgendaEntry> { it.start }.thenBy { it.end }.thenBy { it.task.id })
    return screenState(
        phase = MorningReviewPhase.Summary,
        headerText = "Morning review",
        absoluteDateLabel = dateLabel,
        warning = warning,
        refreshing = refreshInFlight,
        overdueRows = agenda.overdue.map { it.toReviewRow(zoneId, "Overdue", actionInFlight) },
        todayRows = todayEntries.map { it.toReviewRow(zoneId, "Today", actionInFlight) },
        startDayEnabled = !actionInFlight,
        snoozeEnabled = !actionInFlight,
        showBack = showBack,
        actionInFlight = actionInFlight
    )
}

private fun screenState(
    phase: MorningReviewPhase,
    headerText: String,
    absoluteDateLabel: String,
    warning: String?,
    refreshing: Boolean,
    blockingCards: List<MorningReviewBlockingCard> = emptyList(),
    overdueRows: List<MorningReviewAgendaRow> = emptyList(),
    todayRows: List<MorningReviewAgendaRow> = emptyList(),
    startDayLabel: String = "Start day",
    startDayEnabled: Boolean = false,
    snoozeEnabled: Boolean = false,
    showBack: Boolean,
    actionInFlight: Boolean
) = MorningReviewScreenState(
    phase = phase,
    headerText = headerText,
    absoluteDateLabel = absoluteDateLabel,
    warning = warning,
    refreshing = refreshing,
    blockingCards = blockingCards,
    overdueRows = overdueRows,
    todayRows = todayRows,
    startDayLabel = startDayLabel,
    startDayEnabled = startDayEnabled,
    snoozeEnabled = snoozeEnabled,
    showBack = showBack,
    actionInFlight = actionInFlight
)

private fun blockingCard(task: TaskSnapshotItem): MorningReviewBlockingCard {
    val description = task.description?.trim()?.takeIf { it.isNotEmpty() }
    val hasValidRootSchedule = task.dueDatetime?.let(::parseSnapshotInstantOrNull) != null &&
        (task.durationMinutes ?: 0) > 0
    return if (
        task.hierarchyStatus == HierarchyStatus.unsupported &&
        task.validationErrors.isEmpty() &&
        hasValidRootSchedule
    ) {
        MorningReviewBlockingCard.UnsupportedHierarchy(
            taskId = task.id,
            title = task.title,
            problemText = hierarchyProblemText(task.hierarchyViolation),
            description = description
        )
    } else {
        MorningReviewBlockingCard.Repair(
            taskId = task.id,
            title = task.title,
            problemText = reviewProblemText(task.validationErrors),
            description = description
        )
    }
}

private fun hierarchyProblemText(violation: String?): String = when (violation) {
    "nested_child" -> "Unsupported hierarchy: subtasks cannot have nested subtasks"
    "too_many_children" -> "Unsupported hierarchy: more than 30 subtasks"
    "child_has_schedule" -> "Unsupported hierarchy: a subtask has its own date or time"
    "child_has_duration" -> "Unsupported hierarchy: a subtask has its own duration"
    "child_has_recurrence" -> "Unsupported hierarchy: a subtask is recurring"
    "child_has_assignment" -> "Unsupported hierarchy: a subtask has its own assignee"
    "child_project_mismatch" -> "Unsupported hierarchy: a subtask is in a different project"
    else -> "Unsupported subtask hierarchy — fix in Todoist"
}

private fun reviewProblemText(validationErrors: List<String>): String {
    val errors = validationErrors.toSet()
    val missingDueDatetime = "missing_due_datetime" in errors || "due_datetime_required" in errors
    val missingDuration = "missing_duration" in errors || "duration_required" in errors
    return when {
        missingDueDatetime && missingDuration -> "No start time or duration set"
        missingDueDatetime -> "No start time set"
        missingDuration -> "No duration set"
        validationErrors.isEmpty() -> "Commitment details need repair"
        else -> validationErrors.joinToString(", ") { it.replace('_', ' ') }
    }
}

private fun OwnerAgendaEntry.toReviewRow(
    zoneId: ZoneId,
    subline: String,
    actionInFlight: Boolean
): MorningReviewAgendaRow {
    val time = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    val progress = homeTaskProgress(task)
    val localStart = start.atZone(zoneId)
    val localEnd = end.atZone(zoneId)
    val offsetsChanged = localStart.offset != localEnd.offset
    val startLabel = if (offsetsChanged) {
        "${time.format(localStart)} (UTC${localStart.offset})"
    } else {
        time.format(localStart)
    }
    val endLabel = if (offsetsChanged) {
        "${time.format(localEnd)} (UTC${localEnd.offset})"
    } else {
        time.format(localEnd)
    }
    val dayDelta = ChronoUnit.DAYS.between(localStart.toLocalDate(), localEnd.toLocalDate())
    val dateSuffix = if (dayDelta != 0L) " (+${dayDelta}d)" else ""
    return MorningReviewAgendaRow(
        taskId = task.id,
        title = task.title,
        timeLabel = "$startLabel-$endLabel$dateSuffix",
        subline = subline,
        doneEnabled = progress.doneEnabled && !actionInFlight
    )
}
