package app.buzer.android.ui.screens

import app.buzer.android.ui.agenda.OwnerAgendaEntry
import app.buzer.android.ui.agenda.selectOwnerAgenda
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import app.buzer.shared.ui.home.homeTaskProgress
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    data class Repair(
        override val taskId: String,
        override val title: String,
        val problemText: String,
        val description: String?
    ) : MorningReviewBlockingCard

    data class SyncPending(
        override val taskId: String,
        override val title: String,
        val statusText: String = "Sync pending"
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
    val warning = actionError ?: refreshError?.let { "Refresh failed. Showing last known tasks." }

    if (snapshot == null) {
        if (loading || refreshInFlight) {
            return screenState(
                phase = MorningReviewPhase.Loading,
                headerText = "Loading morning review",
                absoluteDateLabel = dateLabel,
                warning = null,
                refreshing = refreshInFlight,
                showBack = showBack,
                actionInFlight = actionInFlight
            )
        }
        return screenState(
            phase = MorningReviewPhase.NoData,
            headerText = "Task data unavailable",
            absoluteDateLabel = dateLabel,
            warning = actionError ?: refreshError,
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
        overdueRows = agenda.overdue.map { it.toReviewRow(zoneId, "Overdue") },
        todayRows = todayEntries.map { it.toReviewRow(zoneId, "Today") },
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

private fun blockingCard(task: TaskSnapshotItem): MorningReviewBlockingCard =
    if (task.reconciliationStatus == app.buzer.shared.model.ReconciliationStatus.pending) {
        MorningReviewBlockingCard.SyncPending(task.id, task.title)
    } else {
        MorningReviewBlockingCard.Repair(
            taskId = task.id,
            title = task.title,
            problemText = reviewProblemText(task.validationErrors),
            description = task.description?.trim()?.takeIf { it.isNotEmpty() }
        )
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

private fun OwnerAgendaEntry.toReviewRow(zoneId: ZoneId, subline: String): MorningReviewAgendaRow {
    val time = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    val progress = homeTaskProgress(task)
    return MorningReviewAgendaRow(
        taskId = task.id,
        title = task.title,
        timeLabel = "${time.format(start.atZone(zoneId))}-${time.format(end.atZone(zoneId))}",
        subline = subline,
        doneEnabled = progress.doneEnabled
    )
}
