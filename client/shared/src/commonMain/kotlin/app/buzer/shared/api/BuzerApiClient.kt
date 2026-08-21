package app.buzer.shared.api

import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.diagnostics.DiagnosticNetworkExchange
import app.buzer.shared.diagnostics.DiagnosticRedactor
import app.buzer.shared.diagnostics.DiagnosticReportRequest
import app.buzer.shared.diagnostics.DiagnosticReportResponse
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
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.TimeSource
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

fun interface NetworkDiagnosticSink {
    fun record(exchange: DiagnosticNetworkExchange)
}

data class TraceHeaders(
    val traceId: String? = null,
    val sentryTrace: String? = null,
    val baggage: String? = null
)

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
    private val httpClient: HttpClient = defaultHttpClient(token),
    private val networkSink: NetworkDiagnosticSink? = null,
    private val requestIdProvider: () -> String = { generateUuid() },
    private val traceHeaderProvider: () -> TraceHeaders? = { null },
    private val redactor: DiagnosticRedactor = DiagnosticRedactor()
) {
    suspend fun getSnapshot(): TasksSnapshotResponse =
        execute(HttpMethod.Get, "/tasks/snapshot")

    suspend fun forceSync(): TasksSnapshotResponse =
        execute(HttpMethod.Post, "/tasks/sync", body = buildJsonObject { })

    suspend fun getProjects(): ProjectsResponse =
        execute(HttpMethod.Get, "/projects")

    suspend fun createTask(request: CreateTaskRequest): MutationResponse {
        AppLog.info(
            "api create_task request titleLength=${request.title.length} project=${request.projectId} assignee=${request.assigneeId} " +
                "due=${request.dueDatetime} duration=${request.durationMinutes}"
        )
        return execute(HttpMethod.Post, "/tasks", body = request)
    }

    suspend fun startTask(id: String, request: TaskActionRequest): MutationResponse =
        execute(HttpMethod.Post, "/tasks/$id/start", body = request)

    suspend fun completeTask(id: String, request: TaskActionRequest): MutationResponse =
        execute(HttpMethod.Post, "/tasks/$id/done", body = request)

    suspend fun postponeStart(id: String, request: TaskActionRequest): MutationResponse =
        execute(HttpMethod.Post, "/tasks/$id/postpone-start", body = request)

    suspend fun extendTask(id: String, request: ExtendTaskRequest): MutationResponse =
        execute(HttpMethod.Post, "/tasks/$id/extend", body = request)

    suspend fun updateTask(id: String, request: UpdateTaskRequest): MutationResponse =
        execute(HttpMethod.Post, "/tasks/$id/update", body = request)

    suspend fun addChecklistItem(id: String, request: AddChecklistItemRequest): MutationResponse =
        execute(HttpMethod.Post, "/tasks/$id/checklist", body = request)

    suspend fun updateChecklistItem(
        id: String,
        childId: String,
        request: UpdateChecklistItemRequest
    ): MutationResponse = execute(HttpMethod.Post, "/tasks/$id/checklist/$childId/update", body = request)

    suspend fun deleteChecklistItem(
        id: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse = execute(HttpMethod.Post, "/tasks/$id/checklist/$childId/delete", body = request)

    suspend fun checkChecklistItem(
        id: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse = execute(HttpMethod.Post, "/tasks/$id/checklist/$childId/check", body = request)

    suspend fun uncheckChecklistItem(
        id: String,
        childId: String,
        request: FocusedChecklistMutationRequest
    ): MutationResponse = execute(HttpMethod.Post, "/tasks/$id/checklist/$childId/uncheck", body = request)

    suspend fun createDiagnosticReport(
        request: DiagnosticReportRequest
    ): DiagnosticReportResponse =
        execute(
            httpMethod = HttpMethod.Post,
            path = "/diagnostics/reports",
            body = request,
            timeoutMillis = 5_000L
        )

    private fun serializeRequestBody(body: Any?): String? = when (body) {
        null -> null
        is String -> body
        is JsonObject -> body.toString()
        is CreateTaskRequest -> buzerApiJson.encodeToString(CreateTaskRequest.serializer(), body)
        is UpdateTaskRequest -> buzerApiJson.encodeToString(UpdateTaskRequest.serializer(), body)
        is ExtendTaskRequest -> buzerApiJson.encodeToString(ExtendTaskRequest.serializer(), body)
        is TaskActionRequest -> buzerApiJson.encodeToString(TaskActionRequest.serializer(), body)
        is AddChecklistItemRequest -> buzerApiJson.encodeToString(AddChecklistItemRequest.serializer(), body)
        is UpdateChecklistItemRequest -> buzerApiJson.encodeToString(UpdateChecklistItemRequest.serializer(), body)
        is FocusedChecklistMutationRequest -> buzerApiJson.encodeToString(FocusedChecklistMutationRequest.serializer(), body)
        is DiagnosticReportRequest -> buzerApiJson.encodeToString(DiagnosticReportRequest.serializer(), body)
        else -> body.toString()
    }

    private fun extractActionId(body: Any?): String? = when (body) {
        is CreateTaskRequest -> body.actionId
        is TaskActionRequest -> body.actionId
        is UpdateTaskRequest -> body.actionId
        is ExtendTaskRequest -> body.actionId
        is AddChecklistItemRequest -> body.actionId
        is UpdateChecklistItemRequest -> body.actionId
        is FocusedChecklistMutationRequest -> body.actionId
        is DiagnosticReportRequest -> body.actionId
        else -> null
    }

    private fun sanitizeBody(raw: String?): Pair<String?, Boolean> {
        if (raw == null) return null to false
        val redacted = redactor.redactString(raw)
        return if (redacted.length > MAX_BODY_LENGTH) {
            redacted.take(MAX_BODY_LENGTH) to true
        } else {
            redacted to false
        }
    }

    private suspend inline fun <reified T> execute(
        httpMethod: HttpMethod,
        path: String,
        body: Any? = null,
        timeoutMillis: Long? = null
    ): T {
        val rawRequestBody = serializeRequestBody(body)
        val requestId = requestIdProvider()
        val traceHeaders = traceHeaderProvider()
        val traceId = traceHeaders?.traceId
        val actionId = extractActionId(body)
        val startedAt = Clock.System.now().toString()
        val startTime = TimeSource.Monotonic.markNow()

        var status: Int? = null
        var outcome = "success"
        var responseContentType: String? = null
        var rawResponseBody: String? = null
        var failure: Throwable? = null
        var result: T? = null

        try {
            val response = httpClient.request("$baseUrl$path") {
                method = httpMethod
                header("X-Request-ID", requestId)
                traceHeaders?.sentryTrace?.takeIf { it.isNotBlank() }?.let { header("sentry-trace", it) }
                traceHeaders?.baggage?.takeIf { it.isNotBlank() }?.let { header("baggage", it) }
                if (timeoutMillis != null) {
                    timeout {
                        requestTimeoutMillis = timeoutMillis
                    }
                }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }

            status = response.status.value
            responseContentType = response.headers[HttpHeaders.ContentType]
            val responseText = response.bodyAsText()
            rawResponseBody = responseText

            if (response.status.isSuccess()) {
                outcome = "success"
                result = buzerApiJson.decodeFromString<T>(responseText)
            } else {
                outcome = "http_error"
                val error = responseText.toBuzerApiException(response.status.value, response.headers[HttpHeaders.RetryAfter])
                AppLog.error("api http_error status=${error.status} code=${error.code}")
                failure = error
            }
        } catch (e: Throwable) {
            if (failure == null) {
                failure = e
                outcome = when {
                    e is io.ktor.client.plugins.HttpRequestTimeoutException ||
                    e is io.ktor.client.network.sockets.SocketTimeoutException ||
                    e is io.ktor.client.plugins.ConnectTimeoutException ||
                    e is kotlinx.coroutines.TimeoutCancellationException ||
                    e::class.simpleName?.contains("Timeout", ignoreCase = true) == true -> "timeout"
                    e is kotlinx.coroutines.CancellationException -> "cancelled"
                    status != null && HttpStatusCode.fromValue(status).isSuccess() -> "success"
                    status != null && !HttpStatusCode.fromValue(status).isSuccess() -> "http_error"
                    else -> "network_error"
                }
            }
        } finally {
            val durationMillis = startTime.elapsedNow().inWholeMilliseconds
            val (sanitizedReqBody, reqTruncated) = sanitizeBody(rawRequestBody)
            val (sanitizedRespBody, respTruncated) = sanitizeBody(rawResponseBody)

            val exchange = DiagnosticNetworkExchange(
                startedAt = startedAt,
                durationMillis = durationMillis,
                method = httpMethod.value,
                path = path,
                status = status,
                outcome = outcome,
                requestContentType = if (body != null) ContentType.Application.Json.toString() else null,
                responseContentType = responseContentType,
                requestBody = sanitizedReqBody,
                responseBody = sanitizedRespBody,
                requestId = requestId,
                traceId = traceId,
                actionId = actionId,
                requestTruncated = reqTruncated,
                responseTruncated = respTruncated
            )

            runCatching {
                networkSink?.record(exchange)
            }
        }

        failure?.let { throw it }
        return result ?: throw IllegalStateException("Missing response body")
    }
}

private const val MAX_BODY_LENGTH = 65_536

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun generateUuid(): String = kotlin.uuid.Uuid.random().toString()

private fun String.toBuzerApiException(status: Int, retryAfterHeader: String?): BuzerApiException {
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
