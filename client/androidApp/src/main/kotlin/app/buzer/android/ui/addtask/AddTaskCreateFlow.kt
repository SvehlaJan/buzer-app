package app.buzer.android.ui.addtask

import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.MutationOutcome
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.TasksSnapshotResponse

internal sealed interface AddTaskCreateResult {
    data object Created : AddTaskCreateResult
}

internal suspend fun runSuccessfulAddTaskCreate(
    request: CreateTaskRequest,
    createTask: suspend (CreateTaskRequest) -> MutationResponse,
    onTaskCreated: (TasksSnapshotResponse) -> Unit,
    onBack: () -> Unit
): AddTaskCreateResult {
    val response = createTask(request)
    return when (response.outcome) {
        MutationOutcome.completed -> {
            onTaskCreated(response.snapshot)
            onBack()
            AddTaskCreateResult.Created
        }

        MutationOutcome.reconciliation_pending -> error("Task creation is awaiting Todoist confirmation")
    }
}
