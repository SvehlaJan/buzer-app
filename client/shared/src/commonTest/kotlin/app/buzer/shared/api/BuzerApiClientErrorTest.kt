package app.buzer.shared.api

import app.buzer.shared.model.FocusedChecklistMutationRequest
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.diagnostics.AppLog
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuzerApiClientErrorTest {
    @Test
    fun networkSnapshotRejectsMissingSnapshotGeneration(): Unit = runBlocking {
        val client = clientResponding(
            status = HttpStatusCode.OK,
            body = """{"scheduleVersion":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}"""
        )

        assertFails { client.getSnapshot() }
    }

    @Test
    fun networkMutationRejectsMissingSnapshotGeneration(): Unit = runBlocking {
        val client = clientResponding(
            status = HttpStatusCode.OK,
            body = """
                {"actionId":"action-start-1","outcome":"completed","snapshot":{
                  "scheduleVersion":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]
                }}
            """.trimIndent()
        )

        assertFails {
            client.startTask(
                "task-1",
                TaskActionRequest("action-start-1", "device-1", "occurrence-1")
            )
        }
    }

    @Test
    fun conflictSnapshotRejectsMissingSnapshotGeneration(): Unit = runBlocking {
        val client = clientResponding(
            status = HttpStatusCode.Conflict,
            body = """
                {"code":"stale_task","snapshot":{
                  "scheduleVersion":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]
                }}
            """.trimIndent()
        )

        assertFailsWith<SerializationException> { client.getSnapshot() }
    }

    @Test
    fun conflictWithoutAuthoritativeSnapshotIsRejected(): Unit = runBlocking {
        val client = clientResponding(HttpStatusCode.Conflict, """{"code":"stale_task"}""")

        assertFailsWith<SerializationException> { client.getSnapshot() }
    }

    @Test
    fun conflictRetainsAuthoritativeSnapshotForCallerRefresh() = runBlocking {
        val client = clientResponding(
            status = HttpStatusCode.Conflict,
            body =
                """
                {"code":"stale_task","message":"Task revision is stale","snapshot":{
                  "scheduleVersion":12,"snapshotGeneration":12,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]
                }}
                """.trimIndent()
        )

        val error = assertFailsWith<BuzerApiException> { client.getSnapshot() }

        assertEquals(409, error.status)
        assertEquals("stale_task", error.code)
        assertEquals(12, error.snapshot?.scheduleVersion)
        assertNull(error.retryAfterSeconds)
    }

    @Test
    fun rateLimitSurfacesRetryAfterWithoutRetryingTheRequest() = runBlocking {
        var requestCount = 0
        val client = BuzerApiClient(
            baseUrl = "https://fake.invalid",
            token = "test-token",
            httpClient = defaultHttpClient(
                token = "test-token",
                engine = MockEngine {
                    requestCount += 1
                    respond(
                        content = ByteReadChannel("{\"code\":\"rate_limited\"}"),
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.RetryAfter, "7")
                    )
                }
            )
        )

        val error = assertFailsWith<BuzerApiException> { client.getSnapshot() }

        assertEquals(429, error.status)
        assertEquals("rate_limited", error.code)
        assertEquals(7, error.retryAfterSeconds)
        assertEquals(1, requestCount)
    }

    @Test
    fun malformedErrorBodyStillProducesTypedErrorWithoutLeakingTheBody() = runBlocking {
        val client = clientResponding(HttpStatusCode.InternalServerError, "<upstream unavailable>")

        val error = assertFailsWith<BuzerApiException> { client.getSnapshot() }

        assertEquals(500, error.status)
        assertNull(error.code)
        assertNull(error.snapshot)
    }

    @Test
    fun failedRequestPreservesRequestIdInExceptionAndErrorBreadcrumb() = runBlocking {
        val breadcrumbs = mutableListOf<String>()
        AppLog.installSink { record, _ -> breadcrumbs += record.message }
        try {
            val client = BuzerApiClient(
                baseUrl = "https://fake.invalid",
                token = "test-token",
                httpClient = defaultHttpClient(
                    token = "test-token",
                    engine = MockEngine {
                        respond(
                            content = ByteReadChannel("{\"code\":\"server_error\"}"),
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                ),
                requestIdProvider = { "request-123" }
            )

            val error = assertFailsWith<BuzerApiException> { client.getSnapshot() }

            assertEquals("request-123", error.requestId)
            assertTrue(breadcrumbs.any { it.contains("request_id=request-123") })
        } finally {
            AppLog.resetSinkForTests()
        }
    }

    @Test
    fun transportFailurePreservesOriginalExceptionAndRequestIdBreadcrumb() = runBlocking {
        val breadcrumbs = mutableListOf<String>()
        AppLog.installSink { record, _ -> breadcrumbs += record.message }
        try {
            val client = BuzerApiClient(
                baseUrl = "https://fake.invalid",
                token = "test-token",
                httpClient = defaultHttpClient(
                    token = "test-token",
                    engine = MockEngine { throw IllegalStateException("connection lost") }
                ),
                requestIdProvider = { "request-transport-123" }
            )

            val error = assertFailsWith<IllegalStateException> { client.getSnapshot() }

            assertEquals("connection lost", error.message)
            assertEquals("request_id=request-transport-123", error.suppressedExceptions.single().message)
            assertTrue(breadcrumbs.any { it.contains("request_id=request-transport-123") })
        } finally {
            AppLog.resetSinkForTests()
        }
    }

    @Test
    fun successfulResponseDecodeFailurePreservesOriginalExceptionAndRequestId() = runBlocking {
        val client = BuzerApiClient(
            baseUrl = "https://fake.invalid",
            token = "test-token",
            httpClient = defaultHttpClient(
                token = "test-token",
                engine = MockEngine {
                    respond(
                        content = ByteReadChannel("not-json"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ),
            requestIdProvider = { "request-decode-123" }
        )

        val error = assertFailsWith<SerializationException> { client.getSnapshot() }

        assertEquals("request_id=request-decode-123", error.suppressedExceptions.single().message)
    }

    @Test
    fun cancellationFailureRethrowsWithoutRequestCorrelationMarker() = runBlocking {
        val client = BuzerApiClient(
            baseUrl = "https://fake.invalid",
            token = "test-token",
            httpClient = defaultHttpClient(
                token = "test-token",
                engine = MockEngine { throw CancellationException("cancelled") }
            ),
            requestIdProvider = { "request-cancel-123" }
        )

        val error = assertFailsWith<CancellationException> { client.getSnapshot() }

        assertEquals("cancelled", error.message)
        assertTrue(error.suppressedExceptions.isEmpty())
    }

    @Test
    fun malformedNonSnapshotErrorObjectStillProducesTypedError() = runBlocking {
        val client = clientResponding(HttpStatusCode.InternalServerError, """{"code":123}""")

        val error = assertFailsWith<BuzerApiException> { client.getSnapshot() }

        assertEquals(500, error.status)
        assertNull(error.code)
        assertNull(error.snapshot)
    }

    @Test
    fun focusedChecklistEndpointsSendOnlyActionMetadataToTheirOwnPaths() = runBlocking {
        val requests = mutableListOf<Pair<String, String>>()
        val client = BuzerApiClient(
            baseUrl = "https://fake.invalid",
            token = "test-token",
            httpClient = defaultHttpClient(
                token = "test-token",
                engine = MockEngine { request ->
                    requests += request.url.encodedPath to (request.body as TextContent).text
                    respond(
                        content = ByteReadChannel(
                            """{"actionId":"action-check-1","outcome":"completed","snapshot":{"scheduleVersion":1,"snapshotGeneration":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}}"""
                        ),
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            )
        )
        val request = FocusedChecklistMutationRequest(
            actionId = "action-check-1",
            deviceId = "device-1",
            expectedOccurrenceKey = "occurrence-1",
            expectedChildRevision = "r:2"
        )

        client.checkChecklistItem("task-1", "child-1", request)
        client.uncheckChecklistItem("task-1", "child-1", request.copy(actionId = "action-uncheck-1"))

        assertEquals("/tasks/task-1/checklist/child-1/check", requests[0].first)
        assertEquals("/tasks/task-1/checklist/child-1/uncheck", requests[1].first)
        assertFalse(requests.any { (_, body) -> body.contains("checked") })
    }

    private fun clientResponding(status: HttpStatusCode, body: String): BuzerApiClient =
        BuzerApiClient(
            baseUrl = "https://fake.invalid",
            token = "test-token",
            httpClient = defaultHttpClient(
                token = "test-token",
                engine = MockEngine {
                    respond(
                        content = ByteReadChannel(body),
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            )
        )
}
