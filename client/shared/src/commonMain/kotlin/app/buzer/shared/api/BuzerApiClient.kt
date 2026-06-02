package app.buzer.shared.api

import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TasksSnapshotResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json

class BuzerApiClient(
    private val baseUrl: String,
    private val token: String,
    private val httpClient: HttpClient = defaultHttpClient(token)
) {
    suspend fun getSnapshot(): TasksSnapshotResponse =
        httpClient.get("$baseUrl/tasks/snapshot").body()

    suspend fun createTask(request: CreateTaskRequest) {
        httpClient.post("$baseUrl/tasks") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun startTask(id: String) {
        emptyJsonPost("$baseUrl/tasks/$id/start")
    }

    suspend fun completeTask(id: String) {
        emptyJsonPost("$baseUrl/tasks/$id/done")
    }

    suspend fun postponeStart(id: String) {
        emptyJsonPost("$baseUrl/tasks/$id/postpone-start")
    }

    suspend fun extendTask(id: String) {
        emptyJsonPost("$baseUrl/tasks/$id/extend")
    }

    suspend fun rescheduleTask(id: String, request: RescheduleTaskRequest) {
        httpClient.post("$baseUrl/tasks/$id/reschedule") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    private suspend fun emptyJsonPost(url: String) {
        httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { })
        }
    }
}

private fun defaultHttpClient(token: String): HttpClient =
    HttpClient {
        expectSuccess = true
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            })
        }
        defaultRequest {
            bearerAuth(token)
        }
    }
