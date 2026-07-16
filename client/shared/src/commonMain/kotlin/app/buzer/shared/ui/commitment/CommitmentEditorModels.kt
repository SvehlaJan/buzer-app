package app.buzer.shared.ui.commitment

import app.buzer.shared.model.ChecklistMutationOutcome
import app.buzer.shared.model.MutationOutcome
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskRecurrence
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

data class RecurrenceChoice(
    val number: Int,
    val preset: RecurrencePreset,
    val label: String
)

val recurrenceChoices = listOf(
    RecurrenceChoice(1, RecurrencePreset.never, "Never"),
    RecurrenceChoice(2, RecurrencePreset.daily, "Daily"),
    RecurrenceChoice(3, RecurrencePreset.weekdays, "Weekdays"),
    RecurrenceChoice(4, RecurrencePreset.weekly, "Weekly"),
    RecurrenceChoice(5, RecurrencePreset.monthly, "Monthly"),
    RecurrenceChoice(6, RecurrencePreset.yearly, "Yearly")
)

data class RecurrenceEditorState(
    val selectedPreset: RecurrencePreset?,
    val customRule: String?,
    val isReadOnly: Boolean,
    val canEditStartOrRecurrence: Boolean
) {
    fun withReplacement(preset: RecurrencePreset): RecurrenceEditorState =
        copy(selectedPreset = preset, isReadOnly = false, canEditStartOrRecurrence = true)
}

fun recurrenceEditorState(recurrence: TaskRecurrence): RecurrenceEditorState {
    val isCustom = recurrence.kind == RecurrenceKind.custom
    return RecurrenceEditorState(
        selectedPreset = recurrence.preset,
        customRule = recurrence.rule,
        isReadOnly = isCustom,
        canEditStartOrRecurrence = !isCustom
    )
}

data class ChecklistEditorDraft(
    val clientMutationId: String,
    val title: String,
    val description: String? = null,
    val failureCode: String? = null
)

data class ChecklistDraftValidation(
    val bannerMessage: String? = null,
    val invalidDraftIds: Set<String> = emptySet()
)

fun validateChecklistDrafts(drafts: List<ChecklistEditorDraft>): ChecklistDraftValidation {
    val invalidIds = buildSet {
        drafts.filter { it.clientMutationId.isBlank() || it.title.isBlank() }.forEach { add(it.clientMutationId) }
        drafts.groupBy { it.clientMutationId }
            .filterValues { it.size > 1 }
            .keys
            .forEach(::add)
    }
    return ChecklistDraftValidation(
        bannerMessage = if (drafts.size > 30) "A checklist can have at most 30 items" else null,
        invalidDraftIds = invalidIds
    )
}

fun retainFailedChecklistDrafts(
    drafts: List<ChecklistEditorDraft>,
    outcomes: List<ChecklistMutationOutcome>
): List<ChecklistEditorDraft> {
    val failures = outcomes
        .filter { it.outcome == MutationOutcome.failed || it.outcome == MutationOutcome.stale_child }
        .associateBy { it.clientMutationId }
    return drafts.mapNotNull { draft ->
        failures[draft.clientMutationId]?.let { outcome -> draft.copy(failureCode = outcome.errorCode) }
    }
}
