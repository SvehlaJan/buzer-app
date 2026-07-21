package app.buzer.android.ui.taskeditor

import app.buzer.shared.model.ProjectCollaborator
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
        date != null && hour != null && minute != null && durationMinutes != null

    fun isEmpty(): Boolean =
        date == null && hour == null && minute == null && durationMinutes == null

    fun selectedTime(): LocalTime? =
        if (hour != null && minute != null) LocalTime.of(hour, minute) else null

    fun startDateTime(): LocalDateTime? =
        if (date != null && hour != null && minute != null) date.atTime(hour, minute) else null
}

internal data class TaskEditorDraft(
    val title: String,
    val description: String = "",
    val projectId: String? = null,
    val assignee: ProjectCollaborator? = null,
    val schedule: TaskEditorSchedule = TaskEditorSchedule(),
    val recurrencePreset: RecurrencePreset = RecurrencePreset.never
)

internal fun setNoSchedule(draft: TaskEditorDraft): TaskEditorDraft =
    if (draft.assignee?.isCurrentUser == false) {
        draft.copy(
            schedule = TaskEditorSchedule(),
            recurrencePreset = RecurrencePreset.never
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
