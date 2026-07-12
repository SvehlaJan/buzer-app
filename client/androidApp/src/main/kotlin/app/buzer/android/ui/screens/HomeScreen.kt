package app.buzer.android.ui.screens

import app.buzer.android.ui.components.AttentionStrip
import app.buzer.android.ui.components.AttentionStripModel
import app.buzer.android.ui.components.BuzerScaffold
import app.buzer.android.ui.components.IconFabAction
import app.buzer.android.ui.components.IconFabStack
import app.buzer.android.ui.components.PrimaryActionButton
import app.buzer.android.ui.components.TaskDetailsBlock
import app.buzer.android.ui.status.BlockingErrorState
import app.buzer.android.ui.status.BlockingLoadingState
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.status.WarningStatus
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import app.buzer.shared.ui.home.HomeAgendaRow
import app.buzer.shared.ui.home.HomeAgendaRowState
import app.buzer.shared.ui.home.HomeAttention
import app.buzer.shared.ui.home.HomeAttentionAction
import app.buzer.shared.ui.home.HomeCurrentBlock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface HomeContentState {
    data object Loading : HomeContentState
    data object Ready : HomeContentState
    data class Error(val message: String) : HomeContentState
}

data class HomeScreenState(
    val weekdayLabel: String,
    val dateLabel: String,
    val contentState: HomeContentState,
    val statusMessage: String?,
    val attention: HomeAttention?,
    val currentBlock: HomeCurrentBlock?,
    val agendaRows: List<HomeAgendaRow>,
    val canOpenMorningReview: Boolean
)

fun buildHomeScreenState(
    snapshot: SnapshotViewState?,
    refreshInFlight: Boolean = false,
    refreshError: String? = null,
    now: OffsetDateTime
): HomeScreenState {
    val emptyState = HomeScreenState(
        weekdayLabel = weekdayFormatter.format(now),
        dateLabel = dateFormatter.format(now),
        contentState = if (refreshError != null && snapshot == null) {
            HomeContentState.Error(refreshError)
        } else if (snapshot == null) {
            HomeContentState.Loading
        } else {
            HomeContentState.Ready
        },
        statusMessage = null,
        attention = null,
        currentBlock = null,
        agendaRows = emptyList(),
        canOpenMorningReview = false
    )

    if (snapshot == null) return emptyState

    val agendaRows = buildAgendaRows(snapshot, now)
    val currentBlock = agendaRows
        .firstOrNull { it.state == HomeAgendaRowState.Current }
        ?.let { current ->
            HomeCurrentBlock(
                taskId = current.taskId,
                title = current.title,
                blockWindow = current.timeLabel,
                remainingLabel = current.subline.removePrefix("Current - "),
                description = current.description
            )
        }

    return emptyState.copy(
        contentState = HomeContentState.Ready,
        statusMessage = when {
            refreshInFlight -> "Refreshing..."
            else -> null
        },
        attention = buildAttention(snapshot, refreshError),
        currentBlock = currentBlock,
        agendaRows = agendaRows,
        canOpenMorningReview = snapshot.invalidTasks.isNotEmpty()
    )
}

@Composable
fun HomeScreen(
    onAddTask: () -> Unit,
    onMorningReview: () -> Unit,
    onDoneCurrentTask: (String) -> Unit = {},
    onRetryRefresh: () -> Unit = {},
    state: HomeScreenState
) {
    Box(modifier = Modifier.fillMaxSize()) {
        BuzerScaffold(bottomClearance = Spacing.fabClearance) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = state.weekdayLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = state.dateLabel,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            state.statusMessage?.let { message ->
                if (message == "Refreshing...") {
                    InlineLoadingStatus(message)
                } else {
                    WarningStatus(message = message, onRetry = onRetryRefresh)
                }
            }

            state.attention?.let { attention ->
                AttentionStrip(
                    model = AttentionStripModel(
                        message = attention.message,
                        actionLabel = when (attention.action) {
                            HomeAttentionAction.OpenMorningReview -> "Review"
                            HomeAttentionAction.RetryRefresh -> "Retry"
                            HomeAttentionAction.ShowHealthDetail -> null
                        }
                    ),
                    onAction = when (attention.action) {
                        HomeAttentionAction.OpenMorningReview -> onMorningReview
                        HomeAttentionAction.RetryRefresh -> onRetryRefresh
                        HomeAttentionAction.ShowHealthDetail -> null
                    }
                )
            }

            when (val contentState = state.contentState) {
                HomeContentState.Loading -> BlockingLoadingState("Loading tasks...")
                is HomeContentState.Error -> BlockingErrorState(
                    message = contentState.message,
                    onRetry = onRetryRefresh
                )

                HomeContentState.Ready -> HomeReadyContent(
                    state = state,
                    onDoneCurrentTask = onDoneCurrentTask
                )
            }
        }

        IconFabStack(
            primaryAction = IconFabAction(
                contentDescription = "Add task",
                onClick = onAddTask,
                icon = { Text("+", style = MaterialTheme.typography.headlineSmall) }
            ),
            secondaryActions = listOf(
                IconFabAction(
                    contentDescription = "Morning review",
                    onClick = onMorningReview,
                    icon = { Text("R", style = MaterialTheme.typography.labelLarge) }
                )
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.lg)
        )
    }
}

@Composable
private fun ColumnScope.HomeReadyContent(
    state: HomeScreenState,
    onDoneCurrentTask: (String) -> Unit
) {
    state.currentBlock?.let { currentBlock ->
        CurrentBlockHero(
            currentBlock = currentBlock,
            onDone = { onDoneCurrentTask(currentBlock.taskId) }
        )
    }

    if (state.agendaRows.isEmpty() && state.currentBlock == null) {
        Text(
            text = "No tasks for today",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            items(state.agendaRows, key = { it.taskId }) { row ->
                AgendaRow(row)
            }
        }
    }
}

@Composable
private fun CurrentBlockHero(
    currentBlock: HomeCurrentBlock,
    onDone: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = currentBlock.remainingLabel,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = currentBlock.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = currentBlock.blockWindow,
                style = MaterialTheme.typography.bodyMedium
            )
            TaskDetailsBlock(currentBlock.description)
            PrimaryActionButton(
                label = "Done",
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = currentBlock.doneContentDescription }
            )
        }
    }
}

@Composable
private fun AgendaRow(row: HomeAgendaRow) {
    val stateColor = when (row.state) {
        HomeAgendaRowState.Overdue -> MaterialTheme.colorScheme.error
        HomeAgendaRowState.Current -> MaterialTheme.colorScheme.primary
        HomeAgendaRowState.Upcoming -> MaterialTheme.colorScheme.outline
        HomeAgendaRowState.Done -> MaterialTheme.colorScheme.outlineVariant
    }
    val contentColor = if (row.state == HomeAgendaRowState.Done) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = row.timeLabel,
            modifier = Modifier.width(76.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(56.dp)
                .background(stateColor)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = row.subline,
                style = MaterialTheme.typography.bodySmall,
                color = when (row.state) {
                    HomeAgendaRowState.Overdue -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            TaskDetailsBlock(row.description)
        }
    }
}

private fun buildAgendaRows(
    snapshot: SnapshotViewState,
    deviceNow: OffsetDateTime
): List<HomeAgendaRow> {
    val offset = deviceNow.offset
    val today = deviceNow.toLocalDate()
    val tasks = snapshot.tasks
        .distinctBy { it.id }
        .filterNot { it.isDeleted }

    return tasks.mapNotNull { task ->
        val start = task.dueDatetime?.let { parseTaskTime(it, offset) } ?: return@mapNotNull null
        val duration = task.durationMinutes ?: return@mapNotNull null
        val end = start.plusMinutes(duration.toLong())
        val isToday = start.toLocalDate() == today || end.toLocalDate() == today
        if (!isToday) return@mapNotNull null
        val rowState = when {
            task.isCompleted || task.state == CommitmentState.completed -> HomeAgendaRowState.Done
            !deviceNow.isBefore(start) && deviceNow.isBefore(end) -> HomeAgendaRowState.Current
            !deviceNow.isBefore(end) -> HomeAgendaRowState.Overdue
            else -> HomeAgendaRowState.Upcoming
        }
        HomeAgendaRow(
            taskId = task.id,
            timeLabel = "${timeFormatter.format(start)}-${timeFormatter.format(end)}",
            title = task.title,
            state = rowState,
            subline = sublineFor(rowState, deviceNow, start, end),
            description = task.description
        )
    }.sortedWith(
        compareBy<HomeAgendaRow> {
            when (it.state) {
                HomeAgendaRowState.Done -> 0
                HomeAgendaRowState.Overdue -> 1
                HomeAgendaRowState.Current -> 2
                HomeAgendaRowState.Upcoming -> 3
            }
        }.thenBy { it.timeLabel }
    )
}

private fun sublineFor(
    rowState: HomeAgendaRowState,
    deviceNow: OffsetDateTime,
    start: OffsetDateTime,
    end: OffsetDateTime
): String =
    when (rowState) {
        HomeAgendaRowState.Done -> "Done"
        HomeAgendaRowState.Current -> "${durationLabel(Duration.between(deviceNow, end))} remaining"
        HomeAgendaRowState.Upcoming -> "Upcoming - starts in ${durationLabel(Duration.between(deviceNow, start))}"
        HomeAgendaRowState.Overdue -> "Overdue - ended ${durationLabel(Duration.between(end, deviceNow))} ago"
    }

private fun buildAttention(
    snapshot: SnapshotViewState,
    refreshError: String?
): HomeAttention? {
    val invalidCount = snapshot.invalidTasks.size
    if (invalidCount > 0) {
        return HomeAttention(
            message = if (invalidCount == 1) {
                "1 invalid task needs review"
            } else {
                "$invalidCount invalid tasks need review"
            },
            action = HomeAttentionAction.OpenMorningReview
        )
    }

    val permissionIssue = snapshot.healthWarnings.firstOrNull {
        it.key.contains("alarm", ignoreCase = true) ||
            it.key.contains("notification", ignoreCase = true)
    }
    if (permissionIssue != null) {
        return HomeAttention(permissionIssue.message, HomeAttentionAction.ShowHealthDetail)
    }

    if (refreshError != null) {
        return HomeAttention("Refresh failed. Showing last known tasks.", HomeAttentionAction.RetryRefresh)
    }

    if (snapshot.healthWarnings.any { it.key.contains("backend", ignoreCase = true) }) {
        return HomeAttention("Backend refresh needs attention", HomeAttentionAction.RetryRefresh)
    }

    val whatsappIssue = snapshot.healthWarnings.firstOrNull { it.key.contains("whatsapp", ignoreCase = true) }
    return whatsappIssue?.let { HomeAttention(it.message, HomeAttentionAction.ShowHealthDetail) }
}

private fun parseTaskTime(value: String, offset: java.time.ZoneOffset): OffsetDateTime =
    runCatching { OffsetDateTime.parse(value).withOffsetSameInstant(offset) }
        .getOrElse { OffsetDateTime.parse(value.substringBefore('[')).withOffsetSameInstant(offset) }

private fun durationLabel(duration: Duration): String {
    val minutes = duration.toMinutes().coerceAtLeast(0)
    if (minutes < 1) return "<1m"
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours == 0L -> "${remainingMinutes}m"
        remainingMinutes == 0L -> "${hours}h"
        else -> "${hours}h ${remainingMinutes}m"
    }
}

private val weekdayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE", Locale.US)

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM d", Locale.US)

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US)
