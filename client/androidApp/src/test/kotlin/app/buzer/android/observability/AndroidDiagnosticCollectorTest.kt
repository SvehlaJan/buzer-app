package app.buzer.android.observability

import app.buzer.shared.diagnostics.DiagnosticCorrelation
import app.buzer.shared.diagnostics.DiagnosticLogRecord
import app.buzer.shared.diagnostics.DiagnosticNetworkExchange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDiagnosticCollectorTest {

    private val collector = AndroidDiagnosticCollector()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun serializesSchemaVersion1JsonCapsule() {
        val contextStore = DiagnosticContextStore()
        contextStore.updateUi { it.copy(screen = "Home", currentActivity = "MainActivity") }
        contextStore.updateCorrelation { it.copy(deviceId = "dev-test", reportSessionId = "rep-session-1") }

        val capsule = collector.buildCapsule(
            reportSessionId = "rep-session-1",
            androidDiagnosticEventId = "evt-diag-1",
            capturedAt = "2026-08-21T12:30:00Z",
            contextSnapshot = contextStore.snapshot(),
            sectionStatuses = mapOf(
                "logs" to DiagnosticSectionStatus.complete(),
                "network" to DiagnosticSectionStatus.complete(),
                "threads" to DiagnosticSectionStatus.complete(),
                "screenshot" to DiagnosticSectionStatus.unavailable("headless")
            ),
            truncations = listOf(
                DiagnosticTruncationRecord(
                    section = "logs",
                    originalBytes = 2000,
                    truncatedBytes = 1000,
                    reason = "over_budget"
                )
            ),
            appDiagnostics = AndroidAppDiagnostics(
                versionName = "1.2.3",
                versionCode = 123L,
                gitSha = "abcdef123456",
                environment = "production",
                processUptimeMillis = 60000L
            ),
            deviceDiagnostics = AndroidDeviceDiagnostics(
                manufacturer = "Google",
                model = "Pixel 8",
                androidVersion = "14",
                sdkInt = 34,
                displayMetrics = AndroidDisplayMetrics(
                    density = 2.625f,
                    widthPixels = 1080,
                    heightPixels = 2400,
                    densityDpi = 420
                ),
                locale = "en-US",
                timezone = "America/New_York"
            )
        )

        val jsonStr = collector.buildCapsuleJson(capsule)
        val parsedCapsule = json.decodeFromString<AndroidDiagnosticCapsule>(jsonStr)

        assertEquals(1, parsedCapsule.schemaVersion)
        assertEquals("rep-session-1", parsedCapsule.reportSessionId)
        assertEquals("evt-diag-1", parsedCapsule.androidDiagnosticEventId)
        assertEquals("2026-08-21T12:30:00Z", parsedCapsule.capturedAt)

        assertEquals("1.2.3", parsedCapsule.app.versionName)
        assertEquals(123L, parsedCapsule.app.versionCode)
        assertEquals("abcdef123456", parsedCapsule.app.gitSha)
        assertEquals("production", parsedCapsule.app.environment)

        assertEquals("Google", parsedCapsule.device.manufacturer)
        assertEquals("Pixel 8", parsedCapsule.device.model)
        assertEquals("14", parsedCapsule.device.androidVersion)
        assertEquals(34, parsedCapsule.device.sdkInt)
        assertEquals("en-US", parsedCapsule.device.locale)
        assertEquals("America/New_York", parsedCapsule.device.timezone)

        assertEquals("Home", parsedCapsule.context.ui.screen)
        assertEquals("dev-test", parsedCapsule.context.correlation.deviceId)

        assertEquals("complete", parsedCapsule.sectionStatuses["logs"]?.status)
        assertEquals("unavailable", parsedCapsule.sectionStatuses["screenshot"]?.status)
        assertEquals("headless", parsedCapsule.sectionStatuses["screenshot"]?.reasonCode)

        assertEquals(1, parsedCapsule.truncations.size)
        assertEquals("logs", parsedCapsule.truncations.single().section)
        assertEquals("over_budget", parsedCapsule.truncations.single().reason)
    }

    @Test
    fun formatsAppLogTextWithTimestampsSeverityTagAndThrowables() {
        val logs = listOf(
            DiagnosticLogRecord(
                capturedAt = "2026-08-21T12:00:00Z",
                severity = "info",
                tag = "SyncEngine",
                message = "Sync completed successfully"
            ),
            DiagnosticLogRecord(
                capturedAt = "2026-08-21T12:01:00Z",
                severity = "error",
                tag = "AlarmScheduler",
                message = "Failed to schedule exact alarm",
                throwableType = "SecurityException",
                throwableMessage = "Exact alarm permission denied"
            )
        )

        val logText = collector.buildAppLogsText(logs)

        assertTrue(logText.contains("[2026-08-21T12:00:00Z] [INFO] [SyncEngine] Sync completed successfully"))
        assertTrue(logText.contains("[2026-08-21T12:01:00Z] [ERROR] [AlarmScheduler] Failed to schedule exact alarm (SecurityException: Exact alarm permission denied)"))
    }

    @Test
    fun formatsNetworkHistoryJson() {
        val exchanges = listOf(
            DiagnosticNetworkExchange(
                startedAt = "2026-08-21T12:00:00Z",
                durationMillis = 150L,
                method = "POST",
                path = "/api/tasks/action",
                status = 200,
                outcome = "success",
                requestContentType = "application/json",
                responseContentType = "application/json",
                requestBody = "{\"action\":\"done\"}",
                responseBody = "{\"ok\":true}",
                requestId = "req-123",
                traceId = "trace-456",
                actionId = "act-789"
            )
        )

        val netJson = collector.buildNetworkHistoryJson(exchanges)
        val parsed = json.decodeFromString<List<DiagnosticNetworkExchange>>(netJson)

        assertEquals(1, parsed.size)
        assertEquals("POST", parsed.single().method)
        assertEquals("/api/tasks/action", parsed.single().path)
        assertEquals(200, parsed.single().status)
        assertEquals("req-123", parsed.single().requestId)
    }

    @Test
    fun buildsSortedThreadDumpCappedAt512KiB() {
        val thread1 = Thread("Worker-Thread-2")
        val thread2 = Thread("Alpha-Thread-1")
        val thread3 = Thread("Zeta-Thread-3")

        val stackElement = StackTraceElement("app.buzer.Main", "execute", "Main.kt", 42)
        val mockStackTraces = mapOf(
            thread1 to arrayOf(stackElement),
            thread2 to arrayOf(stackElement),
            thread3 to arrayOf(stackElement)
        )

        val threadDump = collector.buildThreadDump(mockStackTraces)

        // Threads must be sorted alphabetically by name
        val alphaIdx = threadDump.indexOf("Alpha-Thread-1")
        val workerIdx = threadDump.indexOf("Worker-Thread-2")
        val zetaIdx = threadDump.indexOf("Zeta-Thread-3")

        assertTrue(alphaIdx >= 0)
        assertTrue(workerIdx > alphaIdx)
        assertTrue(zetaIdx > workerIdx)

        // Format checks
        assertTrue(threadDump.contains("\"Alpha-Thread-1\" (id="))
        assertTrue(threadDump.contains("at app.buzer.Main.execute(Main.kt:42)"))

        // Size cap: must be <= 512 KiB
        assertTrue(threadDump.toByteArray(Charsets.UTF_8).size <= 512 * 1024)
    }

    @Test
    fun redactsSensitiveDataFromCapsuleAndArtifacts() {
        val secretContext = DiagnosticContextStore()
        secretContext.updateUi { it.copy(inFlightOperation = "login password=SecretPassword123!") }

        val capsule = collector.buildCapsule(
            reportSessionId = "rep-1",
            androidDiagnosticEventId = "evt-1",
            capturedAt = "2026-08-21T12:00:00Z",
            contextSnapshot = secretContext.snapshot()
        )

        val capsuleJson = collector.buildCapsuleJson(capsule)
        assertFalse(capsuleJson.contains("SecretPassword123!"))
        assertTrue(capsuleJson.contains("[REDACTED]"))

        // Redaction in app log text
        val logs = listOf(
            DiagnosticLogRecord(
                capturedAt = "2026-08-21T12:00:00Z",
                severity = "info",
                tag = "Auth",
                message = "Header Authorization: Bearer secret_jwt_token_999"
            )
        )
        val logText = collector.buildAppLogsText(logs)
        assertFalse(logText.contains("secret_jwt_token_999"))
        assertTrue(logText.contains("Bearer [REDACTED]"))

        // Redaction in network history
        val exchanges = listOf(
            DiagnosticNetworkExchange(
                startedAt = "2026-08-21T12:00:00Z",
                durationMillis = 50L,
                method = "POST",
                path = "/api/login",
                status = 200,
                outcome = "success",
                requestBody = "{\"token\":\"super_secret_token_123\"}",
                responseBody = "{\"todoist_token\":\"secret_todoist_token\"}",
                requestId = "req-1"
            )
        )
        val netJson = collector.buildNetworkHistoryJson(exchanges)
        assertFalse(netJson.contains("super_secret_token_123"))
        assertFalse(netJson.contains("secret_todoist_token"))
        assertTrue(netJson.contains("[REDACTED]"))
    }
}
