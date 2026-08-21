package app.buzer.android.observability

import android.app.Activity
import android.content.Context
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticLogRecord
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

    private class TestActivity(
        private val finishing: Boolean = false,
        private val destroyed: Boolean = false
    ) : Activity() {
        override fun isFinishing(): Boolean = finishing
        override fun isDestroyed(): Boolean = destroyed
    }

    private class DummyContext : android.content.ContextWrapper(null)

    @Before
    fun setup() {
        ObservabilityRuntime.resetForTests()
    }

    @After
    fun tearDown() {
        ObservabilityRuntime.resetForTests()
        CurrentActivityTracker.setActivityForTest(null)
    }

    @Test
    fun blankDsnInitializesNoOpInstanceAndDisablesObservability() {
        val dummyContext = DummyContext()
        val instance = ObservabilityRuntime.initialize(
            context = dummyContext,
            dsn = "",
            environment = "test-env",
            release = "app.buzer@1.0.0+test"
        )

        assertSame(NoOpAndroidObservability, instance)
        assertFalse(instance.isEnabled())
        assertSame(NoOpAndroidObservability, ObservabilityRuntime.instance)

        // Verify NoOp methods behave safely
        instance.setContext(DiagnosticCorrelation(deviceId = "dev-1"))
        instance.addLog(
            DiagnosticLogRecord(
                capturedAt = "2026-08-21T12:00:00Z",
                severity = "info",
                tag = "Buzer",
                message = "Test message"
            ),
            null
        )
        assertNull(instance.captureHandled("test.marker", RuntimeException("Boom")))
        assertEquals("", instance.newEventId())
        val returnedEventId = instance.captureDiagnosticEvent(
            eventId = "evt-1",
            reportSessionId = "rep-1",
            submitted = true,
            context = DiagnosticCorrelation(),
            attachments = emptyList()
        )
        assertEquals("evt-1", returnedEventId)
        assertTrue(instance.flush(1000L))
    }

    @Test
    fun recordingAndroidObservabilityRecordsAllInvocations() {
        val recording = RecordingAndroidObservability()
        ObservabilityRuntime.resetForTests(recording)

        assertTrue(recording.isEnabled())

        // 1. setContext
        val correlation = DiagnosticCorrelation(
            deviceId = "dev-42",
            taskId = "task-99",
            occurrenceKey = "occ-1",
            actionId = "act-1",
            requestId = "req-1",
            traceId = "trace-1",
            screen = "Home",
            blockerType = "alarm",
            appSection = "timeline",
            reportSessionId = "rep-session-1"
        )
        recording.setContext(correlation)
        assertEquals(1, recording.contexts.size)
        assertEquals(correlation, recording.contexts.single())

        // 2. addLog via AppLog
        AppLog.info("Info log message")
        AppLog.error("Error log message", IllegalStateException("Error detail"))
        assertEquals(2, recording.logs.size)
        assertEquals("Info log message", recording.logs[0].first.message)
        assertEquals("info", recording.logs[0].first.severity)
        assertEquals("Error log message", recording.logs[1].first.message)
        assertEquals("error", recording.logs[1].first.severity)
        assertNotNull(recording.logs[1].second)

        // 3. captureHandled
        val ex = RuntimeException("Handled exception")
        val handledId = recording.captureHandled("sync.error", ex, correlation)
        assertNotNull(handledId)
        assertEquals(1, recording.handledExceptions.size)
        assertEquals("sync.error", recording.handledExceptions.single().marker)
        assertSame(ex, recording.handledExceptions.single().throwable)

        // 4. newEventId
        val id1 = recording.newEventId()
        assertNotNull(id1)
        assertTrue(id1.isNotBlank())

        // 5. captureDiagnosticEvent
        val attachment = DiagnosticAttachment(
            pathname = "test.txt",
            bytes = "content".encodeToByteArray(),
            contentType = "text/plain"
        )
        val eventId = recording.captureDiagnosticEvent(
            eventId = "diag-evt-100",
            reportSessionId = "rep-100",
            submitted = true,
            context = correlation,
            attachments = listOf(attachment)
        )
        assertEquals("diag-evt-100", eventId)
        assertEquals(1, recording.diagnosticEvents.size)
        val captured = recording.diagnosticEvents.single()
        assertEquals("diag-evt-100", captured.eventId)
        assertEquals("rep-100", captured.reportSessionId)
        assertTrue(captured.submitted)
        assertEquals(listOf(attachment), captured.attachments)

        // 6. showFeedback
        recording.showFeedback("event-123")
        assertEquals(listOf<String?>("event-123"), recording.feedbackRequests)

        // 7. flush
        assertTrue(recording.flush(2000L))
        assertEquals(1, recording.flushCount)

        // 8. reset
        recording.reset()
        assertEquals(0, recording.contexts.size)
        assertEquals(0, recording.logs.size)
        assertEquals(0, recording.handledExceptions.size)
        assertEquals(0, recording.diagnosticEvents.size)
        assertEquals(0, recording.feedbackRequests.size)
        assertEquals(0, recording.flushCount)
    }

    @Test
    fun currentActivityTrackerTracksActivityWeakly() {
        val activeActivity = TestActivity(finishing = false, destroyed = false)
        CurrentActivityTracker.onActivityStarted(activeActivity)
        CurrentActivityTracker.onActivityResumed(activeActivity)

        assertSame(activeActivity, CurrentActivityTracker.currentActivity)

        // When finishing, currentActivity should be null
        val finishingActivity = TestActivity(finishing = true, destroyed = false)
        CurrentActivityTracker.onActivityStarted(finishingActivity)
        assertNull(CurrentActivityTracker.currentActivity)

        // When destroyed, currentActivity should be null
        val destroyedActivity = TestActivity(finishing = false, destroyed = true)
        CurrentActivityTracker.onActivityStarted(destroyedActivity)
        assertNull(CurrentActivityTracker.currentActivity)

        // Lifecycle transitions: stopped/destroyed clears matching activity
        CurrentActivityTracker.onActivityStarted(activeActivity)
        assertSame(activeActivity, CurrentActivityTracker.currentActivity)
        CurrentActivityTracker.onActivityDestroyed(activeActivity)
        assertNull(CurrentActivityTracker.currentActivity)
    }
}
