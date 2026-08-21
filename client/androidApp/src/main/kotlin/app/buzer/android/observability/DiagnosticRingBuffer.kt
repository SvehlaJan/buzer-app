package app.buzer.android.observability

import app.buzer.shared.diagnostics.DiagnosticLogRecord
import app.buzer.shared.diagnostics.DiagnosticNetworkExchange
import java.util.ArrayDeque

object Utf8Truncator {
    fun truncateUtf8(input: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val bytes = input.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return input

        var byteCount = 0
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val codePoint = input.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val cpBytes = when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
            if (byteCount + cpBytes > maxBytes) break
            byteCount += cpBytes
            sb.append(input, i, i + charCount)
            i += charCount
        }
        return sb.toString()
    }

    fun truncateUtf8Bytes(input: ByteArray, maxBytes: Int): ByteArray {
        if (input.size <= maxBytes) return input
        if (maxBytes <= 0) return ByteArray(0)
        val text = input.decodeToString()
        return truncateUtf8(text, maxBytes).toByteArray(Charsets.UTF_8)
    }
}

class DiagnosticLogRingBuffer(
    val maxRecords: Int = 1000,
    val maxAgeMillis: Long = 15 * 60 * 1000L
) {
    private data class TimestampedRecord(
        val record: DiagnosticLogRecord,
        val timestampMillis: Long
    )

    private val lock = Any()
    private val buffer = ArrayDeque<TimestampedRecord>()

    fun add(record: DiagnosticLogRecord, timestampMillis: Long = System.currentTimeMillis()) {
        runCatching {
            synchronized(lock) {
                while (buffer.size >= maxRecords) {
                    buffer.removeFirst()
                }
                buffer.addLast(TimestampedRecord(record, timestampMillis))
            }
        }
    }

    fun snapshot(nowMillis: Long = System.currentTimeMillis()): List<DiagnosticLogRecord> {
        val cutoff = nowMillis - maxAgeMillis
        synchronized(lock) {
            val result = mutableListOf<DiagnosticLogRecord>()
            val iterator = buffer.descendingIterator()
            while (iterator.hasNext() && result.size < maxRecords) {
                val item = iterator.next()
                if (item.timestampMillis >= cutoff) {
                    result.add(item.record)
                }
            }
            return result
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return buffer.size
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }
}

class DiagnosticNetworkRingBuffer(
    val maxExchanges: Int = 100,
    val maxAgeMillis: Long = 15 * 60 * 1000L
) {
    private data class TimestampedExchange(
        val exchange: DiagnosticNetworkExchange,
        val timestampMillis: Long
    )

    private val lock = Any()
    private val buffer = ArrayDeque<TimestampedExchange>()

    fun add(exchange: DiagnosticNetworkExchange, timestampMillis: Long = System.currentTimeMillis()) {
        runCatching {
            synchronized(lock) {
                while (buffer.size >= maxExchanges) {
                    buffer.removeFirst()
                }
                buffer.addLast(TimestampedExchange(exchange, timestampMillis))
            }
        }
    }

    fun snapshot(nowMillis: Long = System.currentTimeMillis()): List<DiagnosticNetworkExchange> {
        val cutoff = nowMillis - maxAgeMillis
        synchronized(lock) {
            val result = mutableListOf<DiagnosticNetworkExchange>()
            val iterator = buffer.descendingIterator()
            while (iterator.hasNext() && result.size < maxExchanges) {
                val item = iterator.next()
                if (item.timestampMillis >= cutoff) {
                    result.add(item.exchange)
                }
            }
            return result
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return buffer.size
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }
}

open class DiagnosticRingBuffer(
    val logs: DiagnosticLogRingBuffer = DiagnosticLogRingBuffer(),
    val network: DiagnosticNetworkRingBuffer = DiagnosticNetworkRingBuffer()
) {
    fun reset() {
        logs.clear()
        network.clear()
    }

    companion object : DiagnosticRingBuffer() {
        val instance: DiagnosticRingBuffer get() = this
    }
}
