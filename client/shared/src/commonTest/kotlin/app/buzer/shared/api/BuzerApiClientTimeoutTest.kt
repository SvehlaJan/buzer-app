package app.buzer.shared.api

import app.buzer.shared.model.CreateTaskRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BuzerApiClientTimeoutTest {
    @Test
    fun getSnapshotDefaultTimeoutPreservesOriginalExceptionAndRequestCorrelation() = runBlocking {
        val engine = MockEngine { _ ->
            delay(300)
            respond(
                content = ByteReadChannel("""{"scheduleVersion":1,"snapshotGeneration":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}"""),
                headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
            )
        }
        val client = BuzerApiClient(
            baseUrl = "https://unreachable.invalid",
            token = "test-token",
            httpClient = defaultHttpClient(token = "test-token", timeoutMillis = 50, engine = engine),
            requestIdProvider = { "request-timeout-123" }
        )

        val error = assertFailsWith<HttpRequestTimeoutException> {
            client.getSnapshot(forceRefresh = false)
        }

        assertEquals("request_id=request-timeout-123", error.suppressedExceptions.single().message)
    }

    @Test
    fun getSnapshotForceRefreshApplies15sTimeoutAndSucceedsBeyondDefaultTimeout() = runBlocking {
        val engine = MockEngine { _ ->
            delay(300)
            respond(
                content = ByteReadChannel("""{"scheduleVersion":1,"snapshotGeneration":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
            )
        }
        val client = BuzerApiClient(
            baseUrl = "https://slow.invalid",
            token = "test-token",
            httpClient = defaultHttpClient(token = "test-token", timeoutMillis = 50, engine = engine),
            requestIdProvider = { "request-forcerefresh-123" }
        )

        val result = client.getSnapshot(forceRefresh = true)

        assertEquals(1, result.snapshotGeneration)
    }

    @Test
    fun mutationsApply15sTimeoutAndSucceedBeyondDefaultTimeout() = runBlocking {
        val engine = MockEngine { _ ->
            delay(300)
            respond(
                content = ByteReadChannel("""{"snapshot":{"scheduleVersion":1,"snapshotGeneration":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, listOf(ContentType.Application.Json.toString()))
            )
        }
        val client = BuzerApiClient(
            baseUrl = "https://slow.invalid",
            token = "test-token",
            httpClient = defaultHttpClient(token = "test-token", timeoutMillis = 50, engine = engine),
            requestIdProvider = { "request-mutation-123" }
        )

        val result = client.createTask(CreateTaskRequest(title = "New task", projectId = "proj-1"))

        assertEquals(1, result.snapshot.snapshotGeneration)
    }
}
