package app.buzer.shared.api

import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.AddChecklistItemRequest
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.FocusedChecklistMutationRequest
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.ProjectsResponse
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.model.UpdateChecklistItemRequest
import app.buzer.shared.model.UpdateTaskRequest
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json

class BuzerApiException(
    val status: Int,
    val code: String?,
    val retryAfterSeconds: Long?,
    val snapshot: TasksSnapshotResponse?,
    message: String? = null
) : Exception(message ?: code ?: "Buzer API request failed with HTTP $status")

@Serializable
private data class BuzerApiErrorBody(
    val code: String? = null,
    val message: String? = null,
    val snapshot: TasksSnapshotResponse? = null
)

class BuzerApiClient(
    private val baseUrl: String,
    private val token: String,
    private val httpClient: HttpClient = defaultHttpClient(token)
) {
    suspend fun getSnapshot(): TasksSnapshotResponse =
        httpClient.get("$baseUrl/tasks/snapshot").decodeBody()

    suspend fun getProjects(): ProjectsResponse =
        httpClient.get("$baseUrl/projects").decodeBody()

    suspend fun createTask(request: CreateTaskRequest): MutationResponse {
        AppLog.info(
            "api create_task request titleLength=${request.title.length} project=${request.projectId} assignee=${request.assigneeId} " +
                "due=${request.dueDatetime} duration=${request.durationMinutes}"
        )
        return mutationPost("$baseUrl/tasks", request)
    }

    suspend fun startTask(id: String, request: TaskActionRequest): MutationResponse =
        mutationPost("$baseUrl/tasks/$id/start", request)

    suspend fun completeTask(id: String, request: TaskActionRequest): MutationResponse =
        mutationPost("$baseUrl/tasks/$id/done", request)

    suspend fun postponeStart(id: String, request: TaskActionRequest): MutationResponse =
        mutationPost("$baseUrl/tasks/$id/postpone-start", request)

    suspend fun extendTask(id: String, request: TaskActionRequest): MutationResponse =
        mutationPost("$baseUrl/tasks/$id/extend", request)

    suspend fun rescheduleTask(id: String, request: RescheduleTaskRequest): MutationResponse =
        mutationPost("$baseUrl/tasks/$id/reschedule", request)

    suspend fun updateTask(id: String, request: UpdateTaskRequest): MutationResponse =
        mutationPost("$baseUrl/tasks/$id/update", request)

    suspend fun addChecklistItem(id: String, request: AddChecklistItemRequest): MutationResponse =
        mutationPost("$baseUrl/tasks/$id/checklist", request)

    suspend fun updateChecklistItem(
        id: String,
        childId: String,
        request: UpdateChecklistItemRequest
    ): MutationResponse = mutationPost("$baseUrl/tasks/$id/checklist/$childId/update", request)

    suspend fun deleteChecklistItem(
        id: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse = mutationPost("$baseUrl/tasks/$id/checklist/$childId/delete", request)

    suspend fun checkChecklistItem(
        id: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse = mutationPost("$baseUrl/tasks/$id/checklist/$childId/check", request)

    suspend fun uncheckChecklistItem(
        id: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse = mutationPost("$baseUrl/tasks/$id/checklist/$childId/uncheck", request)

    suspend fun dismissMorningReview() {
        httpClient.post("$baseUrl/review/dismiss") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { })
        }.ensureSuccess()
    }

    private suspend fun mutationPost(url: String, body: Any): MutationResponse =
        httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.decodeBody()

}

private suspend inline fun <reified T> HttpResponse.decodeBody(): T {
    ensureSuccess()
    return body()
}

private suspend fun HttpResponse.ensureSuccess() {
    if (status.isSuccess()) return
    val error = bodyAsText().toBuzerApiException(status.value, headers[HttpHeaders.RetryAfter])
    AppLog.error("api http_error status=${error.status} code=${error.code}")
    throw error
}

private fun String.toBuzerApiException(status: Int, retryAfterHeader: String?): BuzerApiException {
    val parsed = runCatching { buzerApiJson.decodeFromString<BuzerApiErrorBody>(this) }.getOrNull()
    return BuzerApiException(
        status = status,
        code = parsed?.code,
        retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull(),
        snapshot = parsed?.snapshot,
        message = parsed?.message
    )
}

private const val DEFAULT_TIMEOUT_MILLIS = 5_000L

internal fun defaultHttpClient(
    token: String,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    engine: HttpClientEngine? = null
): HttpClient {
    val config: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = timeoutMillis
            requestTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    AppLog.debug("http $message")
                }
            }
            level = LogLevel.HEADERS
            sanitizeHeader { header -> header == HttpHeaders.Authorization }
        }
        install(ContentNegotiation) {
            json(buzerApiJson)
        }
        defaultRequest {
            bearerAuth(token)
        }
    }
    return if (engine != null) HttpClient(engine, config) else HttpClient(config)
}

internal val buzerApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
