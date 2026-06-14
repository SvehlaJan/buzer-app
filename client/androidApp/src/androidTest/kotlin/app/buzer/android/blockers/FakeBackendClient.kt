package app.buzer.android.blockers

import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL

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
        val body = calls()
        check(body.contains("\"path\":\"$path\"")) {
            "Expected fake backend call $path, got $body"
        }
        forbiddenFragments.forEach { fragment ->
            check(!body.contains(fragment)) {
                "Expected fake backend call $path body to omit $fragment, got $body"
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
