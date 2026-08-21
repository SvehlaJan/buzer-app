package app.buzer.android.observability

import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticLogRecord
import app.buzer.shared.diagnostics.DiagnosticNetworkExchange
import app.buzer.shared.diagnostics.DiagnosticReportRequest
import app.buzer.shared.diagnostics.DiagnosticReportResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class FakeTemporaryScopeModifier : TemporaryScopeModifier {
    var currentReportSessionId: String? = null
    var currentAndroidDiagnosticEventId: String? = null
    var clearCount: Int = 0

    override fun setTemporaryScope(reportSessionId: String, androidDiagnosticEventId: String) {
        currentReportSessionId = reportSessionId
        currentAndroidDiagnosticEventId = androidDiagnosticEventId
    }

    override fun clearTemporaryScope() {
        currentReportSessionId = null
        currentAndroidDiagnosticEventId = null
        clearCount++
    }
}

class FakeBackendReportSender {
    val requests = mutableListOf<DiagnosticReportRequest>()
    var shouldFail: Boolean = false

    suspend fun sendReport(request: DiagnosticReportRequest): DiagnosticReportResponse {
        if (shouldFail) {
            throw IOException("Backend unavailable")
        }
        requests.add(request)
        return DiagnosticReportResponse(
            schemaVersion = 1,
            reportSessionId = request.reportSessionId,
            backendDiagnosticEventId = "backend-event-id-1234567890123456",
            outcome = "captured"
        )
    }
}

class ManualReportCoordinatorTest {
    private lateinit var contextStore: DiagnosticContextStore
    private lateinit var ringBuffer: DiagnosticRingBuffer
    private lateinit var observability: RecordingAndroidObservability
    private lateinit var scopeModifier: FakeTemporaryScopeModifier
    private lateinit var backendSender: FakeBackendReportSender
    private lateinit var attachmentCollector: AndroidAttachmentCollector
    private lateinit var coordinator: ManualReportCoordinator

    private var fixedUuidCounter = 0

    @Before
    fun setUp() {
        contextStore = DiagnosticContextStore()
        ringBuffer = DiagnosticRingBuffer()
        observability = RecordingAndroidObservability()
        scopeModifier = FakeTemporaryScopeModifier()
        backendSender = FakeBackendReportSender()
        fixedUuidCounter = 0

        attachmentCollector = AndroidAttachmentCollector(
            contextStore = contextStore,
            ringBuffer = ringBuffer,
            screenshotProvider = { "fake-screenshot".toByteArray() },
            threadDumpProvider = { "fake-threads" }
        )

        coordinator = ManualReportCoordinator(
            contextStore = contextStore,
            attachmentCollector = attachmentCollector,
            observability = observability,
            scopeModifier = scopeModifier,
            backendReportSender = { backendSender.sendReport(it) },
            recentRequestIdsProvider = { ringBuffer.network.snapshot(1724241600000L).map { it.requestId }.distinct().take(20) },
            uuidProvider = { "00000000-0000-0000-0000-${String.format("%012d", ++fixedUuidCounter)}" },
            clock = { 1724241600000L },
            scope = CoroutineScope(Dispatchers.Unconfined)
        )
    }

    @After
    fun tearDown() {
        contextStore.reset()
        ringBuffer.reset()
        coordinator.reset()
    }

    @Test
    fun successfulSubmissionEmitsPairedEventsAndClearsScope() = runBlocking {
        contextStore.setCorrelation(
            DiagnosticCorrelation(
                deviceId = "device-abc",
                taskId = "task-123",
                occurrenceKey = "occ-456",
                screen = "Home"
            )
        )

        coordinator.onFormOpen().join()
        assertEquals(ManualReportState.FORM_OPEN, coordinator.state)
        assertEquals("00000000-0000-0000-0000-000000000001", scopeModifier.currentReportSessionId)
        assertNotNull(scopeModifier.currentAndroidDiagnosticEventId)

        coordinator.onSubmitSuccess(null)?.join()

        assertEquals(ManualReportState.IDLE, coordinator.state)
        assertEquals(1, observability.diagnosticEvents.size)
        assertEquals(1, backendSender.requests.size)

        val sentryEvent = observability.diagnosticEvents.single()
        val backendRequest = backendSender.requests.single()

        assertEquals(sentryEvent.reportSessionId, backendRequest.reportSessionId)
        assertEquals(sentryEvent.eventId, backendRequest.androidDiagnosticEventId)
        assertTrue(sentryEvent.submitted)
        assertEquals("device-abc", sentryEvent.context.deviceId)
        assertEquals("task-123", sentryEvent.context.taskId)
        assertEquals("Home", sentryEvent.context.screen)

        assertEquals("device-abc", backendRequest.deviceId)
        assertEquals("task-123", backendRequest.taskId)
        assertEquals("occ-456", backendRequest.occurrenceKey)

        assertNull(scopeModifier.currentReportSessionId)
        assertNull(scopeModifier.currentAndroidDiagnosticEventId)
    }

    @Test
    fun onFormCloseWithoutSubmitCapturesOrphanEventAndClearsScope() = runBlocking {
        contextStore.setCorrelation(DiagnosticCorrelation(deviceId = "device-1", screen = "Home"))

        coordinator.onFormOpen().join()
        assertEquals(ManualReportState.FORM_OPEN, coordinator.state)

        coordinator.onFormClose()?.join()

        assertEquals(ManualReportState.IDLE, coordinator.state)
        assertEquals(1, observability.diagnosticEvents.size)
        assertEquals(0, backendSender.requests.size)

        val sentryEvent = observability.diagnosticEvents.single()
        assertFalse(sentryEvent.submitted)
        assertEquals("device-1", sentryEvent.context.deviceId)
        assertNull(scopeModifier.currentReportSessionId)
    }

    @Test
    fun onSubmitErrorCapturesOrphanEventAndClearsScope() = runBlocking {
        contextStore.setCorrelation(DiagnosticCorrelation(deviceId = "device-2"))

        coordinator.onFormOpen().join()
        coordinator.onSubmitError(RuntimeException("Failed to submit feedback"))?.join()

        assertEquals(ManualReportState.IDLE, coordinator.state)
        assertEquals(1, observability.diagnosticEvents.size)
        assertEquals(0, backendSender.requests.size)

        val sentryEvent = observability.diagnosticEvents.single()
        assertFalse(sentryEvent.submitted)
        assertNull(scopeModifier.currentReportSessionId)
    }

    @Test
    fun backendFailureIsIsolatedAndDoesNotSuppressSentryDiagnosticEvent() = runBlocking {
        backendSender.shouldFail = true

        coordinator.onFormOpen().join()
        coordinator.onSubmitSuccess(null)?.join()

        assertEquals(ManualReportState.IDLE, coordinator.state)
        assertEquals(1, observability.diagnosticEvents.size)
        assertTrue(observability.diagnosticEvents.single().submitted)
        assertNull(scopeModifier.currentReportSessionId)
    }

    @Test
    fun freezesDiagnosticContextSnapshotAtFormOpen() = runBlocking {
        contextStore.setCorrelation(DiagnosticCorrelation(taskId = "initial-task", screen = "ScreenA"))

        coordinator.onFormOpen().join()

        // Mutate context store after form opened
        contextStore.setCorrelation(DiagnosticCorrelation(taskId = "mutated-task", screen = "ScreenB"))

        coordinator.onSubmitSuccess(null)?.join()

        val sentryEvent = observability.diagnosticEvents.single()
        assertEquals("initial-task", sentryEvent.context.taskId)
        assertEquals("ScreenA", sentryEvent.context.screen)

        val backendRequest = backendSender.requests.single()
        assertEquals("initial-task", backendRequest.taskId)
    }

    @Test
    fun includesRecentRequestIdsInBackendReport() = runBlocking {
        ringBuffer.network.add(
            DiagnosticNetworkExchange(
                startedAt = "2026-08-21T12:00:00Z",
                durationMillis = 100L,
                method = "GET",
                path = "/tasks",
                status = 200,
                outcome = "success",
                requestId = "req-first"
            ),
            1724241600000L
        )
        ringBuffer.network.add(
            DiagnosticNetworkExchange(
                startedAt = "2026-08-21T12:00:01Z",
                durationMillis = 150L,
                method = "POST",
                path = "/tasks/done",
                status = 200,
                outcome = "success",
                requestId = "req-second"
            ),
            1724241600000L
        )

        coordinator.onFormOpen().join()
        coordinator.onSubmitSuccess(null)?.join()

        val backendRequest = backendSender.requests.single()
        assertTrue(backendRequest.requestIds.contains("req-first"))
        assertTrue(backendRequest.requestIds.contains("req-second"))
    }

    @Test
    fun staleSessionRecoveryReplacesPreviousSessionCleanly() = runBlocking {
        coordinator.onFormOpen().join()
        val firstSessionId = scopeModifier.currentReportSessionId

        // Call onFormOpen again without closing the previous one
        coordinator.onFormOpen().join()
        val secondSessionId = scopeModifier.currentReportSessionId

        assertTrue(firstSessionId != secondSessionId)

        coordinator.onSubmitSuccess(null)?.join()

        assertEquals(1, observability.diagnosticEvents.size)
        assertEquals(secondSessionId, observability.diagnosticEvents.single().reportSessionId)
        assertEquals(ManualReportState.IDLE, coordinator.state)
        assertNull(scopeModifier.currentReportSessionId)
    }

    @Test
    fun idempotentCallbacksWhenIdleOrConsecutive() = runBlocking {
        // Calling close/submit when already idle does nothing
        coordinator.onFormClose()?.join()
        coordinator.onSubmitSuccess(null)?.join()
        coordinator.onSubmitError(RuntimeException("error"))?.join()

        assertEquals(0, observability.diagnosticEvents.size)
        assertEquals(0, backendSender.requests.size)

        // Calling submitSuccess followed by formClose emits only 1 event
        coordinator.onFormOpen().join()
        coordinator.onSubmitSuccess(null)?.join()
        coordinator.onFormClose()?.join()

        assertEquals(1, observability.diagnosticEvents.size)
        assertTrue(observability.diagnosticEvents.single().submitted)
    }
}
