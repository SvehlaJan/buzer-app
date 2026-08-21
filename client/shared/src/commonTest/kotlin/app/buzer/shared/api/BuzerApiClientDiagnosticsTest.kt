package app.buzer.shared.api

import app.buzer.shared.diagnostics.DiagnosticNetworkExchange
import app.buzer.shared.diagnostics.DiagnosticReportRequest
import app.buzer.shared.model.CreateTaskRequest
import app.buzer.shared.model.TaskActionRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuzerApiClientDiagnosticsTest {

    private class RecordingNetworkSink : NetworkDiagnosticSink {
        val exchanges = mutableListOf<DiagnosticNetworkExchange>()
        override fun record(exchange: DiagnosticNetworkExchange) {
            exchanges += exchange
        }
    }

    @Test
    fun recordsOnlyPathNeverSchemeOrHostCredentials() = runBlocking {
        val sink = RecordingNetworkSink()
        val client = BuzerApiClient(
            baseUrl = "https://user:secret@api.buzer.invalid:8443",
            token = "secret-token",
            httpClient = defaultHttpClient(
                token = "secret-token",
                engine = MockEngine {
                    respond(
                        content = ByteReadChannel("""{"scheduleVersion":1,"snapshotGeneration":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ),
            networkSink = sink,
            requestIdProvider = { "req-fixed-1" }
        )

        client.getSnapshot()

        assertEquals(1, sink.exchanges.size)
        val exchange = sink.exchanges.single()
        assertEquals("/tasks/snapshot", exchange.path)
        assertFalse(exchange.path.contains("https"))
        assertFalse(exchange.path.contains("buzer.invalid"))
        assertFalse(exchange.path.contains("secret"))
        assertEquals("GET", exchange.method)
        assertEquals(200, exchange.status)
        assertEquals("success", exchange.outcome)
        assertEquals("req-fixed-1", exchange.requestId)
        assertTrue(exchange.durationMillis >= 0)
    }

    @Test
    fun sendsRequestIdAndTraceHeadersWithoutReplacingBearerAuth() = runBlocking {
        val sink = RecordingNetworkSink()
        var capturedRequestId: String? = null
        var capturedSentryTrace: String? = null
        var capturedBaggage: String? = null
        var capturedAuth: String? = null

        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "my-bearer-token",
            httpClient = defaultHttpClient(
                token = "my-bearer-token",
                engine = MockEngine { request ->
                    capturedRequestId = request.headers["X-Request-ID"]
                    capturedSentryTrace = request.headers["sentry-trace"]
                    capturedBaggage = request.headers["baggage"]
                    capturedAuth = request.headers[HttpHeaders.Authorization]
                    respond(
                        content = ByteReadChannel("""{"projects":[]}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ),
            networkSink = sink,
            requestIdProvider = { "custom-req-id-42" },
            traceHeaderProvider = {
                TraceHeaders(
                    traceId = "trace-42",
                    sentryTrace = "trace-42-span-1-1",
                    baggage = "sentry-environment=test,sentry-release=1.0.0"
                )
            }
        )

        client.getProjects()

        assertEquals("custom-req-id-42", capturedRequestId)
        assertEquals("trace-42-span-1-1", capturedSentryTrace)
        assertEquals("sentry-environment=test,sentry-release=1.0.0", capturedBaggage)
        assertEquals("Bearer my-bearer-token", capturedAuth)

        val exchange = sink.exchanges.single()
        assertEquals("custom-req-id-42", exchange.requestId)
        assertEquals("trace-42", exchange.traceId)
        assertEquals("/projects", exchange.path)
    }

    @Test
    fun redactsSecretsAndTruncatesLargeBodies() = runBlocking {
        val sink = RecordingNetworkSink()
        val largePadding = "x".repeat(70_000)
        val responseBodyWithSecret = """{"actionId":"action-create-99","outcome":"completed","snapshot":{"scheduleVersion":1,"snapshotGeneration":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]},"secret_token":"SECRET_VALUE_123","padding":"$largePadding"}"""

        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "token-1",
            httpClient = defaultHttpClient(
                token = "token-1",
                engine = MockEngine {
                    respond(
                        content = ByteReadChannel(responseBodyWithSecret),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ),
            networkSink = sink,
            requestIdProvider = { "req-trunc-1" }
        )

        val createRequest = CreateTaskRequest(
            title = "Plan release with password: 'SUPER_SECRET_PASSWORD'",
            projectId = "proj-1",
            actionId = "action-create-99",
            deviceId = "device-1"
        )

        client.createTask(createRequest)

        assertEquals(1, sink.exchanges.size)
        val exchange = sink.exchanges.single()

        // Verify request body redaction
        assertNotNull(exchange.requestBody)
        assertFalse(exchange.requestBody!!.contains("SUPER_SECRET_PASSWORD"))
        assertTrue(exchange.requestBody!!.contains("[REDACTED]"))
        assertFalse(exchange.requestTruncated)
        assertEquals("action-create-99", exchange.actionId)

        // Verify response body redaction and truncation
        assertNotNull(exchange.responseBody)
        assertFalse(exchange.responseBody!!.contains("SECRET_VALUE_123"))
        assertTrue(exchange.responseBody!!.contains("[REDACTED]"))
        assertTrue(exchange.responseTruncated)
        assertEquals(65_536, exchange.responseBody!!.length)
    }

    @Test
    fun truncatesLargeRequestBodyAndSetsTruncatedFlag() = runBlocking {
        val sink = RecordingNetworkSink()
        val largeTitle = "A".repeat(70_000)
        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "token-1",
            httpClient = defaultHttpClient(
                token = "token-1",
                engine = MockEngine {
                    respond(
                        content = ByteReadChannel("""{"actionId":"act-1","outcome":"completed","snapshot":{"scheduleVersion":1,"snapshotGeneration":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ),
            networkSink = sink,
            requestIdProvider = { "req-large-body" }
        )

        client.createTask(
            CreateTaskRequest(
                title = largeTitle,
                projectId = "proj-1",
                actionId = "act-1",
                deviceId = "dev-1"
            )
        )

        val exchange = sink.exchanges.single()
        assertTrue(exchange.requestTruncated)
        assertEquals(65_536, exchange.requestBody?.length)
        assertFalse(exchange.responseTruncated)
    }

    @Test
    fun recordsHttpErrorOnNon2xxResponse() = runBlocking {
        val sink = RecordingNetworkSink()
        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "token-1",
            httpClient = defaultHttpClient(
                token = "token-1",
                engine = MockEngine {
                    respond(
                        content = ByteReadChannel("""{"code":"task_not_found","message":"Not found"}"""),
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ),
            networkSink = sink,
            requestIdProvider = { "req-404" }
        )

        assertFailsWith<BuzerApiException> {
            client.startTask("task-99", TaskActionRequest("action-1", "device-1", "occ-1"))
        }

        assertEquals(1, sink.exchanges.size)
        val exchange = sink.exchanges.single()
        assertEquals(404, exchange.status)
        assertEquals("http_error", exchange.outcome)
        assertEquals("action-1", exchange.actionId)
        assertEquals("/tasks/task-99/start", exchange.path)
    }

    @Test
    fun recordsTimeoutOutcomeOnRequestTimeout() = runBlocking {
        val sink = RecordingNetworkSink()
        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "token-1",
            httpClient = defaultHttpClient(
                token = "token-1",
                timeoutMillis = 50,
                engine = MockEngine {
                    delay(300)
                    respond(
                        content = ByteReadChannel("""{"tasks":[]}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ),
            networkSink = sink,
            requestIdProvider = { "req-timeout" }
        )

        assertFailsWith<HttpRequestTimeoutException> {
            client.getSnapshot()
        }

        assertEquals(1, sink.exchanges.size)
        val exchange = sink.exchanges.single()
        assertNull(exchange.status)
        assertEquals("timeout", exchange.outcome)
        assertEquals("/tasks/snapshot", exchange.path)
    }

    @Test
    fun recordsNetworkErrorOnConnectionFailure() = runBlocking {
        val sink = RecordingNetworkSink()
        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "token-1",
            httpClient = defaultHttpClient(
                token = "token-1",
                engine = MockEngine {
                    throw IllegalStateException("Connection reset by peer")
                }
            ),
            networkSink = sink,
            requestIdProvider = { "req-net-err" }
        )

        assertFailsWith<IllegalStateException> {
            client.getSnapshot()
        }

        assertEquals(1, sink.exchanges.size)
        val exchange = sink.exchanges.single()
        assertNull(exchange.status)
        assertEquals("network_error", exchange.outcome)
    }

    @Test
    fun recordsCancelledOutcomeOnCoroutineCancellation() = runBlocking {
        val sink = RecordingNetworkSink()
        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "token-1",
            httpClient = defaultHttpClient(
                token = "token-1",
                engine = MockEngine {
                    throw CancellationException("Request cancelled")
                }
            ),
            networkSink = sink,
            requestIdProvider = { "req-cancel" }
        )

        assertFailsWith<CancellationException> {
            client.getSnapshot()
        }

        assertEquals(1, sink.exchanges.size)
        val exchange = sink.exchanges.single()
        assertNull(exchange.status)
        assertEquals("cancelled", exchange.outcome)
    }

    @Test
    fun failingNetworkSinkNeverBreaksHttpExecutionOrThrowsToCaller() = runBlocking {
        val throwingSink = NetworkDiagnosticSink {
            throw RuntimeException("Database error in sink recording")
        }

        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "token-1",
            httpClient = defaultHttpClient(
                token = "token-1",
                engine = MockEngine {
                    respond(
                        content = ByteReadChannel("""{"scheduleVersion":10,"snapshotGeneration":5,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ),
            networkSink = throwingSink
        )

        val snapshot = client.getSnapshot()
        assertEquals(10, snapshot.scheduleVersion)
        assertEquals(5, snapshot.snapshotGeneration)
    }

    @Test
    fun infersActionIdFromVariousMutationRequests() = runBlocking {
        val sink = RecordingNetworkSink()
        val client = BuzerApiClient(
            baseUrl = "https://api.buzer.invalid",
            token = "token-1",
            httpClient = defaultHttpClient(
                token = "token-1",
                engine = MockEngine {
                    respond(
                        content = ByteReadChannel("""{"actionId":"action-test","outcome":"completed","snapshot":{"scheduleVersion":1,"snapshotGeneration":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            ),
            networkSink = sink
        )

        client.completeTask("t1", TaskActionRequest("act-complete-1", "d1", "occ-1"))
        assertEquals("act-complete-1", sink.exchanges.last().actionId)

        client.postponeStart("t1", TaskActionRequest("act-postpone-1", "d1", "occ-1"))
        assertEquals("act-postpone-1", sink.exchanges.last().actionId)
    }
}
