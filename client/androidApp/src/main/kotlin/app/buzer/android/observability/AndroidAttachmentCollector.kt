package app.buzer.android.observability

import app.buzer.shared.diagnostics.DiagnosticLogRecord
import app.buzer.shared.diagnostics.DiagnosticNetworkExchange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AndroidAttachmentCollector(
    private val contextStore: DiagnosticContextStore = DiagnosticContextStore,
    private val ringBuffer: DiagnosticRingBuffer = DiagnosticRingBuffer,
    private val diagnosticCollector: AndroidDiagnosticCollector = AndroidDiagnosticCollector(),
    private val screenshotProvider: (() -> ByteArray?)? = null,
    private val threadDumpProvider: (() -> String)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timeoutPerCollectorMillis: Long = 1000L,
    private val overallBudgetMillis: Long = 2000L,
    private val maxTotalBytes: Int = 8 * 1024 * 1024
) {

    suspend fun collectAttachments(
        reportSessionId: String,
        eventId: String,
        capturedAt: String = java.time.Instant.now().toString()
    ): List<DiagnosticAttachment> {
        return withContext(Dispatchers.Default) {
            val result = withTimeoutOrNull(overallBudgetMillis) {
                collectInternal(reportSessionId, eventId, capturedAt)
            }
            result ?: fallbackTimeoutAttachments(reportSessionId, eventId, capturedAt)
        }
    }

    fun collectAttachmentsSync(
        reportSessionId: String,
        eventId: String,
        capturedAt: String = java.time.Instant.now().toString()
    ): List<DiagnosticAttachment> {
        return runBlocking {
            collectAttachments(reportSessionId, eventId, capturedAt)
        }
    }

    private suspend fun collectInternal(
        reportSessionId: String,
        eventId: String,
        capturedAt: String
    ): List<DiagnosticAttachment> {
        val sectionStatuses = mutableMapOf<String, DiagnosticSectionStatus>()
        val truncations = mutableListOf<DiagnosticTruncationRecord>()

        // 1. Context snapshot
        val contextSnapshot = runCatching {
            contextStore.snapshot()
        }.getOrElse {
            sectionStatuses["context"] = DiagnosticSectionStatus.partial("snapshot_error")
            DiagnosticContextSnapshot()
        }
        if (!sectionStatuses.containsKey("context")) {
            sectionStatuses["context"] = DiagnosticSectionStatus.complete("ok")
        }

        // 2. Logs snapshot
        val rawLogs: List<DiagnosticLogRecord> = runCatching {
            ringBuffer.logs.snapshot(clock())
        }.getOrElse {
            sectionStatuses["logs"] = DiagnosticSectionStatus.failed("snapshot_error")
            emptyList()
        }
        if (!sectionStatuses.containsKey("logs")) {
            sectionStatuses["logs"] = if (rawLogs.isEmpty()) {
                DiagnosticSectionStatus.complete("empty")
            } else {
                DiagnosticSectionStatus.complete("ok")
            }
        }

        // 3. Network snapshot
        val rawNetwork: List<DiagnosticNetworkExchange> = runCatching {
            ringBuffer.network.snapshot(clock())
        }.getOrElse {
            sectionStatuses["network"] = DiagnosticSectionStatus.failed("snapshot_error")
            emptyList()
        }
        if (!sectionStatuses.containsKey("network")) {
            sectionStatuses["network"] = if (rawNetwork.isEmpty()) {
                DiagnosticSectionStatus.complete("empty")
            } else {
                DiagnosticSectionStatus.complete("ok")
            }
        }

        // 4. Thread dump collection with per-collector timeout
        var rawThreadDump: String? = null
        val threadResult = runCatching {
            withTimeoutOrNull(timeoutPerCollectorMillis) {
                kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                    if (threadDumpProvider != null) {
                        threadDumpProvider.invoke()
                    } else {
                        diagnosticCollector.buildThreadDump()
                    }
                }
            }
        }
        if (threadResult.isFailure) {
            sectionStatuses["threads"] = DiagnosticSectionStatus.failed("error")
        } else {
            rawThreadDump = threadResult.getOrNull()
            if (rawThreadDump == null) {
                sectionStatuses["threads"] = DiagnosticSectionStatus.unavailable("timeout")
            } else if (rawThreadDump.isBlank()) {
                sectionStatuses["threads"] = DiagnosticSectionStatus.unavailable("empty")
            } else {
                sectionStatuses["threads"] = DiagnosticSectionStatus.complete("ok")
            }
        }

        // 5. Screenshot collection with per-collector timeout
        var rawScreenshot: ByteArray? = null
        val screenshotResult = runCatching {
            withTimeoutOrNull(timeoutPerCollectorMillis) {
                kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                    if (screenshotProvider != null) {
                        screenshotProvider.invoke()
                    } else {
                        val activity = CurrentActivityTracker.currentActivity
                        diagnosticCollector.captureScreenshot(activity)
                    }
                }
            }
        }
        if (screenshotResult.isFailure) {
            sectionStatuses["screenshot"] = DiagnosticSectionStatus.failed("error")
        } else {
            rawScreenshot = screenshotResult.getOrNull()
            if (rawScreenshot == null) {
                sectionStatuses["screenshot"] = DiagnosticSectionStatus.unavailable("timeout")
            } else if (rawScreenshot.isEmpty()) {
                sectionStatuses["screenshot"] = DiagnosticSectionStatus.unavailable("headless")
            } else {
                sectionStatuses["screenshot"] = DiagnosticSectionStatus.complete("ok")
            }
        }

        // Cap individual artifact limits
        // 5a. Thread dump capped at 512 KiB
        var threadText = rawThreadDump
        if (threadText != null) {
            val originalBytes = threadText.toByteArray(Charsets.UTF_8).size
            if (originalBytes > 512 * 1024) {
                threadText = Utf8Truncator.truncateUtf8(threadText, 512 * 1024)
                val truncatedBytes = threadText.toByteArray(Charsets.UTF_8).size
                truncations.add(
                    DiagnosticTruncationRecord(
                        section = "threads",
                        originalBytes = originalBytes,
                        truncatedBytes = truncatedBytes,
                        reason = "capped_at_512_kib"
                    )
                )
                sectionStatuses["threads"] = DiagnosticSectionStatus.partial("capped_at_512_kib")
            }
        }

        // 5b. Screenshot capped at 2 MiB
        var screenshotBytes = rawScreenshot
        if (screenshotBytes != null && screenshotBytes.size > 2 * 1024 * 1024) {
            truncations.add(
                DiagnosticTruncationRecord(
                    section = "screenshot",
                    originalBytes = screenshotBytes.size,
                    truncatedBytes = 0,
                    reason = "over_2_mib_budget"
                )
            )
            sectionStatuses["screenshot"] = DiagnosticSectionStatus.unavailable("over_2_mib_budget")
            screenshotBytes = null
        }

        // 5c. Network request/response bodies capped at 64 KiB
        var networkExchanges = rawNetwork.map { ex ->
            var reqBody = ex.requestBody
            var reqTrunc = ex.requestTruncated
            var respBody = ex.responseBody
            var respTrunc = ex.responseTruncated
            if (reqBody != null && reqBody.toByteArray(Charsets.UTF_8).size > 64 * 1024) {
                reqBody = Utf8Truncator.truncateUtf8(reqBody, 64 * 1024)
                reqTrunc = true
            }
            if (respBody != null && respBody.toByteArray(Charsets.UTF_8).size > 64 * 1024) {
                respBody = Utf8Truncator.truncateUtf8(respBody, 64 * 1024)
                respTrunc = true
            }
            if (reqTrunc != ex.requestTruncated || respTrunc != ex.responseTruncated || reqBody != ex.requestBody || respBody != ex.responseBody) {
                ex.copy(
                    requestBody = reqBody,
                    responseBody = respBody,
                    requestTruncated = reqTrunc,
                    responseTruncated = respTrunc
                )
            } else {
                ex
            }
        }

        // Generate text/binary contents
        var activeLogs = rawLogs
        var logText = diagnosticCollector.buildAppLogsText(activeLogs)
        var logBytes = logText.toByteArray(Charsets.UTF_8)

        var netJson = diagnosticCollector.buildNetworkHistoryJson(networkExchanges)
        var netBytes = netJson.toByteArray(Charsets.UTF_8)

        var threadBytes = threadText?.toByteArray(Charsets.UTF_8)

        // Deterministic truncation if total size > maxTotalBytes (8 MiB)
        fun calculateTotalSize(): Int {
            return 2048 + logBytes.size + netBytes.size + (threadBytes?.size ?: 0) + (screenshotBytes?.size ?: 0)
        }

        if (calculateTotalSize() > maxTotalBytes) {
            // Priority 6: Drop info/debug history
            val errorWarnLogs = activeLogs.filter {
                it.severity.equals("error", ignoreCase = true) ||
                    it.severity.equals("warn", ignoreCase = true) ||
                    it.severity.equals("warning", ignoreCase = true)
            }
            val originalLogBytes = logBytes.size
            activeLogs = errorWarnLogs
            logText = diagnosticCollector.buildAppLogsText(activeLogs)
            logBytes = logText.toByteArray(Charsets.UTF_8)
            truncations.add(
                DiagnosticTruncationRecord(
                    section = "logs",
                    originalBytes = originalLogBytes,
                    truncatedBytes = logBytes.size,
                    reason = "dropped_info_debug"
                )
            )
            sectionStatuses["logs"] = DiagnosticSectionStatus.partial("dropped_info_debug")

            val failedSlowNet = networkExchanges.filter { ex ->
                val st = ex.status
                (st == null || st >= 400 || !ex.outcome.equals("success", ignoreCase = true)) ||
                    ex.durationMillis >= 1000L
            }
            val originalNetBytes = netBytes.size
            networkExchanges = failedSlowNet
            netJson = diagnosticCollector.buildNetworkHistoryJson(networkExchanges)
            netBytes = netJson.toByteArray(Charsets.UTF_8)
            truncations.add(
                DiagnosticTruncationRecord(
                    section = "network",
                    originalBytes = originalNetBytes,
                    truncatedBytes = netBytes.size,
                    reason = "dropped_normal_2xx"
                )
            )
            sectionStatuses["network"] = DiagnosticSectionStatus.partial("dropped_normal_2xx")
        }

        if (calculateTotalSize() > maxTotalBytes && screenshotBytes != null) {
            // Priority 5: Drop screenshot
            val originalScreenshotBytes = screenshotBytes.size
            screenshotBytes = null
            truncations.add(
                DiagnosticTruncationRecord(
                    section = "screenshot",
                    originalBytes = originalScreenshotBytes,
                    truncatedBytes = 0,
                    reason = "dropped_over_budget"
                )
            )
            sectionStatuses["screenshot"] = DiagnosticSectionStatus.unavailable("dropped_over_budget")
        }

        if (calculateTotalSize() > maxTotalBytes && netBytes.isNotEmpty()) {
            // Priority 4: Drop network exchanges
            val originalNetBytes = netBytes.size
            networkExchanges = emptyList()
            netJson = diagnosticCollector.buildNetworkHistoryJson(networkExchanges)
            netBytes = netJson.toByteArray(Charsets.UTF_8)
            truncations.add(
                DiagnosticTruncationRecord(
                    section = "network",
                    originalBytes = originalNetBytes,
                    truncatedBytes = netBytes.size,
                    reason = "dropped_over_budget"
                )
            )
            sectionStatuses["network"] = DiagnosticSectionStatus.unavailable("dropped_over_budget")
        }

        if (calculateTotalSize() > maxTotalBytes && threadBytes != null) {
            // Drop threads
            val originalThreadBytes = threadBytes.size
            threadBytes = null
            truncations.add(
                DiagnosticTruncationRecord(
                    section = "threads",
                    originalBytes = originalThreadBytes,
                    truncatedBytes = 0,
                    reason = "dropped_over_budget"
                )
            )
            sectionStatuses["threads"] = DiagnosticSectionStatus.unavailable("dropped_over_budget")
        }

        if (calculateTotalSize() > maxTotalBytes && logBytes.isNotEmpty()) {
            // Priority 3: Drop logs
            val originalLogBytes = logBytes.size
            activeLogs = emptyList()
            logText = ""
            logBytes = ByteArray(0)
            truncations.add(
                DiagnosticTruncationRecord(
                    section = "logs",
                    originalBytes = originalLogBytes,
                    truncatedBytes = 0,
                    reason = "dropped_over_budget"
                )
            )
            sectionStatuses["logs"] = DiagnosticSectionStatus.unavailable("dropped_over_budget")
        }

        // Build capsule JSON
        val capsule = diagnosticCollector.buildCapsule(
            reportSessionId = reportSessionId,
            androidDiagnosticEventId = eventId,
            capturedAt = capturedAt,
            contextSnapshot = contextSnapshot,
            sectionStatuses = sectionStatuses,
            truncations = truncations
        )
        val capsuleJson = diagnosticCollector.buildCapsuleJson(capsule)
        val capsuleBytes = capsuleJson.toByteArray(Charsets.UTF_8)

        // Build final attachments list
        val attachments = mutableListOf<DiagnosticAttachment>()
        attachments.add(
            DiagnosticAttachment(
                pathname = "android-diagnostics-v1.json",
                bytes = capsuleBytes,
                contentType = "application/json"
            )
        )
        attachments.add(
            DiagnosticAttachment(
                pathname = "android-app-log.txt",
                bytes = logBytes,
                contentType = "text/plain"
            )
        )
        attachments.add(
            DiagnosticAttachment(
                pathname = "android-network-history.json",
                bytes = netBytes,
                contentType = "application/json"
            )
        )
        if (threadBytes != null && threadBytes.isNotEmpty()) {
            attachments.add(
                DiagnosticAttachment(
                    pathname = "android-threads.txt",
                    bytes = threadBytes,
                    contentType = "text/plain"
                )
            )
        }
        if (screenshotBytes != null && screenshotBytes.isNotEmpty()) {
            attachments.add(
                DiagnosticAttachment(
                    pathname = "android-screenshot.webp",
                    bytes = screenshotBytes,
                    contentType = "image/webp"
                )
            )
        }

        return attachments
    }

    private fun fallbackTimeoutAttachments(
        reportSessionId: String,
        eventId: String,
        capturedAt: String
    ): List<DiagnosticAttachment> {
        val fallbackCapsule = diagnosticCollector.buildCapsule(
            reportSessionId = reportSessionId,
            androidDiagnosticEventId = eventId,
            capturedAt = capturedAt,
            contextSnapshot = contextStore.snapshot(),
            sectionStatuses = mapOf("all" to DiagnosticSectionStatus.partial("overall_timeout")),
            truncations = listOf(
                DiagnosticTruncationRecord(
                    section = "all",
                    originalBytes = 0,
                    truncatedBytes = 0,
                    reason = "overall_timeout"
                )
            )
        )
        val json = diagnosticCollector.buildCapsuleJson(fallbackCapsule)
        return listOf(
            DiagnosticAttachment(
                pathname = "android-diagnostics-v1.json",
                bytes = json.toByteArray(Charsets.UTF_8),
                contentType = "application/json"
            )
        )
    }
}
