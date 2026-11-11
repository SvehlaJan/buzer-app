package app.buzer.shared.api

import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ExtendTaskRequest
import app.buzer.shared.model.FocusedChecklistMutationRequest
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.model.UpdateTaskRequest

interface TaskBackendClient {
    suspend fun getSnapshot(forceRefresh: Boolean = false): TasksSnapshotResponse
    suspend fun createTask(request: CreateTaskRequest): MutationResponse
    suspend fun startTask(id: String, request: TaskActionRequest): MutationResponse
    suspend fun postponeStart(id: String, request: TaskActionRequest): MutationResponse
    suspend fun completeTask(id: String, request: TaskActionRequest): MutationResponse
    suspend fun extendTask(id: String, request: ExtendTaskRequest): MutationResponse
    suspend fun updateTask(id: String, request: UpdateTaskRequest): MutationResponse
    suspend fun checkChecklistItem(
        id: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse
    suspend fun uncheckChecklistItem(
        id: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse
}
