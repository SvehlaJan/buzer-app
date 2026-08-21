package app.buzer.android.observability

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.buzer.android.MainActivity
import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticReportRequest
import app.buzer.shared.diagnostics.DiagnosticReportResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BugReportFlowInstrumentationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val fakeObservability = RecordingAndroidObservability()

    private class TestTemporaryScopeModifier : TemporaryScopeModifier {
        var currentReportSessionId: String? = null
        var currentAndroidDiagnosticEventId: String? = null

        override fun setTemporaryScope(reportSessionId: String, androidDiagnosticEventId: String) {
            currentReportSessionId = reportSessionId
            currentAndroidDiagnosticEventId = androidDiagnosticEventId
        }

        override fun clearTemporaryScope() {
            currentReportSessionId = null
            currentAndroidDiagnosticEventId = null
        }
    }

    private class TestBackendReportSender {
        val requests = mutableListOf<DiagnosticReportRequest>()

        suspend fun sendReport(request: DiagnosticReportRequest): DiagnosticReportResponse {
            requests.add(request)
            return DiagnosticReportResponse(
                schemaVersion = 1,
                reportSessionId = request.reportSessionId,
                backendDiagnosticEventId = "00000000000000000000000000000001",
                outcome = "captured"
            )
        }
    }

    @Before
    fun setUp() {
        ObservabilityRuntime.resetForTests(fakeObservability)
        DiagnosticContextStore.reset()
        DiagnosticRingBuffer.reset()
    }

    @After
    fun tearDown() {
        ObservabilityRuntime.resetForTests()
        DiagnosticContextStore.reset()
        DiagnosticRingBuffer.reset()
    }

    @Test
    fun reportBugFallbackActionInvokesObservabilityShowFeedback() {
        composeRule.onNodeWithContentDescription("Report a bug")
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, fakeObservability.feedbackRequests.size)
    }

    @Test
    fun openingReportFormFreezesContextSnapshotAndBeginsAttachmentGathering() = runBlocking {
        val scopeModifier = TestTemporaryScopeModifier()
        val backendSender = TestBackendReportSender()

        val coordinator = ManualReportCoordinator(
            contextStore = DiagnosticContextStore,
            attachmentCollector = AndroidAttachmentCollector(
                contextStore = DiagnosticContextStore,
                ringBuffer = DiagnosticRingBuffer,
                screenshotProvider = { "screenshot-data".toByteArray() },
                threadDumpProvider = { "thread-dump" }
            ),
            observability = fakeObservability,
            scopeModifier = scopeModifier,
            backendReportSender = { backendSender.sendReport(it) },
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        DiagnosticContextStore.setCorrelation(
            DiagnosticCorrelation(
                deviceId = "device-freeze-1",
                taskId = "task-initial-123",
                screen = "Home"
            )
        )

        val attachJob = coordinator.onFormOpen()
        attachJob.join()

        // Verify temporary scope is active
        assertNotNull(scopeModifier.currentReportSessionId)
        assertNotNull(scopeModifier.currentAndroidDiagnosticEventId)

        // Mutate context after opening form
        DiagnosticContextStore.setCorrelation(
            DiagnosticCorrelation(
                deviceId = "device-freeze-1",
                taskId = "task-mutated-after-freeze",
                screen = "Editor"
            )
        )

        coordinator.onSubmitSuccess(null)?.join()

        assertEquals(1, fakeObservability.diagnosticEvents.size)
        val event = fakeObservability.diagnosticEvents.single()

        // Asserts frozen snapshot values were used
        assertEquals("task-initial-123", event.context.taskId)
        assertEquals("Home", event.context.screen)

        assertEquals(1, backendSender.requests.size)
        val request = backendSender.requests.single()
        assertEquals("task-initial-123", request.taskId)
    }

    @Test
    fun submissionCreatesPairedManualReportEventWithRequiredTagsAndAttachments() = runBlocking {
        val scopeModifier = TestTemporaryScopeModifier()
        val backendSender = TestBackendReportSender()

        val coordinator = ManualReportCoordinator(
            contextStore = DiagnosticContextStore,
            attachmentCollector = AndroidAttachmentCollector(
                contextStore = DiagnosticContextStore,
                ringBuffer = DiagnosticRingBuffer,
                screenshotProvider = { "screenshot-data".toByteArray() },
                threadDumpProvider = { "thread-dump" }
            ),
            observability = fakeObservability,
            scopeModifier = scopeModifier,
            backendReportSender = { backendSender.sendReport(it) },
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        DiagnosticContextStore.setCorrelation(
            DiagnosticCorrelation(
                deviceId = "device-test-1",
                taskId = "task-test-456",
                occurrenceKey = "occ-key-789",
                screen = "Home"
            )
        )

        coordinator.onFormOpen().join()
        coordinator.onSubmitSuccess(null)?.join()

        assertEquals(1, fakeObservability.diagnosticEvents.size)
        val event = fakeObservability.diagnosticEvents.single()

        assertTrue(event.submitted)
        assertEquals("device-test-1", event.context.deviceId)
        assertEquals("task-test-456", event.context.taskId)
        assertEquals("occ-key-789", event.context.occurrenceKey)
        assertEquals("Home", event.context.screen)
        assertTrue(event.eventId.isNotBlank())
        assertTrue(event.reportSessionId.isNotBlank())

        val attachmentNames = event.attachments.map { it.pathname }
        assertTrue(attachmentNames.contains("android-diagnostics-v1.json"))
        assertTrue(attachmentNames.contains("android-app-log.txt"))
        assertTrue(attachmentNames.contains("android-network-history.json"))
    }

    @Test
    fun submissionDispatchesBackendDiagnosticReportRequest() = runBlocking {
        val scopeModifier = TestTemporaryScopeModifier()
        val backendSender = TestBackendReportSender()

        val coordinator = ManualReportCoordinator(
            contextStore = DiagnosticContextStore,
            attachmentCollector = AndroidAttachmentCollector(
                contextStore = DiagnosticContextStore,
                ringBuffer = DiagnosticRingBuffer,
                screenshotProvider = { "screenshot-data".toByteArray() },
                threadDumpProvider = { "thread-dump" }
            ),
            observability = fakeObservability,
            scopeModifier = scopeModifier,
            backendReportSender = { backendSender.sendReport(it) },
            recentRequestIdsProvider = { listOf("req-recent-1", "req-recent-2") },
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        DiagnosticContextStore.setCorrelation(
            DiagnosticCorrelation(
                deviceId = "device-dispatch-1",
                taskId = "task-dispatch-100",
                occurrenceKey = "occ-dispatch-200",
                actionId = "act-dispatch-300",
                traceId = "trace-dispatch-400"
            )
        )

        coordinator.onFormOpen().join()
        coordinator.onSubmitSuccess(null)?.join()

        assertEquals(1, backendSender.requests.size)
        val req = backendSender.requests.single()
        val event = fakeObservability.diagnosticEvents.single()

        assertEquals(1, req.schemaVersion)
        assertEquals(event.reportSessionId, req.reportSessionId)
        assertEquals(event.eventId, req.androidDiagnosticEventId)
        assertEquals("device-dispatch-1", req.deviceId)
        assertEquals("task-dispatch-100", req.taskId)
        assertEquals("occ-dispatch-200", req.occurrenceKey)
        assertEquals("act-dispatch-300", req.actionId)
        assertEquals("trace-dispatch-400", req.traceId)
        assertEquals(listOf("req-recent-1", "req-recent-2"), req.requestIds)
    }
}
