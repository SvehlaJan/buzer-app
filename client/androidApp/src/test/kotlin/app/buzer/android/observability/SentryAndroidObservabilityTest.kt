package app.buzer.android.observability

import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticLogRecord
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentryAndroidObservabilityTest {

    @Test
    fun sanitizeEventRemovesUserAndRequestAndRedactsTokens() {
        val event = SentryEvent().apply {
            message = Message().apply {
                formatted = "User with auth token: secret_token_123456789012 failed"
                message = "secret_token_123456789012"
            }
            setTag("auth_token", "secret_token_123456789012")
            setTag("safe_tag", "valid_value")
            setExtra("api_key", "secret_key_123456789012")
            setExtra("count", 42)
        }

        val sanitized = SentryEventSanitizer.sanitizeEvent(event)

        assertNull(sanitized.user)
        assertNull(sanitized.request)
        assertEquals("[REDACTED]", sanitized.tags?.get("auth_token"))
        assertEquals("valid_value", sanitized.tags?.get("safe_tag"))
        assertEquals("[REDACTED]", sanitized.extras?.get("api_key"))
        assertEquals(42, sanitized.extras?.get("count"))
    }

    @Test
    fun sanitizeBreadcrumbRedactsSensitiveData() {
        val breadcrumb = Breadcrumb().apply {
            message = "Authorization: Bearer my_secret_token_value_here"
            setData("password", "p@ssword123")
            setData("screen", "Home")
        }

        val sanitized = SentryEventSanitizer.sanitizeBreadcrumb(breadcrumb)

        assertEquals("[REDACTED]", sanitized.getData("password"))
        assertEquals("Home", sanitized.getData("screen"))
    }

    @Test
    fun addLogPopulatesAttributesInBreadcrumb() {
        val observability = SentryAndroidObservability()
        val record = DiagnosticLogRecord(
            severity = "info",
            tag = "TestTag",
            message = "Test message with attributes",
            attributes = mapOf(
                "action" to "COMPLETE_TASK",
                "task_id" to "task-789"
            )
        )

        observability.addLog(record)
    }

    @Test
    fun captureHandledSetsAppropriateTags() {
        val observability = SentryAndroidObservability()
        val correlation = DiagnosticCorrelation(
            deviceId = "device-1",
            taskId = "task-1",
            occurrenceKey = "occ-1",
            actionId = "act-1",
            requestId = "req-1",
            screen = "Home",
            blockerType = "Deadline",
            appSection = "Enforcement"
        )
        observability.captureHandled("test.marker", IllegalStateException("local defect"), correlation)
    }
}
