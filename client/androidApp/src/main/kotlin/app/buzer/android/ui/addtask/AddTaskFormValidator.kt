package app.buzer.android.ui.addtask

import app.buzer.android.ui.datetime.DateTimeSelection
import app.buzer.shared.model.CreateTaskRequest
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
        selectedAssignee: AddTaskAssigneeOption?,
        deadline: DateTimeSelection?,
        durationText: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): AddTaskFormResult {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            return AddTaskFormResult.Invalid("Title is required")
        }
        if (selectedAssignee == null) {
            return AddTaskFormResult.Invalid("Assignee is required")
        }
        if (selectedAssignee.id.isBlank()) {
            return AddTaskFormResult.Invalid("${selectedAssignee.label} assignee ID is not configured")
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
                projectId = null,
                assigneeId = selectedAssignee.id,
                dueDatetime = deadline.toIsoInstantString(zoneId),
                durationMinutes = duration
            )
        )
    }
}
