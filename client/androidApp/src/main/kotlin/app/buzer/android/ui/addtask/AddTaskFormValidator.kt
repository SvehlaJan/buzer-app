package app.buzer.android.ui.addtask

import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.shared.model.ChecklistDraft
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.RecurrencePreset
import java.time.ZoneId

sealed interface AddTaskFormResult {
    data class Valid(val request: CreateTaskRequest) : AddTaskFormResult
    data class Invalid(
        val message: String,
        val field: AddTaskInvalidField? = null
    ) : AddTaskFormResult
}

enum class AddTaskInvalidField {
    Title,
    Project,
    Assignee,
    Start,
    Duration
}

object AddTaskFormValidator {
    fun validate(
        title: String,
        selectedProject: ProjectItem?,
        selectedAssignee: ProjectCollaborator?,
        deadline: DateTimeSelection?,
        durationMinutes: Int?,
        zoneId: ZoneId = ZoneId.systemDefault(),
        description: String = "",
        actionId: String = "test-action-id",
        deviceId: String = "test-device-id",
        recurrencePreset: RecurrencePreset = RecurrencePreset.never,
        timeZone: String? = zoneId.id,
        checklistDrafts: List<ChecklistDraft> = emptyList()
    ): AddTaskFormResult {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            return AddTaskFormResult.Invalid("Title is required", AddTaskInvalidField.Title)
        }
        if (selectedProject == null) {
            return AddTaskFormResult.Invalid("Project is required", AddTaskInvalidField.Project)
        }
        if (selectedAssignee == null) {
            return AddTaskFormResult.Invalid(
                "Assignee is required for this project",
                AddTaskInvalidField.Assignee
            )
        }
        if (selectedAssignee.id.isBlank()) {
            return AddTaskFormResult.Invalid(
                "${selectedAssignee.displayName} assignee ID is not configured",
                AddTaskInvalidField.Assignee
            )
        }

        val scheduleRequired = selectedAssignee.isCurrentUser
        val scheduleComplete = deadline != null && (durationMinutes ?: 0) > 0
        val scheduleEmpty = deadline == null && durationMinutes == null
        if (!scheduleComplete && !scheduleEmpty) {
            return invalidScheduleResult(deadline, durationMinutes)
        }
        if (scheduleRequired && !scheduleComplete) {
            return invalidScheduleResult(deadline, durationMinutes)
        }

        val normalizedRecurrencePreset = if (scheduleEmpty) RecurrencePreset.never else recurrencePreset

        return AddTaskFormResult.Valid(
            CreateTaskRequest(
                title = trimmedTitle,
                description = description.normalizedOptionalText(),
                projectId = selectedProject.id,
                assigneeId = selectedAssignee.id.takeIf { selectedProject.canAssignTasks },
                dueDatetime = deadline?.toIsoInstantString(zoneId),
                durationMinutes = durationMinutes,
                actionId = actionId,
                deviceId = deviceId,
                recurrencePreset = normalizedRecurrencePreset,
                timeZone = timeZone?.takeIf {
                    scheduleComplete && normalizedRecurrencePreset != RecurrencePreset.never
                },
                checklistDrafts = checklistDrafts
            )
        )
    }

    private fun String.normalizedOptionalText(): String? =
        trim().takeIf { it.isNotEmpty() }

    private fun invalidScheduleResult(
        deadline: DateTimeSelection?,
        durationMinutes: Int?
    ): AddTaskFormResult.Invalid = when {
        deadline == null -> AddTaskFormResult.Invalid("Start time is required", AddTaskInvalidField.Start)
        durationMinutes == null -> AddTaskFormResult.Invalid("Duration is required", AddTaskInvalidField.Duration)
        else -> AddTaskFormResult.Invalid("Duration must be greater than zero", AddTaskInvalidField.Duration)
    }
}
