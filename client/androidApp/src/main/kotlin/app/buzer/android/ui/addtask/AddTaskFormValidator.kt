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
        selectedAssignee: AddTaskAssigneeOption?,
        deadline: DateTimeSelection?,
        durationMinutes: Int,
        zoneId: ZoneId = ZoneId.systemDefault(),
        description: String = ""
    ): AddTaskFormResult {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            return AddTaskFormResult.Invalid("Title is required", AddTaskInvalidField.Title)
        }
        if (selectedProject == null) {
            return AddTaskFormResult.Invalid("Project is required", AddTaskInvalidField.Project)
        }
        if (selectedProject.canAssignTasks) {
            if (selectedAssignee == null) {
                return AddTaskFormResult.Invalid(
                    "Assignee is required for this project",
                    AddTaskInvalidField.Assignee
                )
            }
            if (selectedAssignee.id.isBlank()) {
                return AddTaskFormResult.Invalid(
                    "${selectedAssignee.label} assignee ID is not configured",
                    AddTaskInvalidField.Assignee
                )
            }
        }
        if (deadline == null) {
            return AddTaskFormResult.Invalid("Start time is required", AddTaskInvalidField.Start)
        }

        return AddTaskFormResult.Valid(
            CreateTaskRequest(
                title = trimmedTitle,
                description = description.normalizedOptionalText(),
                projectId = selectedProject.id,
                assigneeId = selectedAssignee?.id?.takeIf { selectedProject.canAssignTasks },
                dueDatetime = deadline.toIsoInstantString(zoneId),
                durationMinutes = durationMinutes
            )
        )
    }

    private fun String.normalizedOptionalText(): String? =
        trim().takeIf { it.isNotEmpty() }
}
