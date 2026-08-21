package app.buzer.android.observability

import app.buzer.shared.diagnostics.DiagnosticLogRecord
import app.buzer.shared.diagnostics.DiagnosticNetworkExchange
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidAttachmentCollectorTest {

    private lateinit var contextStore: DiagnosticContextStore
    private lateinit var ringBuffer: DiagnosticRingBuffer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        contextStore = DiagnosticContextStore()
        ringBuffer = DiagnosticRingBuffer()
    }

    @After
    fun tearDown() {
        contextStore.reset()
        ringBuffer.reset()
    }

    @Test
    fun collectsAllStandardAttachmentsWhenAvailable() = runBlocking {
        contextStore.updateUi { it.copy(screen = "TestScreen") }
        ringBuffer.logs.add(
            DiagnosticLogRecord(
                capturedAt = "2026-08-21T12:00:00Z",
                severity = "info",
                tag = "TestTag",
                message = "Test log record"
            )
        )
        ringBuffer.network.add(
            DiagnosticNetworkExchange(
                startedAt = "2026-08-21T12:00:00Z",
                durationMillis = 20L,
                method = "GET",
                path = "/api/test",
                status = 200,
                outcome = "success",
                requestId = "req-1"
            )
        )

        val fakeScreenshotBytes = "fake-png-screenshot-bytes".toByteArray(Charsets.UTF_8)
        val collector = AndroidAttachmentCollector(
            contextStore = contextStore,
            ringBuffer = ringBuffer,
            screenshotProvider = { fakeScreenshotBytes },
            threadDumpProvider = { "\"TestThread\" (id=1)\n\tat Test.run(Test.kt:1)\n" }
        )

        val attachments = collector.collectAttachments(
            reportSessionId = "rep-session-123",
            eventId = "evt-456",
            capturedAt = "2026-08-21T12:00:00Z"
        )

        val pathnames = attachments.map { it.pathname }
        assertTrue(pathnames.contains("android-diagnostics-v1.json"))
        assertTrue(pathnames.contains("android-app-log.txt"))
        assertTrue(pathnames.contains("android-network-history.json"))
        assertTrue(pathnames.contains("android-threads.txt"))
        assertTrue(pathnames.contains("android-screenshot.webp"))

        val capsuleAttachment = attachments.first { it.pathname == "android-diagnostics-v1.json" }
        val parsedCapsule = json.decodeFromString<AndroidDiagnosticCapsule>(capsuleAttachment.bytes.decodeToString())

        assertEquals(1, parsedCapsule.schemaVersion)
        assertEquals("rep-session-123", parsedCapsule.reportSessionId)
        assertEquals("evt-456", parsedCapsule.androidDiagnosticEventId)
        assertEquals("TestScreen", parsedCapsule.context.ui.screen)
        assertEquals("complete", parsedCapsule.sectionStatuses["threads"]?.status)
        assertEquals("complete", parsedCapsule.sectionStatuses["screenshot"]?.status)
    }

    @Test
    fun collectorTimeoutIsolationPreventsHanging() = runBlocking {
        val collector = AndroidAttachmentCollector(
            contextStore = contextStore,
            ringBuffer = ringBuffer,
            screenshotProvider = {
                Thread.sleep(300)
                "delayed-screenshot".toByteArray(Charsets.UTF_8)
            },
            threadDumpProvider = { "\"Thread\" (id=1)\n" },
            timeoutPerCollectorMillis = 50L
        )

        val attachments = collector.collectAttachments(
            reportSessionId = "rep-timeout",
            eventId = "evt-timeout"
        )

        val pathnames = attachments.map { it.pathname }
        assertTrue(pathnames.contains("android-diagnostics-v1.json"))
        // Screenshot should be absent due to timeout
        assertFalse(pathnames.contains("android-screenshot.webp"))

        val capsuleAttachment = attachments.first { it.pathname == "android-diagnostics-v1.json" }
        val parsedCapsule = json.decodeFromString<AndroidDiagnosticCapsule>(capsuleAttachment.bytes.decodeToString())
        assertEquals("unavailable", parsedCapsule.sectionStatuses["screenshot"]?.status)
        assertEquals("timeout", parsedCapsule.sectionStatuses["screenshot"]?.reasonCode)
    }

    @Test
    fun collectorFailureIsolationHandlesThrowingCollectors() = runBlocking {
        val collector = AndroidAttachmentCollector(
            contextStore = contextStore,
            ringBuffer = ringBuffer,
            screenshotProvider = { throw RuntimeException("PixelCopy native crash") },
            threadDumpProvider = { throw IllegalStateException("Thread dump failed") }
        )

        val attachments = collector.collectAttachments(
            reportSessionId = "rep-err",
            eventId = "evt-err"
        )

        val pathnames = attachments.map { it.pathname }
        assertTrue(pathnames.contains("android-diagnostics-v1.json"))
        assertTrue(pathnames.contains("android-app-log.txt"))
        assertTrue(pathnames.contains("android-network-history.json"))
        assertFalse(pathnames.contains("android-threads.txt"))
        assertFalse(pathnames.contains("android-screenshot.webp"))

        val capsuleAttachment = attachments.first { it.pathname == "android-diagnostics-v1.json" }
        val parsedCapsule = json.decodeFromString<AndroidDiagnosticCapsule>(capsuleAttachment.bytes.decodeToString())
        assertEquals("failed", parsedCapsule.sectionStatuses["threads"]?.status)
        assertEquals("failed", parsedCapsule.sectionStatuses["screenshot"]?.status)
    }

    @Test
    fun truncatesNetworkBodiesExceeding64KiB() = runBlocking {
        val hugeBody = "X".repeat(100 * 1024) // 100 KiB
        ringBuffer.network.add(
            DiagnosticNetworkExchange(
                startedAt = "2026-08-21T12:00:00Z",
                durationMillis = 50L,
                method = "POST",
                path = "/api/big",
                status = 200,
                outcome = "success",
                requestBody = hugeBody,
                responseBody = hugeBody,
                requestId = "req-huge"
            )
        )

        val collector = AndroidAttachmentCollector(
            contextStore = contextStore,
            ringBuffer = ringBuffer,
            screenshotProvider = null,
            threadDumpProvider = null
        )

        val attachments = collector.collectAttachments(
            reportSessionId = "rep-big",
            eventId = "evt-big"
        )

        val netAttachment = attachments.first { it.pathname == "android-network-history.json" }
        val parsed = json.decodeFromString<List<DiagnosticNetworkExchange>>(netAttachment.bytes.decodeToString())

        assertEquals(1, parsed.size)
        val ex = parsed.single()
        assertTrue(ex.requestTruncated)
        assertTrue(ex.responseTruncated)
        assertEquals(64 * 1024, ex.requestBody?.toByteArray(Charsets.UTF_8)?.size)
        assertEquals(64 * 1024, ex.responseBody?.toByteArray(Charsets.UTF_8)?.size)
    }

    @Test
    fun enforcesBudgetWithDeterministicTruncationPriority() = runBlocking {
        // Fill logs with info records and error records
        for (i in 1..20) {
            ringBuffer.logs.add(
                DiagnosticLogRecord(
                    capturedAt = "2026-08-21T12:00:00Z",
                    severity = "info",
                    tag = "Tag",
                    message = "Info log message #$i: " + "I".repeat(500)
                )
            )
        }
        ringBuffer.logs.add(
            DiagnosticLogRecord(
                capturedAt = "2026-08-21T12:01:00Z",
                severity = "error",
                tag = "Tag",
                message = "Critical error log message: " + "E".repeat(500)
            )
        )

        // Set low budget (e.g. 5 KB = 5120 bytes) to force truncation of info/debug logs
        val smallBudget = 5120
        val fakeScreenshot = "S".repeat(2000).toByteArray(Charsets.UTF_8)

        val collector = AndroidAttachmentCollector(
            contextStore = contextStore,
            ringBuffer = ringBuffer,
            screenshotProvider = { fakeScreenshot },
            threadDumpProvider = { "Thread dump\n" },
            maxTotalBytes = smallBudget
        )

        val attachments = collector.collectAttachments(
            reportSessionId = "rep-budget",
            eventId = "evt-budget"
        )

        val capsuleAttachment = attachments.first { it.pathname == "android-diagnostics-v1.json" }
        val parsedCapsule = json.decodeFromString<AndroidDiagnosticCapsule>(capsuleAttachment.bytes.decodeToString())

        // Check that truncations were recorded
        assertTrue(parsedCapsule.truncations.isNotEmpty())

        val logAttachment = attachments.first { it.pathname == "android-app-log.txt" }
        val logContent = logAttachment.bytes.decodeToString()
        // Error log should be kept
        assertTrue(logContent.contains("Critical error log message"))
        // Info log should be dropped under budget pressure
        assertFalse(logContent.contains("Info log message #1"))
    }

    @Test
    fun screenshotCollectorDifferentiatesHeadlessNullFromTimeout() = runBlocking {
        val collector = AndroidAttachmentCollector(
            contextStore = contextStore,
            ringBuffer = ringBuffer,
            screenshotProvider = { null },
            threadDumpProvider = { "\"Thread\" (id=1)\n" }
        )

        val attachments = collector.collectAttachments(
            reportSessionId = "rep-headless",
            eventId = "evt-headless"
        )

        val capsuleAttachment = attachments.first { it.pathname == "android-diagnostics-v1.json" }
        val parsedCapsule = json.decodeFromString<AndroidDiagnosticCapsule>(capsuleAttachment.bytes.decodeToString())
        assertEquals("unavailable", parsedCapsule.sectionStatuses["screenshot"]?.status)
        assertEquals("headless", parsedCapsule.sectionStatuses["screenshot"]?.reasonCode)
    }

    @Test
    fun screenshotCollectorDifferentiatesHeadlessEmptyByteArrayFromTimeout() = runBlocking {
        val collector = AndroidAttachmentCollector(
            contextStore = contextStore,
            ringBuffer = ringBuffer,
            screenshotProvider = { ByteArray(0) },
            threadDumpProvider = { "\"Thread\" (id=1)\n" }
        )

        val attachments = collector.collectAttachments(
            reportSessionId = "rep-headless-empty",
            eventId = "evt-headless-empty"
        )

        val capsuleAttachment = attachments.first { it.pathname == "android-diagnostics-v1.json" }
        val parsedCapsule = json.decodeFromString<AndroidDiagnosticCapsule>(capsuleAttachment.bytes.decodeToString())
        assertEquals("unavailable", parsedCapsule.sectionStatuses["screenshot"]?.status)
        assertEquals("headless", parsedCapsule.sectionStatuses["screenshot"]?.reasonCode)
    }
}
