package app.buzer.shared.ui.home

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
    val description: String?
)

data class HomeCurrentBlock(
    val taskId: String,
    val title: String,
    val blockWindow: String,
    val remainingLabel: String,
    val description: String?,
    val doneContentDescription: String = "Finish current task"
)

data class HomeAttention(
    val message: String,
    val action: HomeAttentionAction
)

enum class HomeAttentionAction {
    OpenMorningReview,
    ShowHealthDetail,
    RetryRefresh
}
