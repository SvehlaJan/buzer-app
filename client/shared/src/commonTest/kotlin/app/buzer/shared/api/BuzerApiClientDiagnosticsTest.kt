package app.buzer.shared.api

import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.diagnostics.DiagnosticLogRecord
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.TaskActionRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuzerApiClientDiagnosticsTest {

    @AfterTest
    fun tearDown() {
        AppLog.resetSinkForTests()
    }

    @Test
    fun everyRequestCarriesRequestIdWithoutTracePropagation() = runBlocking {
        val capturedHeaders = mutableListOf<Map<String, String?>>()
        val requestIds = ArrayDeque(listOf("request-123", "request-456"))
        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "my-bearer-token",
            httpClient = defaultHttpClient(
                token = "my-bearer-token",
                engine = MockEngine { request ->
                    capturedHeaders += mapOf(
                        "request-id" to request.headers["X-Request-ID"],
                        "sentry-trace" to request.headers["sentry-trace"],
                        "baggage" to request.headers["baggage"],
                        "authorization" to request.headers[HttpHeaders.Authorization]
                    )
                    val response = when (request.url.encodedPath) {
                        "/projects" -> """{"projects":[]}"""
                        "/tasks/snapshot" ->
                            """{"scheduleVersion":1,"snapshotGeneration":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}"""
                        else -> error("Unexpected path ${request.url.encodedPath}")
                    }
                    respond(
                        content = ByteReadChannel(response),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ),
            requestIdProvider = { requestIds.removeFirst() }
        )

        client.getProjects()
        client.getSnapshot()

        assertEquals(listOf("request-123", "request-456"), capturedHeaders.map { it["request-id"] })
        capturedHeaders.forEach { headers ->
            assertNull(headers["sentry-trace"])
            assertNull(headers["baggage"])
            assertEquals("Bearer my-bearer-token", headers["authorization"])
        }
    }

    @Test
    fun backendRequestStartedEmitsSafeStructuredAttributesWithoutSensitiveData() = runBlocking {
        val records = mutableListOf<DiagnosticLogRecord>()
        AppLog.installSink { record -> records += record }

        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "my-secret-token",
            httpClient = defaultHttpClient(
                token = "my-secret-token",
                engine = MockEngine {
                    respond(
                        content = ByteReadChannel("""{"snapshot":{"scheduleVersion":1,"snapshotGeneration":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ),
            requestIdProvider = { "req-started-1" },
            isConnectedProvider = { true }
        )

        client.createTask(CreateTaskRequest(title = "Secret confidential title", projectId = "proj-1"))

        val startedRecord = records.single { it.message == "backend_request_started" }
        assertEquals("info", startedRecord.severity)
        assertEquals("Buzer", startedRecord.tag)
        assertEquals("req-started-1", startedRecord.attributes["request_id"])
        assertEquals("/tasks", startedRecord.attributes["route"])
        assertEquals("POST", startedRecord.attributes["method"])
        assertEquals("true", startedRecord.attributes["is_connected"])

        val allLoggedText = buildString {
            append(startedRecord.message)
            startedRecord.attributes.forEach { (k, v) -> append(" $k=$v") }
        }
        assertFalse(allLoggedText.contains("Secret confidential title"))
        assertFalse(allLoggedText.contains("my-secret-token"))
        assertFalse(allLoggedText.contains("Bearer"))
        assertFalse(allLoggedText.contains("Authorization"))
    }

    @Test
    fun backendRequestFailedEmitsSafeStructuredAttributesOnHttpError() = runBlocking {
        val records = mutableListOf<DiagnosticLogRecord>()
        AppLog.installSink { record -> records += record }

        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "my-secret-token",
            httpClient = defaultHttpClient(
                token = "my-secret-token",
                engine = MockEngine {
                    respond(
                        content = ByteReadChannel("""{"code":"stale_task","message":"Secret internal error message","snapshot":{"scheduleVersion":1,"snapshotGeneration":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}}"""),
                        status = HttpStatusCode.Conflict,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ),
            requestIdProvider = { "req-failed-1" },
            isConnectedProvider = { true }
        )

        val error = assertFailsWith<BuzerApiException> {
            client.startTask("task-123", TaskActionRequest(expectedOccurrenceKey = "occurrence-1"))
        }
        assertEquals("stale_task", error.code)

        val failedRecord = records.single { it.message == "backend_request_failed" }
        assertEquals("warning", failedRecord.severity)
        assertEquals("Buzer", failedRecord.tag)
        assertEquals("req-failed-1", failedRecord.attributes["request_id"])
        assertEquals("/tasks/task-123/start", failedRecord.attributes["route"])
        assertEquals("POST", failedRecord.attributes["method"])
        assertEquals("409", failedRecord.attributes["status_code"])
        assertEquals("stale_task", failedRecord.attributes["error_code"])
        assertEquals("true", failedRecord.attributes["is_connected"])
        assertTrue(failedRecord.attributes.containsKey("duration_ms"))

        val allAttrValues = failedRecord.attributes.values.joinToString(" ")
        assertFalse(allAttrValues.contains("Secret internal error message"))
        assertFalse(allAttrValues.contains("my-secret-token"))
        assertFalse(allAttrValues.contains("Bearer"))
    }

    @Test
    fun backendRequestFailedEmitsSafeStructuredAttributesOnNetworkFailure() = runBlocking {
        val records = mutableListOf<DiagnosticLogRecord>()
        AppLog.installSink { record -> records += record }

        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "my-secret-token",
            httpClient = defaultHttpClient(
                token = "my-secret-token",
                engine = MockEngine { throw IllegalStateException("network lost") }
            ),
            requestIdProvider = { "req-net-1" },
            isConnectedProvider = { false }
        )

        assertFailsWith<IllegalStateException> {
            client.getSnapshot()
        }

        val failedRecord = records.single { it.message == "backend_request_failed" }
        assertEquals("warning", failedRecord.severity)
        assertEquals("Buzer", failedRecord.tag)
        assertEquals("req-net-1", failedRecord.attributes["request_id"])
        assertEquals("/tasks/snapshot", failedRecord.attributes["route"])
        assertEquals("GET", failedRecord.attributes["method"])
        assertEquals("false", failedRecord.attributes["is_connected"])
        assertTrue(failedRecord.attributes.containsKey("duration_ms"))
        assertFalse(failedRecord.attributes.containsKey("status_code"))
        assertFalse(failedRecord.attributes.containsKey("error_code"))
    }

    @Test
    fun backendRequestFailedEmitsErrorOnResponseBodyDecodeFailure() = runBlocking {
        val records = mutableListOf<DiagnosticLogRecord>()
        AppLog.installSink { record -> records += record }

        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "my-secret-token",
            httpClient = defaultHttpClient(
                token = "my-secret-token",
                engine = MockEngine {
                    respond(
                        content = ByteReadChannel("malformed payload"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ),
            requestIdProvider = { "req-decode-1" },
            isConnectedProvider = { true }
        )

        assertFailsWith<SerializationException> {
            client.getSnapshot()
        }

        val failedRecord = records.single { it.message == "backend_request_failed" }
        assertEquals("error", failedRecord.severity)
        assertEquals("Buzer", failedRecord.tag)
        assertEquals("req-decode-1", failedRecord.attributes["request_id"])
        assertEquals("/tasks/snapshot", failedRecord.attributes["route"])
        assertEquals("GET", failedRecord.attributes["method"])
        assertEquals("200", failedRecord.attributes["status_code"])
        assertEquals("true", failedRecord.attributes["is_connected"])
        assertTrue(failedRecord.attributes.containsKey("duration_ms"))
        assertFalse(failedRecord.attributes.values.any { it.contains("malformed payload") })
    }
}
