package app.buzer.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.buzer.android.ui.components.AttentionStrip
import app.buzer.android.ui.components.AttentionStripModel
import app.buzer.android.ui.components.ChecklistProgress
import app.buzer.android.ui.components.IconFabAction
import app.buzer.android.ui.components.IconFabStack
import app.buzer.android.ui.components.PrimaryActionButton
import app.buzer.android.ui.components.TaskDetailsBlock
import app.buzer.android.ui.status.BlockingErrorState
import app.buzer.android.ui.status.BlockingLoadingState
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.status.WarningStatus
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.ui.home.HomeAgendaRow
import app.buzer.shared.ui.home.HomeAgendaRowState
import app.buzer.shared.ui.home.HomeAttentionAction
import app.buzer.shared.ui.home.HomeCurrentBlock
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: HomeScreenState,
    onRefreshTasks: () -> Unit = {},
    onAddTask: () -> Unit = {},
    onEditTask: (String) -> Unit = {},
    onDoneTask: (String) -> Unit = {},
    onToggleChecklistItem: (String, String) -> Unit = { _, _ -> },
    onOpenMorningReview: () -> Unit = {},
    onToggleOverdueExpanded: () -> Unit = {},
    onToggleUnassignedExpanded: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val thirdFutureHeaderIndex: Int? = remember(state) {
        if (state.futureGroups.size < 3) return@remember null

        var idx = 0
        // header:title
        idx += 1
        // attention:review
        if (state.reviewAttention != null) idx += 1
        // attention:refresh
        if (state.refreshAttention != null) idx += 1
        // hero:<id>
        if (state.currentBlock != null) idx += 1
        // overlap:<id>
        idx += state.overlappingCurrentRows.size
        // overdue:header + overdue:<id>
        if (state.overdue.totalCount > 0) {
            idx += 1
            idx += state.overdue.rows.size
        }
        // unassigned:header + unassigned:<id>
        if (state.unassigned.totalCount > 0) {
            idx += 1
            idx += state.unassigned.rows.size
        }
        // future group 0 header + rows
        idx += 1 + state.futureGroups[0].rows.size
        // future group 1 header + rows
        idx += 1 + state.futureGroups[1].rows.size
        // future group 2 header index
        idx
    }

    val showBackToTop by remember(state, thirdFutureHeaderIndex) {
        derivedStateOf {
            val targetIndex = thirdFutureHeaderIndex ?: return@derivedStateOf false
            listState.layoutInfo.visibleItemsInfo.any { it.index >= targetIndex }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.refreshInFlight,
            onRefresh = onRefreshTasks,
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Refresh tasks") {
                            onRefreshTasks()
                            true
                        }
                    )
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("home-agenda-list")
                    .padding(horizontal = Spacing.lg),
                contentPadding = PaddingValues(top = Spacing.md, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                // 1. Title/updated header
                item(key = "header:title") {
                    HomeTitleHeader(state = state, onRetryRefresh = onRefreshTasks)
                }

                // 2. Attention banners (review attention, refresh attention)
                state.reviewAttention?.let { attention ->
                    item(key = "attention:review") {
                        AttentionStrip(
                            model = AttentionStripModel(
                                message = attention.message,
                                actionLabel = "Review"
                            ),
                            onAction = onOpenMorningReview
                        )
                    }
                }

                state.refreshAttention?.let { attention ->
                    item(key = "attention:refresh") {
                        AttentionStrip(
                            model = AttentionStripModel(
                                message = attention.message,
                                actionLabel = "Retry"
                            ),
                            onAction = onRefreshTasks
                        )
                    }
                }

                when (val contentState = state.contentState) {
                    HomeContentState.Loading -> {
                        item(key = "content:loading") {
                            BlockingLoadingState("Loading tasks...")
                        }
                    }

                    is HomeContentState.Error -> {
                        item(key = "content:error") {
                            BlockingErrorState(
                                message = contentState.message,
                                onRetry = onRefreshTasks
                            )
                        }
                    }

                    HomeContentState.Ready -> {
                        // 3. Hero block (currentBlock)
                        state.currentBlock?.let { hero ->
                            item(key = "hero:${hero.taskId}") {
                                CurrentBlockHero(
                                    currentBlock = hero,
                                    overlappingCount = state.overlappingCurrentRows.size,
                                    onDone = { onDoneTask(hero.taskId) },
                                    onEdit = { onEditTask(hero.taskId) },
                                    onToggleChecklistItem = { itemId -> onToggleChecklistItem(hero.taskId, itemId) }
                                )
                            }
                        }

                        // 4. Overlapping current rows
                        items(
                            items = state.overlappingCurrentRows,
                            key = { row -> "overlap:${row.taskId}" }
                        ) { row ->
                            AgendaRow(
                                row = row,
                                onEdit = { onEditTask(row.taskId) },
                                onDone = { onDoneTask(row.taskId) }
                            )
                        }

                        // 5. Overdue section
                        if (state.overdue.totalCount > 0) {
                            item(key = "overdue:header") {
                                SectionHeader(
                                    title = "Overdue",
                                    count = state.overdue.totalCount,
                                    buttonLabel = if (state.overdue.totalCount > 3) {
                                        if (state.overdue.expanded) "Show less" else "Show all ${state.overdue.totalCount}"
                                    } else null,
                                    onButtonClick = onToggleOverdueExpanded
                                )
                            }
                            items(
                                items = state.overdue.rows,
                                key = { row -> "overdue:${row.taskId}" }
                            ) { row ->
                                AgendaRow(
                                    row = row,
                                    onEdit = { onEditTask(row.taskId) },
                                    onDone = { onDoneTask(row.taskId) }
                                )
                            }
                        }

                        // 6. Unassigned section
                        if (state.unassigned.totalCount > 0) {
                            item(key = "unassigned:header") {
                                SectionHeader(
                                    title = "Unassigned",
                                    count = state.unassigned.totalCount,
                                    buttonLabel = if (state.unassigned.expanded) "Show less" else "Show all ${state.unassigned.totalCount}",
                                    onButtonClick = onToggleUnassignedExpanded
                                )
                            }
                            items(
                                items = state.unassigned.rows,
                                key = { row -> "unassigned:${row.taskId}" }
                            ) { row ->
                                AgendaRow(
                                    row = row,
                                    onEdit = { onEditTask(row.taskId) },
                                    onAssign = { onEditTask(row.taskId) }
                                )
                            }
                        }

                        // 7. Future date groups with sticky headers
                        state.futureGroups.forEach { group ->
                            stickyHeader(key = "future:header:${group.date}") {
                                DateGroupHeader(
                                    heading = group.heading,
                                    testTag = "date-header:${group.date}"
                                )
                            }
                            items(
                                items = group.rows,
                                key = { row -> "future:${row.taskId}" }
                            ) { row ->
                                AgendaRow(
                                    row = row,
                                    onEdit = { onEditTask(row.taskId) }
                                )
                            }
                        }

                        // 8. All-Clear state
                        if (state.allClear) {
                            item(key = "allclear") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = Spacing.xl),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No active or upcoming commitments.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Back to top pill & FAB stack alignment
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            AnimatedVisibility(
                visible = showBackToTop,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    onClick = {
                        coroutineScope.launch {
                            listState.animateScrollToItem(0)
                        }
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = "Back to top",
                        modifier = Modifier
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                            .semantics { contentDescription = "Back to top" },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            IconFabStack(
                primaryAction = IconFabAction(
                    contentDescription = "Add task",
                    onClick = onAddTask,
                    icon = { Text("+", style = MaterialTheme.typography.headlineSmall) }
                ),
                secondaryActions = emptyList()
            )
        }
    }
}

@Composable
private fun HomeTitleHeader(
    state: HomeScreenState,
    onRetryRefresh: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = state.weekdayLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.dateLabel,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            state.updatedLabel?.let { updated ->
                Text(
                    text = updated,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        state.statusMessage?.let { message ->
            if (message == "Refreshing...") {
                InlineLoadingStatus(message)
            } else {
                WarningStatus(message = message, onRetry = onRetryRefresh)
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    buttonLabel: String?,
    onButtonClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        if (buttonLabel != null) {
            TextButton(onClick = onButtonClick) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun DateGroupHeader(heading: String, testTag: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        color = MaterialTheme.colorScheme.background
    ) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = Spacing.xs)
        )
    }
}

@Composable
private fun CurrentBlockHero(
    currentBlock: HomeCurrentBlock,
    overlappingCount: Int,
    onDone: () -> Unit,
    onEdit: () -> Unit,
    onToggleChecklistItem: (String) -> Unit
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
            if (overlappingCount > 0) {
                Text(
                    text = "+$overlappingCount overlapping",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
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
            ChecklistProgress(
                items = currentBlock.checklistItems,
                interactive = true,
                onCheckedChange = { item, _ -> onToggleChecklistItem(item.id) }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Edit")
                }
                PrimaryActionButton(
                    label = "Done",
                    onClick = onDone,
                    enabled = currentBlock.doneEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = currentBlock.doneContentDescription }
                )
            }
        }
    }
}

@Composable
private fun AgendaRow(
    row: HomeAgendaRow,
    onEdit: () -> Unit,
    onDone: (() -> Unit)? = null,
    onAssign: (() -> Unit)? = null
) {
    val stateColor = when (row.state) {
        HomeAgendaRowState.Overdue -> MaterialTheme.colorScheme.error
        HomeAgendaRowState.Current -> MaterialTheme.colorScheme.primary
        HomeAgendaRowState.Upcoming -> MaterialTheme.colorScheme.outline
        HomeAgendaRowState.Unassigned -> MaterialTheme.colorScheme.outlineVariant
    }
    val contentColor = MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home-row:${row.taskId}")
            .clickable(onClick = onEdit),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
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
                if (row.totalCount > 0) {
                    Text(
                        text = "Checklist ${row.checkedCount}/${row.totalCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onDone != null) {
                PrimaryActionButton(
                    label = "Done",
                    onClick = onDone,
                    enabled = row.doneEnabled,
                    modifier = Modifier.testTag("home-done:${row.taskId}")
                )
            } else if (onAssign != null) {
                OutlinedButton(
                    onClick = onAssign,
                    modifier = Modifier.testTag("home-assign:${row.taskId}")
                ) {
                    Text("Assign")
                }
            }
        }
    }
}
