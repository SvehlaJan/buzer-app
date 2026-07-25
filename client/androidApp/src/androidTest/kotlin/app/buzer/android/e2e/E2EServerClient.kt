package app.buzer.android.e2e

import app.buzer.android.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Test-only control surface for backend/test/e2eServer.ts, never tools/fake-backend. */
class E2EServerClient(
    private val baseUrl: String = BuildConfig.BUZER_BACKEND_BASE_URL
) {
    fun reset() {
        request("POST", "/__reset")
    }

    fun setScenario(name: String) {
        request("POST", "/__scenario", """{"name":"$name"}""")
    }

    fun updatePayloads(taskId: String): List<JsonObject> = recordedCalls()
        .mapNotNull { call ->
            call.jsonObject.takeIf {
                it["path"]?.jsonPrimitive?.content == "/tasks/$taskId/update"
            }?.get("payload")?.jsonObject
        }

    fun updateCallCount(taskId: String): Int = updatePayloads(taskId).size

    private fun recordedCalls(): JsonArray = Json.parseToJsonElement(request("GET", "/__calls/full"))
        .jsonObject
        .getValue("calls")
        .jsonArray

    private fun request(method: String, path: String, body: String = "{}"): String {
        // Managed-device guest networking can become ready after the host E2E server. These
        // control endpoints are idempotent, so match the established instrumentation retry.
        val deadline = System.currentTimeMillis() + CONNECTIVITY_RETRY_WINDOW_MILLIS
        while (true) {
            try {
                return requestOnce(method, path, body)
            } catch (error: IOException) {
                if (System.currentTimeMillis() >= deadline) throw error
                Thread.sleep(1_000)
            }
        }
    }

    private fun requestOnce(method: String, path: String, body: String): String {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        if (method == "POST") {
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { stream -> stream.write(body.toByteArray()) }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "$method $path failed with HTTP $status: $response" }
            response
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECTIVITY_RETRY_WINDOW_MILLIS = 45_000L
    }
}
