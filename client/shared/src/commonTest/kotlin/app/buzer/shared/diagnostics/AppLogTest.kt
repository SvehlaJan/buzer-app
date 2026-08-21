package app.buzer.shared.diagnostics

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLogTest {

    @AfterTest
    fun tearDown() {
        AppLog.resetSinkForTests()
    }

    @Test
    fun emitsFormattedRecordsToSink() {
        val records = mutableListOf<Pair<DiagnosticLogRecord, Throwable?>>()
        AppLog.installSink { record, throwable ->
            records.add(record to throwable)
        }

        AppLog.debug("debug event")
        AppLog.info("info event")
        val warningException = IllegalStateException("warning detail")
        AppLog.warn("warn event", warningException)
        val errorException = IllegalArgumentException("error detail")
        AppLog.error("error event", errorException)

        assertEquals(4, records.size)

        val (debugRecord, debugThrowable) = records[0]
        assertEquals("debug", debugRecord.severity)
        assertEquals("Buzer", debugRecord.tag)
        assertEquals("debug event", debugRecord.message)
        assertNull(debugRecord.throwableType)
        assertNull(debugRecord.throwableMessage)
        assertNull(debugThrowable)
        assertTrue(debugRecord.capturedAt.isNotEmpty())

        val (infoRecord, infoThrowable) = records[1]
        assertEquals("info", infoRecord.severity)
        assertEquals("Buzer", infoRecord.tag)
        assertEquals("info event", infoRecord.message)
        assertNull(infoRecord.throwableType)
        assertNull(infoRecord.throwableMessage)
        assertNull(infoThrowable)

        val (warnRecord, warnThrowable) = records[2]
        assertEquals("warning", warnRecord.severity)
        assertEquals("Buzer", warnRecord.tag)
        assertEquals("warn event", warnRecord.message)
        assertEquals("IllegalStateException", warnRecord.throwableType)
        assertEquals("warning detail", warnRecord.throwableMessage)
        assertEquals(warningException, warnThrowable)

        val (errorRecord, errorThrowable) = records[3]
        assertEquals("error", errorRecord.severity)
        assertEquals("Buzer", errorRecord.tag)
        assertEquals("error event", errorRecord.message)
        assertEquals("IllegalArgumentException", errorRecord.throwableType)
        assertEquals("error detail", errorRecord.throwableMessage)
        assertEquals(errorException, errorThrowable)
    }

    @Test
    fun sinkFailureNeverBreaksApplicationLogging() {
        AppLog.installSink { _, _ -> throw IllegalStateException("sink down") }
        // Must not throw exception
        AppLog.error("enforcement still runs", IllegalArgumentException("failure"))
        AppLog.resetSinkForTests()
    }
}
