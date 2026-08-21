package app.buzer.android.observability

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.PixelCopy
import app.buzer.android.BuildConfig
import app.buzer.shared.diagnostics.DiagnosticLogRecord
import app.buzer.shared.diagnostics.DiagnosticNetworkExchange
import app.buzer.shared.diagnostics.DiagnosticRedactor
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Serializable
data class AndroidAppDiagnostics(
    val versionName: String,
    val versionCode: Long,
    val gitSha: String,
    val environment: String,
    val processUptimeMillis: Long
)

@Serializable
data class AndroidDisplayMetrics(
    val density: Float,
    val widthPixels: Int,
    val heightPixels: Int,
    val densityDpi: Int
)

@Serializable
data class AndroidDeviceDiagnostics(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val displayMetrics: AndroidDisplayMetrics? = null,
    val locale: String,
    val timezone: String
)

@Serializable
data class DiagnosticSectionStatus(
    val status: String, // "complete", "partial", "unavailable", "failed"
    val reasonCode: String? = null // "ok", "timeout", "empty", "headless", "error", "over_budget", etc.
) {
    companion object {
        fun complete(reasonCode: String? = "ok") = DiagnosticSectionStatus("complete", reasonCode)
        fun partial(reasonCode: String? = null) = DiagnosticSectionStatus("partial", reasonCode)
        fun unavailable(reasonCode: String? = null) = DiagnosticSectionStatus("unavailable", reasonCode)
        fun failed(reasonCode: String? = null) = DiagnosticSectionStatus("failed", reasonCode)
    }
}

@Serializable
data class DiagnosticTruncationRecord(
    val section: String,
    val originalBytes: Int,
    val truncatedBytes: Int,
    val reason: String
)

@Serializable
data class AndroidDiagnosticCapsule(
    val schemaVersion: Int = 1,
    val reportSessionId: String,
    val androidDiagnosticEventId: String,
    val capturedAt: String,
    val app: AndroidAppDiagnostics,
    val device: AndroidDeviceDiagnostics,
    val context: DiagnosticContextSnapshot,
    val sectionStatuses: Map<String, DiagnosticSectionStatus> = emptyMap(),
    val truncations: List<DiagnosticTruncationRecord> = emptyList()
)

class AndroidDiagnosticCollector(
    private val redactor: DiagnosticRedactor = DiagnosticRedactor()
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun collectAppDiagnostics(): AndroidAppDiagnostics {
        val uptime = runCatching {
            SystemClock.elapsedRealtime()
        }.getOrElse {
            runCatching {
                java.lang.management.ManagementFactory.getRuntimeMXBean().uptime
            }.getOrDefault(0L)
        }

        return AndroidAppDiagnostics(
            versionName = runCatching { BuildConfig.VERSION_NAME }.getOrNull() ?: "0.1.0-dev",
            versionCode = runCatching { BuildConfig.VERSION_CODE.toLong() }.getOrNull() ?: 1L,
            gitSha = runCatching { BuildConfig.BUZER_GIT_SHA }.getOrNull() ?: "local",
            environment = runCatching { BuildConfig.SENTRY_ENVIRONMENT }.getOrNull() ?: "development-device",
            processUptimeMillis = uptime
        )
    }

    fun collectDeviceDiagnostics(context: Context? = null): AndroidDeviceDiagnostics {
        val displayMetrics = context?.resources?.displayMetrics?.let { dm ->
            AndroidDisplayMetrics(
                density = dm.density,
                widthPixels = dm.widthPixels,
                heightPixels = dm.heightPixels,
                densityDpi = dm.densityDpi
            )
        }

        return AndroidDeviceDiagnostics(
            manufacturer = runCatching { Build.MANUFACTURER }.getOrNull() ?: "Unknown",
            model = runCatching { Build.MODEL }.getOrNull() ?: "Unknown",
            androidVersion = runCatching { Build.VERSION.RELEASE }.getOrNull() ?: "Unknown",
            sdkInt = runCatching { Build.VERSION.SDK_INT }.getOrNull() ?: 0,
            displayMetrics = displayMetrics,
            locale = runCatching { Locale.getDefault().toLanguageTag() }.getOrNull() ?: "und",
            timezone = runCatching { TimeZone.getDefault().id }.getOrNull() ?: "UTC"
        )
    }

    fun buildCapsule(
        reportSessionId: String,
        androidDiagnosticEventId: String,
        capturedAt: String,
        contextSnapshot: DiagnosticContextSnapshot = DiagnosticContextStore.snapshot(),
        sectionStatuses: Map<String, DiagnosticSectionStatus> = emptyMap(),
        truncations: List<DiagnosticTruncationRecord> = emptyList(),
        appDiagnostics: AndroidAppDiagnostics = collectAppDiagnostics(),
        deviceDiagnostics: AndroidDeviceDiagnostics = collectDeviceDiagnostics()
    ): AndroidDiagnosticCapsule {
        return AndroidDiagnosticCapsule(
            schemaVersion = 1,
            reportSessionId = reportSessionId,
            androidDiagnosticEventId = androidDiagnosticEventId,
            capturedAt = capturedAt,
            app = appDiagnostics,
            device = deviceDiagnostics,
            context = contextSnapshot,
            sectionStatuses = sectionStatuses,
            truncations = truncations
        )
    }

    fun buildCapsuleJson(capsule: AndroidDiagnosticCapsule): String {
        val element = json.encodeToJsonElement(AndroidDiagnosticCapsule.serializer(), capsule)
        val redacted = redactor.redactJson(element)
        return json.encodeToString(JsonElement.serializer(), redacted)
    }

    fun buildAppLogsText(logs: List<DiagnosticLogRecord>): String {
        if (logs.isEmpty()) return ""
        val sb = StringBuilder()
        for (record in logs) {
            sb.append("[").append(record.capturedAt).append("] ")
            sb.append("[").append(record.severity.uppercase()).append("] ")
            sb.append("[").append(record.tag).append("] ")
            sb.append(record.message)
            if (!record.throwableType.isNullOrBlank() || !record.throwableMessage.isNullOrBlank()) {
                sb.append(" (").append(record.throwableType ?: "Throwable")
                if (!record.throwableMessage.isNullOrBlank()) {
                    sb.append(": ").append(record.throwableMessage)
                }
                sb.append(")")
            }
            sb.append("\n")
        }
        return redactor.redactString(sb.toString())
    }

    fun buildNetworkHistoryJson(exchanges: List<DiagnosticNetworkExchange>): String {
        val element = json.encodeToJsonElement(
            ListSerializer(DiagnosticNetworkExchange.serializer()),
            exchanges
        )
        val redacted = redactor.redactJson(element)
        return json.encodeToString(JsonElement.serializer(), redacted)
    }

    @Suppress("DEPRECATION")
    fun buildThreadDump(stackTraces: Map<Thread, Array<StackTraceElement>> = Thread.getAllStackTraces()): String {
        val sortedThreads = stackTraces.keys.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val sb = StringBuilder()
        for (thread in sortedThreads) {
            val stack = stackTraces[thread] ?: emptyArray()
            sb.append("\"").append(thread.name).append("\" ")
            sb.append("(id=").append(thread.id)
            sb.append(", state=").append(thread.state)
            sb.append(", isDaemon=").append(thread.isDaemon)
            sb.append(", priority=").append(thread.priority).append(")\n")
            for (ste in stack) {
                sb.append("\tat ").append(ste.toString()).append("\n")
            }
            sb.append("\n")
        }
        val rawText = redactor.redactString(sb.toString())
        return Utf8Truncator.truncateUtf8(rawText, 512 * 1024)
    }

    fun captureScreenshot(activity: Activity?): ByteArray? {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return null
        return runCatching {
            val window = activity.window ?: return null
            val decorView = window.peekDecorView() ?: return null
            if (decorView.width <= 0 || decorView.height <= 0) return null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
                val latch = CountDownLatch(1)
                var copyResult = PixelCopy.ERROR_UNKNOWN
                val handlerThread = HandlerThread("PixelCopyScreenshot")
                handlerThread.start()
                val success = try {
                    val handler = Handler(handlerThread.looper)
                    PixelCopy.request(window, bitmap, { result ->
                        copyResult = result
                        latch.countDown()
                    }, handler)

                    latch.await(1000, TimeUnit.MILLISECONDS)
                } finally {
                    handlerThread.quitSafely()
                }

                if (success && copyResult == PixelCopy.SUCCESS) {
                    val outputStream = ByteArrayOutputStream()
                    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                    bitmap.compress(format, 80, outputStream)
                    val bytes = outputStream.toByteArray()
                    if (bytes.size <= 2 * 1024 * 1024) {
                        bytes
                    } else {
                        val reduced = ByteArrayOutputStream()
                        bitmap.compress(format, 50, reduced)
                        reduced.toByteArray().takeIf { it.size <= 2 * 1024 * 1024 }
                    }
                } else {
                    null
                }
            } else {
                null
            }
        }.getOrNull()
    }
}
