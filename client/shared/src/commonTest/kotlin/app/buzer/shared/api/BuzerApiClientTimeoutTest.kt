package app.buzer.shared.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BuzerApiClientTimeoutTest {
    @Test
    fun getSnapshotTimesOutWhenServerNeverResponds() {
        runBlocking {
            val engine = MockEngine { _ ->
                delay(300)
                respond(
                    content = ByteReadChannel("""{"tasks":[]}"""),
                    headers = headersOf(HttpHeaders.ContentType, listOf("application/json"))
                )
            }
            val client = BuzerApiClient(
                baseUrl = "https://unreachable.invalid",
                token = "test-token",
                httpClient = defaultHttpClient(token = "test-token", timeoutMillis = 50, engine = engine)
            )

            assertFailsWith<HttpRequestTimeoutException> {
                client.getSnapshot()
            }
        }
    }
}
