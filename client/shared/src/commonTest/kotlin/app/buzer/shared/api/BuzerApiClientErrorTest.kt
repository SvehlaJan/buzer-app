package app.buzer.shared.api

import app.buzer.shared.model.FocusedChecklistMutationRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse

class BuzerApiClientErrorTest {
    @Test
    fun conflictRetainsAuthoritativeSnapshotForCallerRefresh() = runBlocking {
        val client = clientResponding(
            status = HttpStatusCode.Conflict,
            body =
                """
                {"code":"stale_task","message":"Task revision is stale","snapshot":{
                  "scheduleVersion":12,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]
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
                            """{"actionId":"action-check-1","outcome":"completed","snapshot":{"scheduleVersion":1,"serverTime":"2026-07-16T10:00:00Z","health":[],"tasks":[]}}"""
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
