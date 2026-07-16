package app.buzer.shared.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
