package app.buzer.shared.ui.home

import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.ChecklistItem

enum class HomeAgendaRowState {
    Overdue,
    Current,
    Upcoming,
    Done
}

data class HomeAgendaRow(
    val taskId: String,
    val timeLabel: String,
    val title: String,
    val state: HomeAgendaRowState,
    val subline: String,
    val description: String?,
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
    val nextUncheckedChildTitle: String? = null
)

data class HomeCurrentBlock(
    val taskId: String,
    val title: String,
    val blockWindow: String,
    val remainingLabel: String,
    val description: String?,
    val doneContentDescription: String = "Finish current task",
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
    val nextUncheckedChildTitle: String? = null,
    val checklistItems: List<ChecklistItem> = emptyList(),
    val doneEnabled: Boolean = checkedCount == totalCount,
    val extendEnabled: Boolean = true
)

data class HomeTaskProgress(
    val checkedCount: Int,
    val totalCount: Int,
    val nextUncheckedChildTitle: String?,
    val doneEnabled: Boolean,
    val extendEnabled: Boolean
) {
    val countLabel: String = "$checkedCount/$totalCount"
}

fun homeTaskProgress(task: TaskSnapshotItem): HomeTaskProgress {
    val nextUnchecked = task.checklistItems
        .asSequence()
        .filterNot { it.checked }
        .sortedBy { it.order }
        .firstOrNull()
        ?.title
    return HomeTaskProgress(
        checkedCount = task.checkedCount,
        totalCount = task.totalCount,
        nextUncheckedChildTitle = nextUnchecked,
        doneEnabled = task.checkedCount == task.totalCount,
        extendEnabled = task.recurrence.kind == RecurrenceKind.none
    )
}

data class HomeAttention(
    val message: String,
    val action: HomeAttentionAction
)

enum class HomeAttentionAction {
    OpenMorningReview,
    ShowHealthDetail,
    RetryRefresh
}
