package app.buzer.android.ui.taskeditor

import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset

internal enum class TaskEditorField {
    Title,
    Project,
    Assignee,
    Schedule,
    Repeat
}

internal enum class ValidationExposure {
    OnEntry,
    OnInteraction
}

internal data class TaskEditorPresentation(
    val screenTitle: String,
    val primaryVerb: String,
    val contextMessage: String? = null
)

internal data class TaskEditorValidation(
    val errors: Map<TaskEditorField, String>,
    val unavailableFields: Set<TaskEditorField> = emptySet()
) {
    val canSubmit: Boolean
        get() = errors.isEmpty() && unavailableFields.isEmpty()

    fun visible(
        exposure: ValidationExposure,
        touchedFields: Set<TaskEditorField>,
        submitAttempted: Boolean
    ): VisibleTaskEditorValidation {
        val visibleErrors = when {
            exposure == ValidationExposure.OnEntry || submitAttempted -> errors
            else -> errors.filterKeys { field ->
                field in touchedFields || field.isDependentOn(touchedFields)
            }
        }

        return VisibleTaskEditorValidation(
            errors = visibleErrors,
            unavailableFields = unavailableFields,
            canSubmit = canSubmit,
            bannerMessage = "Fix the highlighted fields".takeIf { visibleErrors.isNotEmpty() }
        )
    }

    private fun TaskEditorField.isDependentOn(touchedFields: Set<TaskEditorField>): Boolean = when (this) {
        TaskEditorField.Assignee -> TaskEditorField.Project in touchedFields
        TaskEditorField.Schedule -> TaskEditorField.Assignee in touchedFields
        TaskEditorField.Repeat -> TaskEditorField.Schedule in touchedFields
        else -> false
    }
}

internal data class VisibleTaskEditorValidation(
    val errors: Map<TaskEditorField, String>,
    val unavailableFields: Set<TaskEditorField>,
    val canSubmit: Boolean,
    val bannerMessage: String?
) {
    val titleError: String?
        get() = errors[TaskEditorField.Title]
    val projectError: String?
        get() = errors[TaskEditorField.Project]
    val assigneeError: String?
        get() = errors[TaskEditorField.Assignee]
    val scheduleError: String?
        get() = errors[TaskEditorField.Schedule]
    val repeatError: String?
        get() = errors[TaskEditorField.Repeat]
}

internal fun validateTaskEditor(
    draft: TaskEditorDraft,
    selectedProject: ProjectItem?,
    projectAvailable: Boolean = selectedProject != null,
    collaboratorsAvailable: Boolean = selectedProject?.let {
        !it.canAssignTasks || it.collaboratorsReady
    } == true
): TaskEditorValidation {
    val errors = buildMap {
        if (draft.title.trim().isEmpty()) {
            put(TaskEditorField.Title, "Title is required")
        }
        if (draft.projectId.isNullOrBlank()) {
            put(TaskEditorField.Project, "Project is required")
        }
        when {
            draft.assignee == null -> put(TaskEditorField.Assignee, "Assignee is required for this project")
            draft.assignee.id.isBlank() -> put(
                TaskEditorField.Assignee,
                "${draft.assignee.displayName} assignee ID is not configured"
            )
            selectedProject != null && collaboratorsAvailable &&
                selectedProject.collaborators.none { it.id == draft.assignee.id } -> put(
                    TaskEditorField.Assignee,
                    "Assignee is unavailable for this project"
                )
        }
        val schedule = draft.schedule
        val scheduleComplete = schedule.isComplete()
        val scheduleEmpty = schedule.isEmpty()
        val scheduleRequired = draft.assignee?.isCurrentUser != false
        if (!scheduleComplete && (scheduleRequired || !scheduleEmpty)) {
            put(TaskEditorField.Schedule, scheduleError(schedule))
        }
        if (scheduleEmpty && draft.assignee?.isCurrentUser == false && draft.recurrencePreset != RecurrencePreset.never) {
            put(TaskEditorField.Repeat, "Repeat requires a schedule")
        }
        if (draft.recurrenceKind == RecurrenceKind.custom &&
            draft.schedule != draft.originalSchedule &&
            !draft.repeatReplacementSelected
        ) {
            put(TaskEditorField.Repeat, "Choose a supported repeat before saving")
        }
    }
    val unavailableFields = buildSet {
        if (!projectAvailable) add(TaskEditorField.Project)
        if (!collaboratorsAvailable) add(TaskEditorField.Assignee)
    }

    return TaskEditorValidation(errors = errors, unavailableFields = unavailableFields)
}

private fun scheduleError(schedule: TaskEditorSchedule): String = when {
    schedule.date == null || schedule.hour == null || schedule.minute == null -> "Start time is required"
    schedule.durationMinutes == null -> "Duration is required"
    schedule.durationMinutes <= 0 -> "Duration must be greater than zero"
    else -> "Start time is required"
}
