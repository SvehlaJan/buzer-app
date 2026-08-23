package app.buzer.shared.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticRedactorTest {
    private val redactor = DiagnosticRedactor()

    @Test
    fun redactsNestedCredentialKeysAndEmbeddedBearerValues() {
        val value = redactor.redactMap(
            mapOf(
                "authorization" to "Bearer SECRET_ANDROID_123",
                "nested" to mapOf("TodoistToken" to "SECRET_TODOIST_123"),
                "message" to "request failed Authorization: Bearer SECRET_ANDROID_123"
            )
        ).toString()

        assertFalse(value.contains("SECRET_ANDROID_123"))
        assertFalse(value.contains("SECRET_TODOIST_123"))
        assertTrue(value.contains("[REDACTED]"))
    }

    @Test
    fun redactsBearerAndBasicAuthTokensInStrings() {
        val bearerUpper = redactor.redactString("Authorization: Bearer SECRET_ANDROID_123")
        assertEquals("Authorization: Bearer [REDACTED]", bearerUpper)

        val bearerLower = redactor.redactString("header bearer SECRET_ANDROID_123 attached")
        assertEquals("header bearer [REDACTED] attached", bearerLower)

        val bearerQuoted = redactor.redactString("Bearer \"SECRET_BEARER_123\"")
        assertEquals("Bearer \"[REDACTED]\"", bearerQuoted)

        val bearerSingleQuoted = redactor.redactString("Bearer 'SECRET_BEARER_123'")
        assertEquals("Bearer '[REDACTED]'", bearerSingleQuoted)

        val basicUpper = redactor.redactString("Authorization: Basic SECRET_BASIC_AUTH_ABC")
        assertEquals("Authorization: Basic [REDACTED]", basicUpper)

        val basicLower = redactor.redactString("auth basic SECRET_BASIC_AUTH_ABC header")
        assertEquals("auth basic [REDACTED] header", basicLower)
    }

    @Test
    fun redactsCredentialsInUrls() {
        val userPassUrl = redactor.redactString("https://user:SECRET_PASSWORD_999@api.example.com/v1/sync")
        assertEquals("https://[REDACTED]:[REDACTED]@api.example.com/v1/sync", userPassUrl)

        val userOnlyUrl = redactor.redactString("https://SECRET_TODOIST_123@api.todoist.com/rest/v2/tasks")
        assertEquals("https://[REDACTED]@api.todoist.com/rest/v2/tasks", userOnlyUrl)
    }

    @Test
    fun redactsPemBlocks() {
        val pemSingle = redactor.redactString(
            "-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC...\nSECRET_PRIVATE_KEY_456\n-----END PRIVATE KEY-----"
        )
        assertEquals("[REDACTED]", pemSingle)

        val pemRsa = redactor.redactString(
            "key data:\n-----BEGIN RSA PRIVATE KEY-----\nSECRET_PRIVATE_KEY_456\n-----END RSA PRIVATE KEY-----\nend key"
        )
        assertEquals("key data:\n[REDACTED]\nend key", pemRsa)
    }

    @Test
    fun redactsKeyValuePairsInLogs() {
        assertEquals("todoist_token=[REDACTED] status=error", redactor.redactString("todoist_token=SECRET_TODOIST_123 status=error"))
        assertEquals("sentry_auth_token: [REDACTED]", redactor.redactString("sentry_auth_token: SECRET_SENTRY_123"))
        assertEquals("password=\"[REDACTED]\" in payload", redactor.redactString("password=\"SECRET_PASSWORD_999\" in payload"))
        assertEquals("password=\"[REDACTED]\"", redactor.redactString("password=\"my secret passphrase with spaces\""))
        assertEquals("secret='[REDACTED]'", redactor.redactString("secret='single quoted secret with spaces'"))
        assertEquals("cookie=[REDACTED]", redactor.redactString("cookie=SECRET_COOKIE_123"))
        assertEquals("set_cookie=[REDACTED]", redactor.redactString("set_cookie=SECRET_COOKIE_123"))
        assertEquals("set-cookie=[REDACTED]", redactor.redactString("set-cookie=SECRET_COOKIE_123"))
        assertEquals("cookie: [REDACTED]", redactor.redactString("cookie: session=SECRET_COOKIE_789; other=123"))
        assertEquals("Set-Cookie: [REDACTED]", redactor.redactString("Set-Cookie: auth_token=SECRET_COOKIE_789; Secure"))
        assertEquals("qr_code: [REDACTED]", redactor.redactString("qr_code: 1@SECRET_WHATSAPP_123,abc,xyz"))
    }

    @Test
    fun redactsQuotedSecretsAndSentinelsAreCompletelyAbsent() {
        val input = """
            password="my secret passphrase with spaces"
            secret='single quoted secret with spaces'
            cookie=SECRET_COOKIE_123
            Bearer "SECRET_BEARER_123"
        """.trimIndent()

        val redacted = redactor.redactString(input)

        val sentinels = listOf(
            "my secret passphrase with spaces",
            "single quoted secret with spaces",
            "SECRET_COOKIE_123",
            "SECRET_BEARER_123"
        )
        for (sentinel in sentinels) {
            assertFalse(redacted.contains(sentinel), "Expected sentinel '$sentinel' to be absent")
        }

        assertTrue(redacted.contains("password=\"[REDACTED]\""))
        assertTrue(redacted.contains("secret='[REDACTED]'"))
        assertTrue(redacted.contains("cookie=[REDACTED]"))
        assertTrue(redacted.contains("Bearer \"[REDACTED]\""))
    }

    @Test
    fun preservesAlreadyRedactedAndSafeValues() {
        assertEquals("Authorization: Bearer [REDACTED]", redactor.redactString("Authorization: Bearer [REDACTED]"))
        assertEquals("Authorization: Bearer \"[REDACTED]\"", redactor.redactString("Authorization: Bearer \"[REDACTED]\""))
        assertEquals("api_token=[REDACTED]", redactor.redactString("api_token=[REDACTED]"))
        assertEquals("password=\"[REDACTED]\"", redactor.redactString("password=\"[REDACTED]\""))
        assertEquals("secret='[REDACTED]'", redactor.redactString("secret='[REDACTED]'"))
        assertEquals("Regular safe log message with id=task-123", redactor.redactString("Regular safe log message with id=task-123"))
    }

    @Test
    fun redactsMapsListsAndJsonElements() {
        val inputMap = mapOf(
            "authorization" to "Bearer SECRET_ANDROID_123",
            "cookie" to "session=SECRET_COOKIE_789",
            "set-cookie" to "auth=SECRET_COOKIE_789",
            "token" to "SECRET_ANDROID_123",
            "api_token" to "SECRET_ANDROID_123",
            "access_token" to "SECRET_ANDROID_123",
            "refresh_token" to "SECRET_REFRESH_TOKEN_000",
            "password" to "SECRET_PASSWORD_999",
            "secret" to "SECRET_SENTRY_123",
            "private_key" to "SECRET_PRIVATE_KEY_456",
            "nested" to mapOf(
                "TodoistToken" to "SECRET_TODOIST_123",
                "sentry_auth_token" to "SECRET_SENTRY_123",
                "password" to "SECRET_PASSWORD_999",
                "api_token" to "SECRET_ANDROID_123",
                "access_token" to "SECRET_ANDROID_123",
                "refresh_token" to "SECRET_REFRESH_TOKEN_000",
                "secret" to "SECRET_SENTRY_123",
                "private_key" to "-----BEGIN PRIVATE KEY-----\nSECRET_PRIVATE_KEY_456\n-----END PRIVATE KEY-----",
                "whatsapp_session" to "SECRET_WHATSAPP_123"
            ),
            "items" to listOf(
                "Bearer SECRET_ANDROID_123",
                mapOf("password" to "SECRET_PASSWORD_999")
            ),
            "message" to "request failed Authorization: Bearer SECRET_ANDROID_123",
            "safeKey" to "safeValue",
            "occurrenceKey" to "occurrence_123",
            "reportSessionId" to "session_abc"
        )

        val redactedMap = redactor.redactMap(inputMap)
        val serializedMap = redactedMap.toString()

        val sentinels = listOf(
            "SECRET_TODOIST_123",
            "SECRET_ANDROID_123",
            "SECRET_WHATSAPP_123",
            "SECRET_SENTRY_123",
            "SECRET_PASSWORD_999",
            "SECRET_PRIVATE_KEY_456",
            "SECRET_COOKIE_789",
            "SECRET_REFRESH_TOKEN_000"
        )
        for (sentinel in sentinels) {
            assertFalse(serializedMap.contains(sentinel), "Map should not contain $sentinel")
        }

        assertEquals("safeValue", redactedMap["safeKey"])
        assertEquals("occurrence_123", redactedMap["occurrenceKey"])
        assertEquals("session_abc", redactedMap["reportSessionId"])
        assertEquals("[REDACTED]", redactedMap["authorization"])

        // Test JsonElement redaction
        val jsonInput = JsonObject(
            mapOf(
                "authorization" to JsonPrimitive("Bearer SECRET_ANDROID_123"),
                "nested" to JsonObject(mapOf("TodoistToken" to JsonPrimitive("SECRET_TODOIST_123"))),
                "items" to JsonArray(listOf(JsonPrimitive("Bearer SECRET_ANDROID_123"))),
                "safeKey" to JsonPrimitive("safeValue")
            )
        )
        val redactedJson = redactor.redactJson(jsonInput)
        val serializedJson = Json.encodeToString(JsonElement.serializer(), redactedJson)
        for (sentinel in listOf("SECRET_TODOIST_123", "SECRET_ANDROID_123")) {
            assertFalse(serializedJson.contains(sentinel), "Json should not contain $sentinel")
        }
        assertTrue(serializedJson.contains("[REDACTED]"))
    }

    @Test
    fun redactsGenericAuthAndSuffixCookieKeys() {
        assertEquals("auth=[REDACTED]", redactor.redactString("auth=SECRET_AUTH_123"))
        assertEquals("session_cookie=[REDACTED]", redactor.redactString("session_cookie=SECRET_COOKIE_789"))

        val redacted = redactor.redactMap(
            mapOf(
                "auth" to "SECRET_AUTH_123",
                "session_cookie" to "SECRET_COOKIE_789"
            )
        )

        assertEquals("[REDACTED]", redacted["auth"])
        assertEquals("[REDACTED]", redacted["session_cookie"])
    }
}
