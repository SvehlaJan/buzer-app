package app.buzer.shared.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class DiagnosticRedactor {

    fun redactString(input: String): String {
        if (input.isEmpty()) return input

        var text = input

        // 1. PEM private key blocks
        text = PEM_PATTERN.replace(text, "[REDACTED]")

        // 2. Bearer and Basic authorization patterns
        text = AUTH_SCHEME_PATTERN.replace(text) { matchResult ->
            val scheme = matchResult.groupValues[1]
            val quote = matchResult.groupValues[2]
            if (quote.isNotEmpty()) {
                "$scheme $quote[REDACTED]$quote"
            } else {
                "$scheme [REDACTED]"
            }
        }

        // 3. Credentials in URLs (e.g. https://user:pass@host or https://token@host)
        text = URL_CREDENTIALS_PATTERN.replace(text) { matchResult ->
            val scheme = matchResult.groupValues[1]
            val creds = matchResult.groupValues[2]
            if (creds == "[REDACTED]" || creds == "[REDACTED]:[REDACTED]") {
                matchResult.value
            } else if (creds.contains(":")) {
                "$scheme[REDACTED]:[REDACTED]@"
            } else {
                "$scheme[REDACTED]@"
            }
        }

        // 4. Header-like / multiline cookie / set-cookie / qr_code patterns
        text = COOKIE_HEADER_PATTERN.replace(text) { matchResult ->
            val headerName = matchResult.groupValues[1]
            "$headerName: [REDACTED]"
        }

        text = QR_KEY_PATTERN.replace(text) { matchResult ->
            val keyName = matchResult.groupValues[1]
            val sep = matchResult.groupValues[2]
            if (sep.contains(":")) {
                "$keyName: [REDACTED]"
            } else {
                "$keyName=[REDACTED]"
            }
        }

        // 5. In-text key-value assignments (e.g. password="...", token=..., sentry_auth_token: ...)
        text = KEY_VALUE_PATTERN.replace(text) { matchResult ->
            val keyQuote = matchResult.groupValues[1]
            val key = matchResult.groupValues[2]
            val delimiter = matchResult.groupValues[3]
            val valQuote = matchResult.groupValues[4]
            val value = if (valQuote.isNotEmpty()) matchResult.groupValues[5] else matchResult.groupValues[6]
            if (value == "[REDACTED]") {
                matchResult.value
            } else if (delimiter.contains(":")) {
                "$keyQuote$key$keyQuote: $valQuote[REDACTED]$valQuote"
            } else {
                "$keyQuote$key$keyQuote=$valQuote[REDACTED]$valQuote"
            }
        }

        return text
    }

    fun redactMap(input: Map<*, *>): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((k, v) in input) {
            val keyStr = k?.toString() ?: "null"
            if (isCredentialKey(keyStr)) {
                result[keyStr] = "[REDACTED]"
            } else {
                result[keyStr] = redactAny(v)
            }
        }
        return result
    }

    fun redactList(input: List<*>): List<Any?> {
        return input.map { redactAny(it) }
    }

    fun redactJson(input: JsonElement): JsonElement {
        return when (input) {
            is JsonPrimitive -> {
                if (input.isString) {
                    JsonPrimitive(redactString(input.content))
                } else {
                    input
                }
            }
            is JsonArray -> {
                JsonArray(input.map { redactJson(it) })
            }
            is JsonObject -> {
                val newEntries = mutableMapOf<String, JsonElement>()
                for ((key, value) in input) {
                    if (isCredentialKey(key)) {
                        newEntries[key] = JsonPrimitive("[REDACTED]")
                    } else {
                        newEntries[key] = redactJson(value)
                    }
                }
                JsonObject(newEntries)
            }
        }
    }

    fun redactAny(value: Any?): Any? {
        return when (value) {
            null -> null
            is String -> redactString(value)
            is Map<*, *> -> redactMap(value)
            is List<*> -> redactList(value)
            is JsonElement -> redactJson(value)
            is Throwable -> redactThrowable(value)
            is Number, is Boolean, is Char -> value
            else -> redactString(value.toString())
        }
    }

    fun redactThrowable(throwable: Throwable?): String? {
        if (throwable == null) return null
        return throwable.message?.let { redactString(it) }
    }

    companion object {
        private val PEM_PATTERN = Regex(
            """-----BEGIN [A-Z0-9_ -]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z0-9_ -]*PRIVATE KEY-----|$)"""
        )

        private val AUTH_SCHEME_PATTERN = Regex(
            """(?i)\b(bearer|basic)\s+(?:(["'])(?!\[REDACTED\]\2)([^\r\n]*?)\2|(?!\[REDACTED\])[^\s,;"'\r\n]+)"""
        )

        private val URL_CREDENTIALS_PATTERN = Regex(
            """([a-zA-Z][a-zA-Z0-9+.-]*:\/\/)([^@\/\s]+)@"""
        )

        private val COOKIE_HEADER_PATTERN = Regex(
            """(?i)\b(cookie|set-cookie|set_cookie)\s*:\s*(?!\[REDACTED\])[^\r\n]+"""
        )

        private val QR_KEY_PATTERN = Regex(
            """(?i)\b(qr_code|qr_string|qr_content|whatsapp_session)\s*([:=])\s*(?!\[REDACTED\])[^\r\n]+"""
        )

        private val KEY_VALUE_PATTERN = Regex(
            """(?i)(["']?)\b(password|passwd|pwd|secret|secret_token|client_secret|app_secret|shared_secret|api_secret|api_token|access_token|refresh_token|auth_token|id_token|todoist_token|sentry_auth_token|sentry_token|devicetoken|device_token|session_token|session_key|credentials|credential|private_key|api_key|token|cookie|set-cookie|set_cookie)\1\s*([:=])\s*(?:(["'])(?!\[REDACTED\]\4)([^\r\n]*?)\4|(?!\[REDACTED\])([^\s,;"'\r\n]+))"""
        )

        private val EXACT_CREDENTIAL_KEYS = setOf(
            "authorization", "proxyauthorization", "cookie", "setcookie",
            "password", "passwd", "pwd", "secret", "privatekey", "credentials",
            "credential", "apikey", "apitoken", "accesstoken", "refreshtoken",
            "authtoken", "idtoken", "token", "todoisttoken", "sentryauthtoken",
            "sentrytoken", "devicetoken", "clientsecret", "appsecret", "sharedsecret",
            "apisecret", "sessiontoken", "sessionkey", "whatsappsession",
            "qrcode", "qrcontent", "qrstring", "sessiondata"
        )

        fun isCredentialKey(rawKey: String): Boolean {
            val normalized = rawKey.lowercase().replace("-", "").replace("_", "")
            if (normalized == "reportsessionid") return false
            if (normalized == "occurrencekey") return false
            if (normalized == "deviceid") return false
            if (normalized == "taskid") return false
            if (normalized == "actionid") return false
            if (normalized == "requestid") return false
            if (normalized == "traceid") return false
            if (normalized in EXACT_CREDENTIAL_KEYS) return true
            if (normalized.endsWith("token") ||
                normalized.endsWith("secret") ||
                normalized.endsWith("password") ||
                normalized.endsWith("privatekey") ||
                normalized.endsWith("apikey")
            ) {
                return true
            }
            return false
        }
    }
}
