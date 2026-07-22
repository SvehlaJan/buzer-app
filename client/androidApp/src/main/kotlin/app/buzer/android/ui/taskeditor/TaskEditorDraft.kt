package app.buzer.android.ui.taskeditor

import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal data class TaskEditorSchedule(
    val date: LocalDate? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val durationMinutes: Int? = null
) {
    fun isComplete(): Boolean =
        date != null && hasValidTime() && (durationMinutes ?: 0) > 0

    fun isEmpty(): Boolean =
        date == null && hour == null && minute == null && durationMinutes == null

    fun selectedTime(): LocalTime? =
        if (hasValidTime()) LocalTime.of(hour!!, minute!!) else null

    fun startDateTime(): LocalDateTime? =
        if (date != null && hasValidTime()) date.atTime(hour!!, minute!!) else null

    private fun hasValidTime(): Boolean = hour in 0..23 && minute in 0..59
}

internal data class TaskEditorDraft(
    val title: String,
    val description: String = "",
    val projectId: String? = null,
    val assignee: ProjectCollaborator? = null,
    val schedule: TaskEditorSchedule = TaskEditorSchedule(),
    val recurrencePreset: RecurrencePreset = RecurrencePreset.never,
    val recurrenceKind: RecurrenceKind = RecurrenceKind.none,
    val originalSchedule: TaskEditorSchedule = schedule,
    val repeatReplacementSelected: Boolean = false
)

internal fun setNoSchedule(draft: TaskEditorDraft): TaskEditorDraft =
    if (draft.assignee?.isCurrentUser == false) {
        draft.copy(
            schedule = TaskEditorSchedule(),
            recurrencePreset = if (draft.recurrenceKind == RecurrenceKind.custom) {
                draft.recurrencePreset
            } else {
                RecurrencePreset.never
            }
        )
    } else {
        draft
    }

internal fun reduceCustomDate(
    draft: TaskEditorDraft,
    pending: LocalDate,
    apply: Boolean
): TaskEditorDraft = if (apply) {
    draft.copy(schedule = draft.schedule.copy(date = pending))
} else {
    draft
}

internal fun reduceCustomTime(
    draft: TaskEditorDraft,
    pending: LocalTime,
    apply: Boolean
): TaskEditorDraft = if (apply) {
    draft.copy(schedule = draft.schedule.copy(hour = pending.hour, minute = pending.minute))
} else {
    draft
}

internal fun reduceCustomDuration(
    draft: TaskEditorDraft,
    pending: Int,
    apply: Boolean
): TaskEditorDraft = if (apply) {
    draft.copy(schedule = draft.schedule.copy(durationMinutes = pending))
} else {
    draft
}
