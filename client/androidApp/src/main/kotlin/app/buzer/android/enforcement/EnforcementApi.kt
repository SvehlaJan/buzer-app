package app.buzer.android.enforcement

import app.buzer.shared.api.BuzerApiClient
import app.buzer.shared.model.FocusedChecklistMutationRequest
import app.buzer.shared.model.ExtendTaskRequest
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.model.UpdateTaskRequest

interface EnforcementApi {
    suspend fun getSnapshot(): TasksSnapshotResponse
    suspend fun startTask(id: String, request: TaskActionRequest): MutationResponse
    suspend fun postponeStart(id: String, request: TaskActionRequest): MutationResponse
    suspend fun completeTask(id: String, request: TaskActionRequest): MutationResponse
    suspend fun extendTask(id: String, request: ExtendTaskRequest): MutationResponse
    suspend fun updateTask(id: String, request: UpdateTaskRequest): MutationResponse
    suspend fun checkChecklistItem(
        taskId: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse
    suspend fun uncheckChecklistItem(
        taskId: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse
    suspend fun dismissMorningReview()
}

class BuzerEnforcementApi(private val client: BuzerApiClient) : EnforcementApi {
    override suspend fun getSnapshot(): TasksSnapshotResponse = client.getSnapshot()

    override suspend fun startTask(id: String, request: TaskActionRequest): MutationResponse =
        client.startTask(id, request)

    override suspend fun postponeStart(id: String, request: TaskActionRequest): MutationResponse =
        client.postponeStart(id, request)

    override suspend fun completeTask(id: String, request: TaskActionRequest): MutationResponse =
        client.completeTask(id, request)

    override suspend fun extendTask(id: String, request: ExtendTaskRequest): MutationResponse =
        client.extendTask(id, request)

    override suspend fun updateTask(id: String, request: UpdateTaskRequest): MutationResponse =
        client.updateTask(id, request)

    override suspend fun checkChecklistItem(
        taskId: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse = client.checkChecklistItem(taskId, childId, request)

    override suspend fun uncheckChecklistItem(
        taskId: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse = client.uncheckChecklistItem(taskId, childId, request)

    override suspend fun dismissMorningReview() {
        client.dismissMorningReview()
    }
}
