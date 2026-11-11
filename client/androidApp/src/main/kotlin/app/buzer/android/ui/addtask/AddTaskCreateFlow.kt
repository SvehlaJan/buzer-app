package app.buzer.android.ui.addtask

import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.shared.model.CreateTaskRequest

internal sealed interface AddTaskCreateOutcome {
    data object NavigatedBack : AddTaskCreateOutcome
    data class ShowWarning(val message: String) : AddTaskCreateOutcome
}

internal suspend fun runAddTaskCreate(
    request: CreateTaskRequest,
    onCreateTask: suspend (CreateTaskRequest) -> CoordinatorActionResult,
    onBack: () -> Unit
): AddTaskCreateOutcome = when (val result = onCreateTask(request)) {
    CoordinatorActionResult.Success -> {
        onBack()
        AddTaskCreateOutcome.NavigatedBack
    }
    is CoordinatorActionResult.Warning -> AddTaskCreateOutcome.ShowWarning(result.message)
    is CoordinatorActionResult.Conflict -> AddTaskCreateOutcome.ShowWarning(result.message)
    is CoordinatorActionResult.TransportFailure -> AddTaskCreateOutcome.ShowWarning(result.message)
    is CoordinatorActionResult.LocalReconciliationFailed -> AddTaskCreateOutcome.ShowWarning(result.message)
    CoordinatorActionResult.ChildActionInFlight ->
        AddTaskCreateOutcome.ShowWarning("Checklist update is already in progress")
}
