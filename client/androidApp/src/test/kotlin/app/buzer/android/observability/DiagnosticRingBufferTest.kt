package app.buzer.android.observability

import app.buzer.shared.diagnostics.DiagnosticLogRecord
import app.buzer.shared.diagnostics.DiagnosticNetworkExchange
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticRingBufferTest {

    private lateinit var ringBuffer: DiagnosticRingBuffer

    @Before
    fun setup() {
        ringBuffer = DiagnosticRingBuffer()
    }

    @After
    fun tearDown() {
        ringBuffer.reset()
        DiagnosticRingBuffer.reset()
    }

    @Test
    fun logBufferEnforces1000RecordCapacityEviction() {
        val logBuffer = DiagnosticLogRingBuffer(maxRecords = 1000)
        val now = 1_000_000L

        for (i in 1..1050) {
            logBuffer.add(
                DiagnosticLogRecord(
                    capturedAt = "2026-08-21T12:00:00Z",
                    severity = "info",
                    tag = "Tag",
                    message = "Message #$i"
                ),
                timestampMillis = now + i
            )
        }

        val snapshot = logBuffer.snapshot(nowMillis = now + 2000)
        assertEquals(1000, snapshot.size)
        // Newest-first: first element is Message #1050, last is Message #51
        assertEquals("Message #1050", snapshot.first().message)
        assertEquals("Message #51", snapshot.last().message)
    }

    @Test
    fun logBufferEnforces15MinuteAgeEviction() {
        val logBuffer = DiagnosticLogRingBuffer(maxRecords = 1000, maxAgeMillis = 15 * 60 * 1000L)
        val now = 20 * 60 * 1000L // 20 minutes

        // Added 18 minutes ago (too old)
        logBuffer.add(
            DiagnosticLogRecord(
                capturedAt = "2026-08-21T12:00:00Z",
                severity = "info",
                tag = "Tag",
                message = "Old message"
            ),
            timestampMillis = now - (18 * 60 * 1000L)
        )

        // Added 5 minutes ago (valid)
        logBuffer.add(
            DiagnosticLogRecord(
                capturedAt = "2026-08-21T12:15:00Z",
                severity = "info",
                tag = "Tag",
                message = "Recent message"
            ),
            timestampMillis = now - (5 * 60 * 1000L)
        )

        val snapshot = logBuffer.snapshot(nowMillis = now)
        assertEquals(1, snapshot.size)
        assertEquals("Recent message", snapshot.single().message)
    }

    @Test
    fun logBufferReturnsNewestFirstOrder() {
        val logBuffer = DiagnosticLogRingBuffer(maxRecords = 10)
        val now = 100_000L

        logBuffer.add(
            DiagnosticLogRecord(capturedAt = "T1", severity = "info", tag = "T", message = "First"),
            timestampMillis = now + 1
        )
        logBuffer.add(
            DiagnosticLogRecord(capturedAt = "T2", severity = "info", tag = "T", message = "Second"),
            timestampMillis = now + 2
        )
        logBuffer.add(
            DiagnosticLogRecord(capturedAt = "T3", severity = "info", tag = "T", message = "Third"),
            timestampMillis = now + 3
        )

        val snapshot = logBuffer.snapshot(nowMillis = now + 10)
        assertEquals(listOf("Third", "Second", "First"), snapshot.map { it.message })
    }

    @Test
    fun networkBufferEnforces100ExchangeCapacityAndAgeEviction() {
        val netBuffer = DiagnosticNetworkRingBuffer(maxExchanges = 100, maxAgeMillis = 15 * 60 * 1000L)
        val now = 30 * 60 * 1000L

        // Add 120 exchanges
        for (i in 1..120) {
            netBuffer.add(
                DiagnosticNetworkExchange(
                    startedAt = "2026-08-21T12:00:00Z",
                    durationMillis = 50L,
                    method = "GET",
                    path = "/api/test/$i",
                    status = 200,
                    outcome = "success",
                    requestId = "req-$i"
                ),
                timestampMillis = now + i
            )
        }

        val snapshot = netBuffer.snapshot(nowMillis = now + 200)
        assertEquals(100, snapshot.size)
        assertEquals("/api/test/120", snapshot.first().path)
        assertEquals("/api/test/21", snapshot.last().path)
    }

    @Test
    fun utf8ByteTruncationPreservesCodePointsWithoutSplitting() {
        // ASCII
        val ascii = "Hello, World!"
        assertEquals("Hello", Utf8Truncator.truncateUtf8(ascii, 5))
        assertEquals(ascii, Utf8Truncator.truncateUtf8(ascii, 100))

        // 2-byte characters (e.g. č = 2 bytes)
        val czech = "Příliš" // P(1) + ř(2) + í(2) + l(1) + i(1) + š(2) = 9 bytes
        // Truncate to 2 bytes: only 'P' (1 byte) can fit, because 'ř' is 2 bytes (1 + 2 = 3 > 2)
        assertEquals("P", Utf8Truncator.truncateUtf8(czech, 2))
        // Truncate to 3 bytes: 'Př' (1 + 2 = 3 bytes)
        assertEquals("Př", Utf8Truncator.truncateUtf8(czech, 3))

        // 3-byte characters (e.g. € = 3 bytes, 你 = 3 bytes)
        val chinese = "你好世界" // 4 chars * 3 bytes = 12 bytes
        assertEquals("你", Utf8Truncator.truncateUtf8(chinese, 3))
        assertEquals("你", Utf8Truncator.truncateUtf8(chinese, 4))
        assertEquals("你", Utf8Truncator.truncateUtf8(chinese, 5))
        assertEquals("你好", Utf8Truncator.truncateUtf8(chinese, 6))

        // 4-byte characters / emojis (e.g. 🚀 = 4 bytes)
        val emojis = "🚀🚨🔥🎉" // 4 emojis * 4 bytes = 16 bytes
        assertEquals("", Utf8Truncator.truncateUtf8(emojis, 3))
        assertEquals("🚀", Utf8Truncator.truncateUtf8(emojis, 4))
        assertEquals("🚀", Utf8Truncator.truncateUtf8(emojis, 7))
        assertEquals("🚀🚨", Utf8Truncator.truncateUtf8(emojis, 8))
        assertEquals("🚀🚨🔥", Utf8Truncator.truncateUtf8(emojis, 12))
        assertEquals("🚀🚨🔥🎉", Utf8Truncator.truncateUtf8(emojis, 16))

        // Byte array truncation
        val emojiBytes = emojis.toByteArray(Charsets.UTF_8)
        val truncatedBytes = Utf8Truncator.truncateUtf8Bytes(emojiBytes, 7)
        assertEquals("🚀", truncatedBytes.decodeToString())
    }

    @Test
    fun concurrentRingBufferAccessIsThreadSafe() {
        val threadCount = 6
        val itemsPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until itemsPerThread) {
                        ringBuffer.logs.add(
                            DiagnosticLogRecord(
                                capturedAt = "2026-08-21T12:00:00Z",
                                severity = "info",
                                tag = "Tag-$t",
                                message = "Log-$t-$i"
                            )
                        )
                        ringBuffer.network.add(
                            DiagnosticNetworkExchange(
                                startedAt = "2026-08-21T12:00:00Z",
                                durationMillis = 10L,
                                method = "POST",
                                path = "/test",
                                status = 200,
                                outcome = "success",
                                requestId = "req-$t-$i"
                            )
                        )
                        ringBuffer.logs.snapshot()
                        ringBuffer.network.snapshot()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue(completed)
    }
}
