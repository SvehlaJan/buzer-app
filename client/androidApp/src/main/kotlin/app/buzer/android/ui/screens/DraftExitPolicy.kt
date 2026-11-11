package app.buzer.android.ui.screens

import app.buzer.android.ui.datetime.DateTimeDraftSelection
import app.buzer.android.ui.taskeditor.ChecklistDraftFingerprint
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

internal class AddTaskDraftFingerprint(
    val title: String,
    val description: String,
    val projectId: String?,
    val assigneeId: String?,
    val start: DateTimeDraftSelection,
    val durationMinutes: Int?,
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
        durationMinutes: Int? = this.durationMinutes,
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
            durationMinutes = null,
            recurrencePreset = RecurrencePreset.never,
            checklistItems = emptyList()
        )
    }
}

internal fun addTaskDraftFingerprint(
    title: String,
    description: String,
    projectId: String?,
    assigneeId: String?,
    start: DateTimeDraftSelection,
    durationMinutes: Int?,
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

internal data class AddTaskProjectBaselineCapture(
    val fingerprint: AddTaskDraftFingerprint,
    val captured: Boolean
)

internal fun shouldDeferNoProjectBaseline(
    persistedProjectId: String?,
    draftProjectId: String?,
    automaticPersistedProjectId: String?
): Boolean = persistedProjectId != null &&
    draftProjectId == null &&
    automaticPersistedProjectId == null

internal fun captureAddTaskProjectBaseline(
    initialFingerprint: AddTaskDraftFingerprint,
    alreadyCaptured: Boolean,
    automaticPersistedProjectId: String?,
    automaticPersistedAssigneeId: String?,
    deferNoProjectBaseline: Boolean
): AddTaskProjectBaselineCapture = when {
    alreadyCaptured -> AddTaskProjectBaselineCapture(
        fingerprint = initialFingerprint,
        captured = true
    )
    automaticPersistedProjectId == null && deferNoProjectBaseline -> AddTaskProjectBaselineCapture(
        fingerprint = initialFingerprint,
        captured = false
    )
    else -> AddTaskProjectBaselineCapture(
        fingerprint = initialFingerprint.copy(
            projectId = automaticPersistedProjectId,
            assigneeId = automaticPersistedAssigneeId
        ),
        captured = true
    )
}
