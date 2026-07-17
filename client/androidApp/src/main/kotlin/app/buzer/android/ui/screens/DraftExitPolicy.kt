package app.buzer.android.ui.screens

import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.shared.model.RecurrencePreset

internal enum class DraftExitDecision { NavigateHome, ConfirmDiscard, Ignore }

internal fun <T> decideDraftExit(
    initial: T,
    current: T,
    submitting: Boolean
): DraftExitDecision = when {
    submitting -> DraftExitDecision.Ignore
    initial == current -> DraftExitDecision.NavigateHome
    else -> DraftExitDecision.ConfirmDiscard
}

internal data class ChecklistDraftFingerprint(
    val stableId: String,
    val title: String,
    val description: String?,
    val checked: Boolean
)

internal data class AddTaskDraftFingerprint(
    val title: String,
    val description: String,
    val projectId: String?,
    val assigneeId: String?,
    val start: DateTimeDraftSelection,
    val durationMinutes: Int,
    val recurrencePreset: RecurrencePreset,
    val checklistItems: List<ChecklistDraftFingerprint>
) {
    companion object {
        fun empty(projectId: String?): AddTaskDraftFingerprint = AddTaskDraftFingerprint(
            title = "",
            description = "",
            projectId = projectId,
            assigneeId = null,
            start = DateTimeDraftSelection.Empty,
            durationMinutes = 30,
            recurrencePreset = RecurrencePreset.never,
            checklistItems = emptyList()
        )
    }
}

internal data class EditTaskDraftFingerprint(
    val title: String,
    val description: String,
    val start: DateTimeDraftSelection,
    val durationMinutes: Int,
    val recurrencePreset: RecurrencePreset?,
    val existingChildren: List<ChecklistDraftFingerprint>,
    val deletedChildIds: Set<String>,
    val addedChildren: List<ChecklistDraftFingerprint>
)

internal fun addTaskDraftFingerprint(
    title: String,
    description: String,
    projectId: String?,
    assigneeId: String?,
    start: DateTimeDraftSelection,
    durationMinutes: Int,
    recurrencePreset: RecurrencePreset,
    checklistItems: List<ChecklistDraftFingerprint>
): AddTaskDraftFingerprint = AddTaskDraftFingerprint(
    title = title,
    description = description,
    projectId = projectId,
    assigneeId = assigneeId,
    start = start,
    durationMinutes = durationMinutes,
    recurrencePreset = recurrencePreset,
    checklistItems = checklistItems
)

internal fun editTaskDraftFingerprint(
    title: String,
    description: String,
    start: DateTimeDraftSelection,
    durationMinutes: Int,
    recurrencePreset: RecurrencePreset?,
    existingChildren: List<ChecklistDraftFingerprint>,
    deletedChildIds: Set<String>,
    addedChildren: List<ChecklistDraftFingerprint>
): EditTaskDraftFingerprint = EditTaskDraftFingerprint(
    title = title,
    description = description,
    start = start,
    durationMinutes = durationMinutes,
    recurrencePreset = recurrencePreset,
    existingChildren = existingChildren,
    deletedChildIds = deletedChildIds,
    addedChildren = addedChildren
)
