package app.buzer.android.ui.addtask

import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.android.ui.taskeditor.TaskEditorDraft
import app.buzer.android.ui.taskeditor.TaskEditorField
import app.buzer.android.ui.taskeditor.TaskEditorSchedule
import app.buzer.android.ui.taskeditor.TaskEditorValidation
import app.buzer.android.ui.taskeditor.validateTaskEditor
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

/** Compatibility bridge for the legacy Add screen until it consumes TaskEditorValidation directly. */
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
        val draft = TaskEditorDraft(
            title = title,
            description = description,
            projectId = selectedProject?.id,
            assignee = selectedAssignee,
            schedule = deadline.toTaskEditorSchedule(durationMinutes),
            recurrencePreset = recurrencePreset
        )
        val validation = validateTaskEditor(
            draft = draft,
            selectedProject = selectedProject,
            projectAvailable = selectedProject != null,
            collaboratorsAvailable = selectedProject?.let {
                !it.canAssignTasks || it.collaboratorsReady
            } == true
        )
        validation.compatibilityInvalid()?.let { return it }

        return AddTaskFormResult.Valid(
            CreateTaskRequest(
                title = title.trim(),
                description = description.normalizedOptionalText(),
                projectId = selectedProject!!.id,
                assigneeId = selectedAssignee!!.id.takeIf { selectedProject.canAssignTasks },
                dueDatetime = deadline?.toIsoInstantString(zoneId),
                durationMinutes = durationMinutes,
                actionId = actionId,
                deviceId = deviceId,
                recurrencePreset = recurrencePreset,
                timeZone = timeZone?.takeIf {
                    draft.schedule.isComplete() && recurrencePreset != RecurrencePreset.never
                },
                checklistDrafts = checklistDrafts
            )
        )
    }
}

private fun DateTimeSelection?.toTaskEditorSchedule(durationMinutes: Int?): TaskEditorSchedule = when (this) {
    null -> TaskEditorSchedule(durationMinutes = durationMinutes)
    else -> TaskEditorSchedule(date = date, hour = hour, minute = minute, durationMinutes = durationMinutes)
}

private fun TaskEditorValidation.compatibilityInvalid(): AddTaskFormResult.Invalid? =
    errors.entries.firstOrNull()?.let { (field, message) ->
        AddTaskFormResult.Invalid(
            message = message,
            field = when (field) {
                TaskEditorField.Title -> AddTaskInvalidField.Title
                TaskEditorField.Project -> AddTaskInvalidField.Project
                TaskEditorField.Assignee -> AddTaskInvalidField.Assignee
                TaskEditorField.Schedule -> when (message) {
                    "Start time is required" -> AddTaskInvalidField.Start
                    else -> AddTaskInvalidField.Duration
                }
                TaskEditorField.Repeat -> null
            }
        )
    }
        ?: unavailableFields.firstOrNull()?.let { field ->
            when (field) {
                TaskEditorField.Project -> AddTaskFormResult.Invalid(
                    "Project is unavailable. Retry before saving.",
                    AddTaskInvalidField.Project
                )
                TaskEditorField.Assignee -> AddTaskFormResult.Invalid(
                    "Collaborators are unavailable. Retry before saving.",
                    AddTaskInvalidField.Assignee
                )
                else -> null
            }
        }

private fun String.normalizedOptionalText(): String? = trim().takeIf { it.isNotEmpty() }
