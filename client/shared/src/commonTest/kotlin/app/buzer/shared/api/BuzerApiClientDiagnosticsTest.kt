package app.buzer.shared.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuzerApiClientDiagnosticsTest {

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
}
