package app.buzer.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.buzer.android.ui.components.BuzerPanel
import app.buzer.android.ui.components.CommitmentEditor
import app.buzer.android.ui.components.TaskDetailsBlock
import app.buzer.android.ui.components.commitmentSaveLabel
import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.status.BlockingErrorState
import app.buzer.android.ui.status.BlockingLoadingState
import app.buzer.android.ui.status.ErrorAlertDialog
import app.buzer.android.ui.status.InlineLoadingStatus
import app.buzer.android.ui.status.LoadingActionButton
import app.buzer.android.ui.status.WarningStatus
import app.buzer.android.ui.theme.Spacing
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.ui.commitment.CommitmentEditorMode
import app.buzer.shared.ui.commitment.CommitmentEditorValidation
import java.time.Instant
import java.time.ZoneId

sealed interface MorningReviewContentState {
    data object Loading : MorningReviewContentState
    data class Ready(val warning: String? = null, val refreshing: Boolean = false) : MorningReviewContentState
    data class Error(val message: String) : MorningReviewContentState
}

fun buildMorningReviewContentState(
    invalidTaskCount: Int,
    refreshing: Boolean,
    refreshError: String?,
    hasLoadedOnce: Boolean
): MorningReviewContentState =
    when {
        refreshError != null && !hasLoadedOnce -> MorningReviewContentState.Error(refreshError)
        refreshError != null -> MorningReviewContentState.Ready(warning = "Refresh failed. Showing last known tasks.")
        refreshing && !hasLoadedOnce && invalidTaskCount == 0 -> MorningReviewContentState.Loading
        refreshing -> MorningReviewContentState.Ready(refreshing = true)
        else -> MorningReviewContentState.Ready()
    }

data class MorningReviewReadyState(
    val headerText: String,
    val headerProgressText: String?,
    val warning: String?,
    val refreshing: Boolean,
    val cards: List<MorningReviewTaskCardState>,
    val actionLabels: List<String>
)

data class MorningReviewTaskCardState(
    val task: TaskSnapshotItem,
    val title: String,
    val problemText: String,
    val description: String?,
    val repairActionLabel: String = "Repair",
    val snoozeActionLabel: String = "Snooze",
    val progressText: String? = null
) {
    fun allText(): String =
        listOfNotNull(title, problemText, description, repairActionLabel, snoozeActionLabel, progressText)
            .joinToString(" ")
}

data class MorningReviewRepairEditorState(
    val mode: CommitmentEditorMode,
    val titleEditable: Boolean,
    val title: String,
    val description: String,
    val projectLabel: String?,
    val assigneeLabel: String?,
    val start: DateTimeSelection?,
    val durationMinutes: Int,
    val submitLabel: String
)

fun buildMorningReviewReadyState(
    invalidTasks: List<TaskSnapshotItem>,
    warning: String?,
    refreshing: Boolean
): MorningReviewReadyState {
    val count = invalidTasks.size
    return MorningReviewReadyState(
        headerText = if (count == 0) {
            "All commitments are valid"
        } else {
            "$count ${if (count == 1) "task needs" else "tasks need"} attention"
        },
        headerProgressText = count.takeIf { it > 0 }?.let { "$it unresolved" },
        warning = warning,
        refreshing = refreshing,
        cards = invalidTasks.map { task ->
            MorningReviewTaskCardState(
                task = task,
                title = task.title,
                problemText = plainLanguageProblem(task.validationErrors),
                description = task.description?.trim()?.takeIf { it.isNotEmpty() }
            )
        },
        actionLabels = if (count == 0) listOf("Done") else emptyList()
    )
}

fun buildMorningReviewRepairEditorState(
    task: TaskSnapshotItem,
    start: DateTimeSelection?,
    durationMinutes: Int
): MorningReviewRepairEditorState =
    MorningReviewRepairEditorState(
        mode = CommitmentEditorMode.Repair,
        titleEditable = false,
        title = task.title,
        description = task.description.orEmpty(),
        projectLabel = task.projectId,
        assigneeLabel = task.assigneeId,
        start = start,
        durationMinutes = durationMinutes,
        submitLabel = commitmentSaveLabel(start, durationMinutes)
    )

private fun plainLanguageProblem(validationErrors: List<String>): String {
    val errors = validationErrors.toSet()
    return when {
        "missing_due_datetime" in errors && "missing_duration" in errors -> "No start time or duration set"
        "missing_due_datetime" in errors -> "No start time set"
        "missing_duration" in errors -> "No duration set"
        validationErrors.isEmpty() -> "Commitment details need repair"
        else -> validationErrors.joinToString(", ") { it.replace('_', ' ') }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorningReviewScreen(
    invalidTasks: List<TaskSnapshotItem> = emptyList(),
    onBack: () -> Unit,
    onRepair: (String, RescheduleTaskRequest) -> Unit = { _, _ -> },
    onSnooze: () -> Unit = {},
    showBack: Boolean = true,
    contentState: MorningReviewContentState = MorningReviewContentState.Ready(),
    repairing: Boolean = false,
    snoozing: Boolean = false,
    actionErrorMessage: String? = null,
    onDismissActionError: () -> Unit = {},
    onRetryAction: () -> Unit = {},
    onRetryRefresh: () -> Unit = {}
) {
    var repairingTask by remember { mutableStateOf<TaskSnapshotItem?>(null) }
    var start by remember(repairingTask?.id) {
        mutableStateOf(repairingTask?.initialRepairStartSelection())
    }
    var durationMinutes by remember(repairingTask?.id) {
        mutableStateOf(repairingTask?.durationMinutes ?: 30)
    }
    var description by remember(repairingTask?.id) {
        mutableStateOf(repairingTask?.description.orEmpty())
    }
    var descriptionExpanded by remember(repairingTask?.id) {
        mutableStateOf(false)
    }
    var errorText by remember(repairingTask?.id) { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        when (val state = contentState) {
            MorningReviewContentState.Loading -> {
                BlockingLoadingState("Loading morning review...")
                return@Column
            }
            is MorningReviewContentState.Error -> {
                BlockingErrorState(message = state.message, onRetry = onRetryRefresh)
                return@Column
            }
            is MorningReviewContentState.Ready -> {
                val readyState = buildMorningReviewReadyState(
                    invalidTasks = invalidTasks,
                    warning = state.warning,
                    refreshing = state.refreshing
                )
                MorningReviewHeader(
                    state = readyState,
                    onRetryRefresh = onRetryRefresh
                )
            }
        }

        val task = repairingTask
        if (task == null) {
            MorningReviewTaskList(
                invalidTasks = invalidTasks,
                onRepair = { repairingTask = it },
                onSnooze = onSnooze,
                onBack = onBack,
                showBack = showBack,
                snoozing = snoozing,
                contentState = contentState
            )
        } else {
            val editorState = buildMorningReviewRepairEditorState(task, start, durationMinutes)
            CommitmentEditor(
                mode = editorState.mode,
                title = editorState.title,
                onTitleChange = {},
                titleEditable = editorState.titleEditable,
                description = description,
                onDescriptionChange = { description = it },
                descriptionExpanded = descriptionExpanded,
                onDescriptionExpandedChange = { descriptionExpanded = it },
                projectContent = { ReceiptText(editorState.projectLabel ?: "Not available") },
                assigneeContent = { ReceiptText(editorState.assigneeLabel ?: "Not available") },
                start = start,
                onStartChange = {
                    start = it
                    errorText = null
                },
                durationMinutes = durationMinutes,
                onDurationChange = {
                    durationMinutes = it
                    errorText = null
                },
                validation = CommitmentEditorValidation(
                    bannerMessage = errorText,
                    titleError = null,
                    projectError = null,
                    assigneeError = null,
                    startError = errorText?.let { "Required" },
                    durationError = null
                ),
                submitting = repairing,
                enabled = !repairing && !snoozing,
                submitLabel = editorState.submitLabel,
                loadingLabel = "Saving...",
                onSubmit = {
                    val selectedStart = start
                    if (selectedStart == null) {
                        errorText = "Start time is required"
                    } else {
                        onRepair(
                            task.id,
                            rescheduleRequestFromEditorStart(
                                start = selectedStart,
                                durationMinutes = durationMinutes,
                                initialDescription = task.description,
                                editedDescription = description
                            )
                        )
                    }
                },
                onCancel = {
                    repairingTask = null
                    errorText = null
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    actionErrorMessage?.let { message ->
        ErrorAlertDialog(
            title = "Action failed",
            message = message,
            onDismiss = onDismissActionError,
            retryLabel = "Retry",
            onRetry = onRetryAction
        )
    }
}

@Composable
private fun MorningReviewHeader(
    state: MorningReviewReadyState,
    onRetryRefresh: () -> Unit
) {
    BuzerPanel {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = state.headerText,
                style = MaterialTheme.typography.headlineSmall
            )
            state.headerProgressText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (state.refreshing) InlineLoadingStatus("Refreshing...")
            state.warning?.let { WarningStatus(it, onRetry = onRetryRefresh) }
        }
    }
}

@Composable
private fun MorningReviewTaskList(
    invalidTasks: List<TaskSnapshotItem>,
    onRepair: (TaskSnapshotItem) -> Unit,
    onSnooze: () -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    snoozing: Boolean,
    contentState: MorningReviewContentState
) {
    val readyState = (contentState as? MorningReviewContentState.Ready)?.let {
        buildMorningReviewReadyState(
            invalidTasks = invalidTasks,
            warning = it.warning,
            refreshing = it.refreshing
        )
    } ?: buildMorningReviewReadyState(invalidTasks, warning = null, refreshing = false)

    if (readyState.cards.isEmpty()) {
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Done")
        }
    } else {
        readyState.cards.forEach { card ->
            MorningReviewRepairCard(
                state = card,
                onRepair = { onRepair(card.task) },
                onSnooze = onSnooze,
                snoozing = snoozing
            )
        }
    }

    if (showBack) {
        TextButton(
            onClick = onBack,
            enabled = !snoozing
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun MorningReviewRepairCard(
    state: MorningReviewTaskCardState,
    onRepair: () -> Unit,
    onSnooze: () -> Unit,
    snoozing: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = state.problemText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TaskDetailsBlock(description = state.description)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                LoadingActionButton(
                    label = state.repairActionLabel,
                    loadingLabel = "Repairing...",
                    loading = false,
                    onClick = onRepair,
                    enabled = !snoozing,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onSnooze,
                    enabled = !snoozing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(state.snoozeActionLabel)
                }
            }
        }
    }
}

@Composable
private fun ReceiptText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun TaskSnapshotItem.initialRepairStartSelection(): DateTimeSelection? {
    val instantText = startAt
        ?: dueDatetime?.takeIf { durationMinutes != null }
    val instant = runCatching { instantText?.let(Instant::parse) }.getOrNull() ?: return null
    val startInstant = if (startAt == null) {
        instant.minusSeconds((durationMinutes ?: return null).toLong() * 60)
    } else {
        instant
    }
    val local = startInstant.atZone(ZoneId.systemDefault()).toLocalDateTime()
    return DateTimeSelection(
        date = local.toLocalDate(),
        hour = local.hour,
        minute = local.minute
    )
}
