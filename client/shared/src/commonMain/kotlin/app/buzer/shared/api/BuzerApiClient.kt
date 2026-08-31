package app.buzer.shared.api

import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.AddChecklistItemRequest
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ExtendTaskRequest
import app.buzer.shared.model.FocusedChecklistMutationRequest
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.ProjectsResponse
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.model.UpdateChecklistItemRequest
import app.buzer.shared.model.UpdateTaskRequest
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.TimeSource

class BuzerApiException(
    val status: Int,
    val code: String? = null,
    val retryAfterSeconds: Long? = null,
    val snapshot: TasksSnapshotResponse? = null,
    val requestId: String? = null,
    message: String? = null
) : Exception(message ?: code ?: "Buzer API request failed with HTTP $status")

class BuzerApiRequestCorrelation(val requestId: String) : Exception("request_id=$requestId")

@Serializable
private data class BuzerApiErrorBody(
    val code: String? = null,
    val message: String? = null,
    val snapshot: TasksSnapshotResponse? = null
)

class BuzerApiClient(
    private val baseUrl: String,
    private val token: String,
    private val httpClient: HttpClient = defaultHttpClient(token),
    private val requestIdProvider: () -> String = { generateUuid() },
    private val isConnectedProvider: (() -> Boolean)? = null
) {
    suspend fun getSnapshot(forceRefresh: Boolean = false): TasksSnapshotResponse {
        val path = if (forceRefresh) "/tasks/snapshot?forceRefresh=true" else "/tasks/snapshot"
        val timeout = if (forceRefresh) MUTATION_TIMEOUT_MILLIS else null
        return execute(HttpMethod.Get, path, timeoutMillis = timeout)
    }

    suspend fun getProjects(): ProjectsResponse =
        execute(HttpMethod.Get, "/projects")

    suspend fun createTask(request: CreateTaskRequest): MutationResponse =
        execute(HttpMethod.Post, "/tasks", body = request, timeoutMillis = MUTATION_TIMEOUT_MILLIS)

    suspend fun startTask(id: String, request: TaskActionRequest): MutationResponse =
        execute(HttpMethod.Post, "/tasks/$id/start", body = request, timeoutMillis = MUTATION_TIMEOUT_MILLIS)

    suspend fun completeTask(id: String, request: TaskActionRequest): MutationResponse =
        execute(HttpMethod.Post, "/tasks/$id/done", body = request, timeoutMillis = MUTATION_TIMEOUT_MILLIS)

    suspend fun postponeStart(id: String, request: TaskActionRequest): MutationResponse =
        execute(HttpMethod.Post, "/tasks/$id/postpone-start", body = request, timeoutMillis = MUTATION_TIMEOUT_MILLIS)

    suspend fun extendTask(id: String, request: ExtendTaskRequest): MutationResponse =
        execute(HttpMethod.Post, "/tasks/$id/extend", body = request, timeoutMillis = MUTATION_TIMEOUT_MILLIS)

    suspend fun updateTask(id: String, request: UpdateTaskRequest): MutationResponse =
        execute(HttpMethod.Post, "/tasks/$id/update", body = request, timeoutMillis = MUTATION_TIMEOUT_MILLIS)

    suspend fun addChecklistItem(id: String, request: AddChecklistItemRequest): MutationResponse =
        execute(HttpMethod.Post, "/tasks/$id/checklist", body = request, timeoutMillis = MUTATION_TIMEOUT_MILLIS)

    suspend fun updateChecklistItem(
        id: String,
        childId: String,
        request: UpdateChecklistItemRequest
    ): MutationResponse =
        execute(
            HttpMethod.Post,
            "/tasks/$id/checklist/$childId/update",
            body = request,
            timeoutMillis = MUTATION_TIMEOUT_MILLIS
        )

    suspend fun deleteChecklistItem(
        id: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse =
        execute(
            HttpMethod.Post,
            "/tasks/$id/checklist/$childId/delete",
            body = request,
            timeoutMillis = MUTATION_TIMEOUT_MILLIS
        )

    suspend fun checkChecklistItem(
        id: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse =
        execute(
            HttpMethod.Post,
            "/tasks/$id/checklist/$childId/check",
            body = request,
            timeoutMillis = MUTATION_TIMEOUT_MILLIS
        )

    suspend fun uncheckChecklistItem(
        id: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse =
        execute(
            HttpMethod.Post,
            "/tasks/$id/checklist/$childId/uncheck",
            body = request,
            timeoutMillis = MUTATION_TIMEOUT_MILLIS
        )

    private suspend inline fun <reified T> execute(
        httpMethod: HttpMethod,
        path: String,
        body: Any? = null,
        timeoutMillis: Long? = null
    ): T {
        val requestId = requestIdProvider()
        val route = path.substringBefore('?')
        val timeMark = TimeSource.Monotonic.markNow()

        val startAttributes = mutableMapOf(
            "request_id" to requestId,
            "route" to route,
            "method" to httpMethod.value
        )
        isConnectedProvider?.let { startAttributes["is_connected"] = it().toString() }
        AppLog.info("backend_request_started", startAttributes)

        val response = try {
            httpClient.request("$baseUrl$path") {
                method = httpMethod
                header("X-Request-ID", requestId)
                if (timeoutMillis != null) {
                    timeout {
                        requestTimeoutMillis = timeoutMillis
                        socketTimeoutMillis = timeoutMillis
                    }
                }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            val durationMs = timeMark.elapsedNow().inWholeMilliseconds
            val failAttributes = mutableMapOf(
                "request_id" to requestId,
                "route" to route,
                "method" to httpMethod.value,
                "duration_ms" to durationMs.toString()
            )
            isConnectedProvider?.let { failAttributes["is_connected"] = it().toString() }
            AppLog.warn("backend_request_failed", throwable, failAttributes)
            throw throwable.toRequestFailure(requestId)
        }

        val responseText = try {
            response.bodyAsText()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            val durationMs = timeMark.elapsedNow().inWholeMilliseconds
            val failAttributes = mutableMapOf(
                "request_id" to requestId,
                "route" to route,
                "method" to httpMethod.value,
                "status_code" to response.status.value.toString(),
                "duration_ms" to durationMs.toString()
            )
            isConnectedProvider?.let { failAttributes["is_connected"] = it().toString() }
            AppLog.warn("backend_request_failed", throwable, failAttributes)
            throw throwable.toRequestFailure(requestId)
        }

        val durationMs = timeMark.elapsedNow().inWholeMilliseconds

        if (response.status.isSuccess()) {
            return try {
                buzerApiJson.decodeFromString<T>(responseText)
            } catch (throwable: Throwable) {
                val decodeAttributes = mutableMapOf(
                    "request_id" to requestId,
                    "route" to route,
                    "method" to httpMethod.value,
                    "status_code" to response.status.value.toString(),
                    "duration_ms" to durationMs.toString()
                )
                isConnectedProvider?.let { decodeAttributes["is_connected"] = it().toString() }
                AppLog.error("backend_request_failed", throwable, decodeAttributes)
                throw throwable.toRequestFailure(requestId)
            }
        }

        val error = try {
            responseText.toBuzerApiException(
                response.status.value,
                response.headers[HttpHeaders.RetryAfter],
                requestId
            )
        } catch (throwable: SerializationException) {
            val errorAttributes = mutableMapOf(
                "request_id" to requestId,
                "route" to route,
                "method" to httpMethod.value,
                "status_code" to response.status.value.toString(),
                "duration_ms" to durationMs.toString()
            )
            isConnectedProvider?.let { errorAttributes["is_connected"] = it().toString() }
            AppLog.error("backend_request_failed", throwable, errorAttributes)
            throw throwable.toRequestFailure(requestId)
        }

        val errorAttributes = mutableMapOf(
            "request_id" to requestId,
            "route" to route,
            "method" to httpMethod.value,
            "status_code" to response.status.value.toString(),
            "duration_ms" to durationMs.toString()
        )
        error.code?.let { errorAttributes["error_code"] = it }
        isConnectedProvider?.let { errorAttributes["is_connected"] = it().toString() }
        AppLog.warn("backend_request_failed", error, errorAttributes)
        throw error
    }
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun generateUuid(): String = kotlin.uuid.Uuid.random().toString()

private fun String.toBuzerApiException(
    status: Int,
    retryAfterHeader: String?,
    requestId: String
): BuzerApiException {
    val decoded = runCatching { buzerApiJson.decodeFromString<BuzerApiErrorBody>(this) }
    if (decoded.isFailure) {
        val containsSnapshot = runCatching {
            val snapshotElement = buzerApiJson.parseToJsonElement(this).jsonObject["snapshot"]
            snapshotElement != null && snapshotElement !is JsonNull
        }.getOrDefault(false)
        if (containsSnapshot) throw decoded.exceptionOrNull()!!
    }
    val parsed = decoded.getOrNull()
    if (status == 409 && parsed?.snapshot == null) {
        throw SerializationException("HTTP 409 response must include an authoritative snapshot")
    }
    return BuzerApiException(
        status = status,
        code = parsed?.code,
        retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull(),
        snapshot = parsed?.snapshot,
        requestId = requestId,
        message = parsed?.message
    )
}

private fun Throwable.toRequestFailure(requestId: String): Throwable {
    if (this is CancellationException) throw this
    if (suppressedExceptions.none { it is BuzerApiRequestCorrelation && it.requestId == requestId }) {
        addSuppressed(BuzerApiRequestCorrelation(requestId))
    }
    return this
}

private const val DEFAULT_TIMEOUT_MILLIS = 5_000L
private const val MUTATION_TIMEOUT_MILLIS = 15_000L

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
