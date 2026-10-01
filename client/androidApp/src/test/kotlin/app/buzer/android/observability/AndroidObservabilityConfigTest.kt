package app.buzer.android.observability

import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticLogRecord
import io.sentry.android.core.SentryAndroidOptions
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidObservabilityConfigTest {

    private class DummyContext : android.content.ContextWrapper(null)

    @Before
    fun setup() {
        ObservabilityRuntime.resetForTests()
    }

    @After
    fun tearDown() {
        ObservabilityRuntime.resetForTests()
    }

    @Test
    fun blankDsnInitializesNoOpInstance() {
        val dummyContext = DummyContext()
        val instance = ObservabilityRuntime.initialize(
            context = dummyContext,
            dsn = "",
            environment = "test-env",
            release = "app.buzer@1.0.0+test"
        )

        assertSame(NoOpAndroidObservability, instance)
        assertSame(NoOpAndroidObservability, ObservabilityRuntime.instance)

        // Verify NoOp methods behave safely
        instance.addLog(
            DiagnosticLogRecord(
                severity = "info",
                tag = "Buzer",
                message = "Test message"
            )
        )
        instance.captureHandled("test.marker", RuntimeException("Boom"))
        instance.showFeedback()
    }

    @Test
    fun recordingAndroidObservabilityRecordsAllInvocations() {
        val recording = RecordingAndroidObservability()
        ObservabilityRuntime.resetForTests(recording)

        // 1. addLog via AppLog
        AppLog.info("Info log message")
        AppLog.error("Error log message", IllegalStateException("Error detail"))
        assertEquals(2, recording.logs.size)
        assertEquals("Info log message", recording.logs[0].message)
        assertEquals("info", recording.logs[0].severity)
        assertEquals("Error log message", recording.logs[1].message)
        assertEquals("error", recording.logs[1].severity)
        assertEquals("IllegalStateException", recording.logs[1].throwableType)
        assertEquals("Error detail", recording.logs[1].throwableMessage)

        // 2. captureHandled
        val correlation = DiagnosticCorrelation(
            deviceId = "dev-42",
            taskId = "task-99",
            occurrenceKey = "occ-1",
            actionId = "act-1",
            requestId = "req-1",
            screen = "Home",
            blockerType = "alarm",
            appSection = "timeline"
        )
        val ex = RuntimeException("Handled exception")
        recording.captureHandled("sync.error", ex, correlation)
        assertEquals(1, recording.handledExceptions.size)
        assertEquals("sync.error", recording.handledExceptions.single().marker)
        assertSame(ex, recording.handledExceptions.single().throwable)
        assertEquals(correlation, recording.handledExceptions.single().context)

        // 3. showFeedback
        recording.showFeedback()
        assertEquals(1, recording.feedbackShowCount)

        // 4. setScreenContext keeps the latest value, including a clear
        recording.setScreenContext(ScreenContext(screen = "Edit", taskId = "task-99"))
        assertEquals(ScreenContext(screen = "Edit", taskId = "task-99"), recording.screenContext)
        recording.setScreenContext(null)
        assertNull(recording.screenContext)

        // 5. reset
        recording.setScreenContext(ScreenContext(screen = "Home", taskId = null))
        recording.reset()
        assertEquals(0, recording.logs.size)
        assertEquals(0, recording.handledExceptions.size)
        assertEquals(0, recording.feedbackShowCount)
        assertNull(recording.screenContext)
    }

    @Test
    fun sentryOptionsConfiguresPrivacySecurityAndFeedbackDefaults() {
        val options = SentryAndroidOptions()
        ObservabilityRuntime.configureSentryOptions(
            options = options,
            dsn = "https://examplePublicKey@o0.ingest.sentry.io/0",
            environment = "staging",
            release = "app.buzer@1.2.3+45"
        )

        assertEquals("https://examplePublicKey@o0.ingest.sentry.io/0", options.dsn)
        assertEquals("staging", options.environment)
        assertEquals("app.buzer@1.2.3+45", options.release)
        assertEquals(1.0, options.sampleRate)
        assertNull(options.tracesSampleRate)
        assertEquals(1.0, options.sessionReplay.onErrorSampleRate)
        assertEquals(0.0, options.sessionReplay.sessionSampleRate)
        assertTrue(options.isAnrEnabled)
        assertFalse(options.isSendDefaultPii)
        assertTrue(options.isAttachScreenshot)
        assertTrue(options.isAttachViewHierarchy)
        assertNotNull(options.beforeSend)
        assertNotNull(options.beforeBreadcrumb)
        assertTrue(options.logs.isEnabled)
        assertNotNull(options.logs.beforeSend)

        val feedback = options.feedbackOptions
        assertTrue(feedback.isUseShakeGesture)
        assertFalse(feedback.isUseSentryUser)
        assertFalse(feedback.isShowName)
        assertFalse(feedback.isShowEmail)
        assertFalse(feedback.isShowBranding)
        assertEquals("Report a bug", feedback.formTitle)
        assertEquals("What went wrong?", feedback.messageLabel)
        assertEquals("What did you expect, and what happened?", feedback.messagePlaceholder)
        assertEquals("Send report", feedback.submitButtonLabel)
        assertEquals("Report sent. Thank you.", feedback.successMessageText)
        assertNotNull(feedback.onFormOpen)
        assertNotNull(feedback.onSubmitSuccess)
    }
}
