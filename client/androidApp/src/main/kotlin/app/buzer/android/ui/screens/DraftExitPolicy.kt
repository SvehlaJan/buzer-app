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

internal class AddTaskDraftFingerprint(
    val title: String,
    val description: String,
    val projectId: String?,
    val assigneeId: String?,
    val start: DateTimeDraftSelection,
    val durationMinutes: Int,
    val recurrencePreset: RecurrencePreset,
    checklistItems: List<ChecklistDraftFingerprint>
) {
    val checklistItems: List<ChecklistDraftFingerprint> = checklistItems.toList()

    fun copy(
        title: String = this.title,
        description: String = this.description,
        projectId: String? = this.projectId,
        assigneeId: String? = this.assigneeId,
        start: DateTimeDraftSelection = this.start,
        durationMinutes: Int = this.durationMinutes,
        recurrencePreset: RecurrencePreset = this.recurrencePreset,
        checklistItems: List<ChecklistDraftFingerprint> = this.checklistItems
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

    override fun equals(other: Any?): Boolean = other is AddTaskDraftFingerprint &&
        title == other.title &&
        description == other.description &&
        projectId == other.projectId &&
        assigneeId == other.assigneeId &&
        start == other.start &&
        durationMinutes == other.durationMinutes &&
        recurrencePreset == other.recurrencePreset &&
        checklistItems == other.checklistItems

    override fun hashCode(): Int = listOf(
        title,
        description,
        projectId,
        assigneeId,
        start,
        durationMinutes,
        recurrencePreset,
        checklistItems
    ).hashCode()

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

internal class EditTaskDraftFingerprint(
    val title: String,
    val description: String,
    val start: DateTimeDraftSelection,
    val durationMinutes: Int,
    val recurrencePreset: RecurrencePreset?,
    existingChildren: List<ChecklistDraftFingerprint>,
    deletedChildIds: Set<String>,
    addedChildren: List<ChecklistDraftFingerprint>
) {
    val existingChildren: List<ChecklistDraftFingerprint> = existingChildren.toList()
    val deletedChildIds: Set<String> = deletedChildIds.toSet()
    val addedChildren: List<ChecklistDraftFingerprint> = addedChildren.toList()

    fun copy(
        title: String = this.title,
        description: String = this.description,
        start: DateTimeDraftSelection = this.start,
        durationMinutes: Int = this.durationMinutes,
        recurrencePreset: RecurrencePreset? = this.recurrencePreset,
        existingChildren: List<ChecklistDraftFingerprint> = this.existingChildren,
        deletedChildIds: Set<String> = this.deletedChildIds,
        addedChildren: List<ChecklistDraftFingerprint> = this.addedChildren
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

    override fun equals(other: Any?): Boolean = other is EditTaskDraftFingerprint &&
        title == other.title &&
        description == other.description &&
        start == other.start &&
        durationMinutes == other.durationMinutes &&
        recurrencePreset == other.recurrencePreset &&
        existingChildren == other.existingChildren &&
        deletedChildIds == other.deletedChildIds &&
        addedChildren == other.addedChildren

    override fun hashCode(): Int = listOf(
        title,
        description,
        start,
        durationMinutes,
        recurrencePreset,
        existingChildren,
        deletedChildIds,
        addedChildren
    ).hashCode()
}

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
