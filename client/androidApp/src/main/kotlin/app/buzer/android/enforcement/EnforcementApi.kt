package app.buzer.android.enforcement

import app.buzer.shared.api.BuzerApiClient
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TasksSnapshotResponse

interface EnforcementApi {
    suspend fun getSnapshot(): TasksSnapshotResponse
    suspend fun startTask(id: String)
    suspend fun postponeStart(id: String)
    suspend fun completeTask(id: String)
    suspend fun extendTask(id: String)
    suspend fun rescheduleTask(id: String, request: RescheduleTaskRequest)
    suspend fun dismissMorningReview()
}

class BuzerEnforcementApi(private val client: BuzerApiClient) : EnforcementApi {
    override suspend fun getSnapshot(): TasksSnapshotResponse = client.getSnapshot()

    override suspend fun startTask(id: String) {
        client.startTask(id)
    }

    override suspend fun postponeStart(id: String) {
        client.postponeStart(id)
    }

    override suspend fun completeTask(id: String) {
        client.completeTask(id)
    }

    override suspend fun extendTask(id: String) {
        client.extendTask(id)
    }

    override suspend fun rescheduleTask(id: String, request: RescheduleTaskRequest) {
        client.rescheduleTask(id, request)
    }

    override suspend fun dismissMorningReview() {
        client.dismissMorningReview()
    }
}
