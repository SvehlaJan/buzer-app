package app.buzer.shared.ui.commitment

import kotlinx.datetime.LocalDateTime

enum class CommitmentEditorMode {
    AddTask,
    Repair,
    Reschedule
}

data class CommitmentEditorDraft(
    val title: String,
    val description: String?,
    val projectName: String?,
    val assigneeLabel: String?,
    val start: LocalDateTime?,
    val durationMinutes: Int,
    val titleEditable: Boolean,
    val descriptionExpanded: Boolean
)

data class CommitmentEditorValidation(
    val bannerMessage: String?,
    val titleError: String?,
    val projectError: String?,
    val assigneeError: String?,
    val startError: String?,
    val durationError: String?
)
