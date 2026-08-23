package app.buzer.android.observability

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentryEventSanitizerTest {

    private val sentinels = listOf(
        "SECRET_TODOIST_123",
        "SECRET_ANDROID_123",
        "SECRET_WHATSAPP_123",
        "SECRET_SENTRY_123",
        "SECRET_PASSWORD_999",
        "SECRET_PRIVATE_KEY_456",
        "SECRET_COOKIE_789",
        "SECRET_BEARER_123"
    )

    @Test
    fun removesRequestAndUserFromEvent() {
        val event = SentryEvent().apply {
            request = Request().apply {
                url = "https://api.example.com/tasks?token=SECRET_TODOIST_123"
                queryString = "token=SECRET_TODOIST_123"
                headers = mapOf("Authorization" to "Bearer SECRET_BEARER_123")
            }
            user = User().apply {
                id = "user-123"
                email = "user@example.com"
                ipAddress = "192.168.1.1"
            }
        }

        val sanitized = SentryEventSanitizer.sanitizeEvent(event)
        assertNull(sanitized.request)
        assertNull(sanitized.user)
    }

    @Test
    fun sanitizesEventMessageExceptionsTagsContextsBreadcrumbsAndExtras() {
        val event = SentryEvent().apply {
            message = Message().apply {
                formatted = "Request failed with Authorization: Bearer SECRET_BEARER_123 and todoist_token=SECRET_TODOIST_123"
                message = "Raw message with password=\"SECRET_PASSWORD_999\""
            }

            val exception = SentryException().apply {
                type = "AuthenticationException"
                value = "Failed connecting to https://user:SECRET_PASSWORD_999@api.example.com/sync with token=SECRET_ANDROID_123"
            }
            exceptions = listOf(exception)

            setTag("todoist_token", "SECRET_TODOIST_123")
            setTag("authorization", "Bearer SECRET_BEARER_123")
            setTag("custom_tag", "Error occurred with api_key=SECRET_SENTRY_123")
            setTag("report_session_id", "session-clean-123")
            setTag("device_id", "device-clean-456")

            val diagMap = mutableMapOf<String, Any>(
                "device_id" to "device-clean-456",
                "auth_header" to "Bearer SECRET_BEARER_123",
                "nested_token" to "todoist_token=SECRET_TODOIST_123",
                "qr_code" to "qr_code: 1@SECRET_WHATSAPP_123,abc"
            )
            contexts["diagnostic"] = diagMap
            contexts["password"] = "SECRET_PASSWORD_999"

            val breadcrumb = Breadcrumb().apply {
                message = "API request failed with cookie: session=SECRET_COOKIE_789"
                setData("token", "SECRET_ANDROID_123")
                setData("details", "Sent todoist_token=SECRET_TODOIST_123 in payload")
            }
            breadcrumbs = listOf(breadcrumb)

            fingerprints = listOf("sync-error-token=SECRET_TODOIST_123", "normal-fingerprint")

            setExtra("credential_dump", "secret='SECRET_PRIVATE_KEY_456'")
            setExtra("api_key", "SECRET_ANDROID_123")
        }

        val sanitized = SentryEventSanitizer.sanitizeEvent(event)

        // 1. Message
        assertNotNull(sanitized.message?.formatted)
        assertFalse(sanitized.message!!.formatted!!.contains("SECRET_BEARER_123"))
        assertFalse(sanitized.message!!.formatted!!.contains("SECRET_TODOIST_123"))
        assertTrue(sanitized.message!!.formatted!!.contains("[REDACTED]"))

        assertNotNull(sanitized.message?.message)
        assertFalse(sanitized.message!!.message!!.contains("SECRET_PASSWORD_999"))
        assertTrue(sanitized.message!!.message!!.contains("[REDACTED]"))

        // 2. Exceptions
        val ex = sanitized.exceptions!!.single()
        assertNotNull(ex.value)
        assertFalse(ex.value!!.contains("SECRET_PASSWORD_999"))
        assertFalse(ex.value!!.contains("SECRET_ANDROID_123"))
        assertTrue(ex.value!!.contains("[REDACTED]"))

        // 3. Tags
        assertEquals("[REDACTED]", sanitized.getTag("todoist_token"))
        assertEquals("[REDACTED]", sanitized.getTag("authorization"))
        assertFalse(sanitized.getTag("custom_tag")!!.contains("SECRET_SENTRY_123"))
        assertTrue(sanitized.getTag("custom_tag")!!.contains("[REDACTED]"))
        assertEquals("session-clean-123", sanitized.getTag("report_session_id"))
        assertEquals("device-clean-456", sanitized.getTag("device_id"))

        // 4. Contexts
        assertEquals("[REDACTED]", sanitized.contexts["password"])
        @Suppress("UNCHECKED_CAST")
        val sanitizedDiag = sanitized.contexts["diagnostic"] as Map<String, Any?>
        assertEquals("device-clean-456", sanitizedDiag["device_id"])
        assertEquals("Bearer [REDACTED]", sanitizedDiag["auth_header"])
        assertFalse((sanitizedDiag["nested_token"] as String).contains("SECRET_TODOIST_123"))
        assertTrue((sanitizedDiag["nested_token"] as String).contains("[REDACTED]"))
        assertFalse((sanitizedDiag["qr_code"] as String).contains("SECRET_WHATSAPP_123"))
        assertTrue((sanitizedDiag["qr_code"] as String).contains("[REDACTED]"))

        // 5. Breadcrumbs
        val bc = sanitized.breadcrumbs!!.single()
        assertFalse(bc.message!!.contains("SECRET_COOKIE_789"))
        assertTrue(bc.message!!.contains("[REDACTED]"))
        assertEquals("[REDACTED]", bc.getData("token"))
        assertFalse((bc.getData("details") as String).contains("SECRET_TODOIST_123"))
        assertTrue((bc.getData("details") as String).contains("[REDACTED]"))

        // 6. Fingerprints
        assertFalse(sanitized.fingerprints!![0].contains("SECRET_TODOIST_123"))
        assertEquals("normal-fingerprint", sanitized.fingerprints!![1])

        // 7. Extras
        assertFalse((sanitized.getExtra("credential_dump") as String).contains("SECRET_PRIVATE_KEY_456"))
        assertTrue((sanitized.getExtra("credential_dump") as String).contains("[REDACTED]"))
        assertEquals("[REDACTED]", sanitized.getExtra("api_key"))

        // Comprehensive check: none of the sentinels appear anywhere in the sanitized event string representation
        val fullString = sanitized.toString()
        for (sentinel in sentinels) {
            assertFalse(fullString.contains(sentinel), "Sanitized event leaked sentinel: $sentinel")
        }
    }

    @Test
    fun sanitizesStandaloneBreadcrumb() {
        val breadcrumb = Breadcrumb().apply {
            category = "http"
            level = SentryLevel.INFO
            message = "Calling API with Bearer SECRET_BEARER_123"
            setData("auth_token", "SECRET_ANDROID_123")
            setData("url", "https://SECRET_TODOIST_123@api.todoist.com/tasks")
        }

        val sanitized = SentryEventSanitizer.sanitizeBreadcrumb(breadcrumb)

        assertFalse(sanitized.message!!.contains("SECRET_BEARER_123"))
        assertTrue(sanitized.message!!.contains("[REDACTED]"))
        assertEquals("[REDACTED]", sanitized.getData("auth_token"))
        assertFalse((sanitized.getData("url") as String).contains("SECRET_TODOIST_123"))
        assertTrue((sanitized.getData("url") as String).contains("[REDACTED]"))
    }

    @Test
    fun sanitizesContextsWithListsAndNestedObjects() {
        val contexts = io.sentry.protocol.Contexts().apply {
            this["todoist_token"] = "SECRET_TODOIST_123"
            this["list_data"] = listOf(
                "Bearer SECRET_BEARER_123",
                mapOf(
                    "api_key" to "SECRET_ANDROID_123",
                    "safe_key" to "password=\"SECRET_PASSWORD_999\""
                )
            )
            this["nested_object"] = mapOf(
                "headers" to listOf("Cookie: session=SECRET_COOKIE_789"),
                "token" to "SECRET_SENTRY_123"
            )
        }

        val sanitized = SentryEventSanitizer.sanitizeContexts(contexts)

        assertEquals("[REDACTED]", sanitized["todoist_token"])

        @Suppress("UNCHECKED_CAST")
        val listData = sanitized["list_data"] as List<Any?>
        assertEquals("Bearer [REDACTED]", listData[0])
        val nestedMap = listData[1] as Map<String, Any?>
        assertEquals("[REDACTED]", nestedMap["api_key"])
        assertEquals("password=\"[REDACTED]\"", nestedMap["safe_key"])

        @Suppress("UNCHECKED_CAST")
        val nestedObj = sanitized["nested_object"] as Map<String, Any?>
        val headers = nestedObj["headers"] as List<Any?>
        assertEquals("Cookie: [REDACTED]", headers[0])
        assertEquals("[REDACTED]", nestedObj["token"])
    }

}
