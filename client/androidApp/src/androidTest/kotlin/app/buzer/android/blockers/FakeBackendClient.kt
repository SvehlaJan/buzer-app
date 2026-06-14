package app.buzer.android.blockers

import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FakeBackendClient(
    private val baseUrl: String = InstrumentationRegistry.getArguments()
        .getString("FAKE_BACKEND_BASE_URL", "http://10.0.2.2:18433")
) {
    fun resetCalls() {
        request("POST", "/__reset")
    }

    fun setFailActions(enabled: Boolean) {
        request("POST", "/__fail-actions", """{"enabled":$enabled}""")
    }

    fun setFailSnapshot(enabled: Boolean) {
        request("POST", "/__fail-snapshot", """{"enabled":$enabled}""")
    }

    fun setFailProjects(enabled: Boolean) {
        request("POST", "/__fail-projects", """{"enabled":$enabled}""")
    }

    fun calls(): String = request("GET", "/__calls")

    fun assertRecorded(path: String) {
        val body = calls()
        check(body.contains("\"path\":\"$path\"")) {
            "Expected fake backend call $path, got $body"
        }
    }

    fun assertRecorded(path: String, vararg bodyFragments: String) {
        val body = calls()
        check(body.contains("\"path\":\"$path\"")) {
            "Expected fake backend call $path, got $body"
        }
        bodyFragments.forEach { fragment ->
            check(body.contains(fragment)) {
                "Expected fake backend call $path body to contain $fragment, got $body"
            }
        }
    }

    fun assertRecordedWithout(path: String, vararg forbiddenFragments: String) {
        val callsBody = calls()
        val matchingBodies = recordedCallBodies(callsBody, path)
        check(matchingBodies.isNotEmpty()) {
            "Expected fake backend call $path, got $callsBody"
        }
        forbiddenFragments.forEach { fragment ->
            val matchingForbiddenBodies = matchingBodies.filter { it.contains(fragment) }
            check(matchingForbiddenBodies.isEmpty()) {
                "Expected fake backend call $path body to omit $fragment, " +
                    "got matching bodies $matchingForbiddenBodies. Full recorded calls: $callsBody"
            }
        }
    }

    private fun recordedCallBodies(callsBody: String, path: String): List<String> {
        val callsArray = runCatching {
            Json.parseToJsonElement(callsBody)
                .jsonObject["calls"]
                ?.jsonArray
                ?: error("Missing calls array")
        }.getOrElse { error ->
            throw AssertionError("Expected fake backend calls JSON, got $callsBody", error)
        }

        return callsArray.mapNotNull { call ->
            val callObject = call.jsonObject
            val recordedPath = callObject["path"]?.jsonPrimitive?.contentOrNull
            if (recordedPath == path) {
                callObject["body"]?.toString() ?: "null"
            } else {
                null
            }
        }
    }

    private fun request(method: String, path: String, body: String = "{}"): String {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        if (method == "POST") {
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { stream ->
                stream.write(body.toByteArray())
            }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) {
                "$method $path failed with HTTP $status: $body"
            }
            body
        } finally {
            connection.disconnect()
        }
    }
}
