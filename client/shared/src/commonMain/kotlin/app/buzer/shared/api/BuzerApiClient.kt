package app.buzer.shared.api

import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.ProjectsResponse
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TasksSnapshotResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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

    suspend fun getProjects(): ProjectsResponse =
        httpClient.get("$baseUrl/projects").body()

    suspend fun createTask(request: CreateTaskRequest) {
        AppLog.info(
            "api create_task request titleLength=${request.title.length} project=${request.projectId} assignee=${request.assigneeId} " +
                "due=${request.dueDatetime} duration=${request.durationMinutes}"
        )
        try {
            httpClient.post("$baseUrl/tasks") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            AppLog.info("api create_task success")
        } catch (error: ResponseException) {
            val body = try {
                error.response.bodyAsText()
            } catch (bodyError: Throwable) {
                "<body unavailable: ${bodyError.message}>"
            }
            AppLog.error("api create_task http_error status=${error.response.status.value} body=$body", error)
            throw error
        } catch (error: Throwable) {
            AppLog.error("api create_task failure type=${error::class.simpleName} message=${error.message}", error)
            throw error
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

    suspend fun dismissMorningReview() {
        emptyJsonPost("$baseUrl/review/dismiss")
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

internal val buzerApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
