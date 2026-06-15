package app.buzer.android.ui.addtask

import app.buzer.shared.model.CreateTaskRequest

internal suspend fun runSuccessfulAddTaskCreate(
    request: CreateTaskRequest,
    createTask: suspend (CreateTaskRequest) -> Unit,
    onTaskCreated: () -> Unit,
    onBack: () -> Unit
) {
    createTask(request)
    onTaskCreated()
    onBack()
}
