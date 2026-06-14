package app.buzer.android.ui.addtask

import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ProjectItem
import java.time.ZoneId

data class AddTaskAssigneeOption(
    val label: String,
    val id: String
)

sealed interface AddTaskFormResult {
    data class Valid(val request: CreateTaskRequest) : AddTaskFormResult
    data class Invalid(val message: String) : AddTaskFormResult
}

object AddTaskFormValidator {
    fun validate(
        title: String,
        selectedProject: ProjectItem?,
        selectedAssignee: AddTaskAssigneeOption?,
        deadline: DateTimeSelection?,
        durationText: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): AddTaskFormResult {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            return AddTaskFormResult.Invalid("Title is required")
        }
        if (selectedProject == null) {
            return AddTaskFormResult.Invalid("Project is required")
        }
        if (selectedProject.canAssignTasks) {
            if (selectedAssignee == null) {
                return AddTaskFormResult.Invalid("Assignee is required for this project")
            }
            if (selectedAssignee.id.isBlank()) {
                return AddTaskFormResult.Invalid("${selectedAssignee.label} assignee ID is not configured")
            }
        }
        if (deadline == null) {
            return AddTaskFormResult.Invalid("Deadline is required")
        }

        val duration = durationText.trim().toIntOrNull()
        if (duration == null || duration <= 0) {
            return AddTaskFormResult.Invalid("Duration must be a positive whole number of minutes")
        }

        return AddTaskFormResult.Valid(
            CreateTaskRequest(
                title = trimmedTitle,
                projectId = selectedProject.id,
                assigneeId = selectedAssignee?.id?.takeIf { selectedProject.canAssignTasks },
                dueDatetime = deadline.toIsoInstantString(zoneId),
                durationMinutes = duration
            )
        )
    }
}
