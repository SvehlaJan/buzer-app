package app.buzer.android.enforcement

import app.buzer.android.observability.AndroidObservability
import app.buzer.android.observability.NoOpAndroidObservability
import app.buzer.android.observability.RecordingAndroidObservability
import app.buzer.shared.api.BuzerApiException
import app.buzer.shared.diagnostics.DiagnosticCorrelation
import java.io.File
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockerErrorReporterTest {
    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("blocker-errors-test").toFile()
        BlockerErrorReporter.resetDeduplicationForTests()
    }

    @Test
    fun writesFailureToLocalLog() {
        val fileName = "blocker_errors_test.log"
        val reporter = BlockerErrorReporter(directory, fileName = fileName)

        reporter.report(
            BlockerAction.POSTPONE_START,
            taskId = "task-1",
            message = "backend unavailable",
            showNotification = false
        )

        val text = directory.resolve(fileName).readText()
        assertTrue(text.contains("POSTPONE_START"))
        assertTrue(text.contains("task-1"))
        assertTrue(text.contains("backend unavailable"))
    }

    @Test
    fun routesCriticalNonFatalToObservability() {
        val recordingObservability = RecordingAndroidObservability()
        val reporter = BlockerErrorReporter(directory, observability = recordingObservability)
        val failure = IllegalStateException("database lock timeout")

        reporter.report(
            action = BlockerAction.START_TASK,
            taskId = "task-123",
            message = "Start task failed",
            showNotification = false,
            throwable = failure
        )

        assertEquals(1, recordingObservability.handledExceptions.size)
        val captured = recordingObservability.handledExceptions.single()
        assertEquals("enforcement.start_task", captured.marker)
        assertEquals(failure, captured.throwable)
        assertEquals("task-123", captured.context.taskId)
        assertEquals("Enforcement", captured.context.appSection)
    }

    @Test
    fun preservesApiRequestIdInHandledErrorCorrelation() {
        val recordingObservability = RecordingAndroidObservability()
        val reporter = BlockerErrorReporter(directory, observability = recordingObservability)
        val failure = BuzerApiException(
            status = 500,
            code = "server_error",
            retryAfterSeconds = null,
            snapshot = null,
            requestId = "request-123"
        )

        reporter.report(
            action = BlockerAction.START_TASK,
            taskId = "task-123",
            message = "Start task failed",
            showNotification = false,
            throwable = failure
        )

        assertEquals("request-123", recordingObservability.handledExceptions.single().context.requestId)
    }

    @Test
    fun routesValidationFailureToLogOnly() {
        val recordingObservability = RecordingAndroidObservability()
        val reporter = BlockerErrorReporter(directory, observability = recordingObservability)
        val validationError = BuzerApiException(
            status = 400,
            code = "validation_error",
            retryAfterSeconds = null,
            snapshot = null,
            message = "Task title is invalid"
        )

        reporter.report(
            action = BlockerAction.UPDATE_TASK,
            taskId = "task-456",
            message = "validation failed: invalid title",
            showNotification = false,
            throwable = validationError
        )

        assertTrue(recordingObservability.handledExceptions.isEmpty())
        assertEquals(1, recordingObservability.logs.size)
        val log = recordingObservability.logs.single().first
        assertEquals("warning", log.severity)
        assertEquals("BlockerError", log.tag)
        assertTrue(log.message.contains("action=UPDATE_TASK"))
        assertTrue(log.message.contains("taskId=task-456"))
    }

    @Test
    fun routesCoordinatorCreationFailureToObservability() {
        val recordingObservability = RecordingAndroidObservability()
        val reporter = BlockerErrorReporter(directory, observability = recordingObservability)
        val configError = IllegalStateException("BUZER_BACKEND_BASE_URL missing")

        reporter.report(
            action = BlockerAction.REFRESH_SNAPSHOT,
            taskId = null,
            message = "coordinator create failed",
            showNotification = false,
            throwable = configError
        )

        assertEquals(1, recordingObservability.handledExceptions.size)
        val captured = recordingObservability.handledExceptions.single()
        assertEquals("enforcement.refresh_snapshot", captured.marker)
        assertEquals(configError, captured.throwable)
    }

    @Test
    fun deduplicatesIdenticalThrowables() {
        val recordingObservability = RecordingAndroidObservability()
        val reporter1 = BlockerErrorReporter(directory, fileName = "log1.log", observability = recordingObservability)
        val reporter2 = BlockerErrorReporter(directory, fileName = "log2.log", observability = recordingObservability)
        val sharedThrowable = RuntimeException("shared critical network partition")

        // First report at lower boundary
        reporter1.report(
            action = BlockerAction.COMPLETE_TASK,
            taskId = "task-1",
            message = "failure 1",
            showNotification = false,
            throwable = sharedThrowable
        )

        // Second report at outer boundary
        reporter2.report(
            action = BlockerAction.COMPLETE_TASK,
            taskId = "task-1",
            message = "failure 2 (outer)",
            showNotification = false,
            throwable = sharedThrowable
        )

        // Handled exception should only be captured once
        assertEquals(1, recordingObservability.handledExceptions.size)
        // But both log files should have received the error write
        assertTrue(directory.resolve("log1.log").exists())
        assertTrue(directory.resolve("log2.log").exists())
    }

    @Test
    fun isolatesObserverFailures() {
        val faultyObservability = object : AndroidObservability by NoOpAndroidObservability {
            override fun captureHandled(marker: String, throwable: Throwable, context: DiagnosticCorrelation): String? {
                throw IllegalStateException("Sentry service crashed")
            }
        }
        val fileName = "isolated_errors.log"
        val reporter = BlockerErrorReporter(directory, fileName = fileName, observability = faultyObservability)

        reporter.report(
            action = BlockerAction.EXTEND_TASK,
            taskId = "task-789",
            message = "Action extend failed",
            showNotification = false,
            throwable = RuntimeException("Backend timeout")
        )

        val text = directory.resolve(fileName).readText()
        assertTrue(text.contains("EXTEND_TASK"))
        assertTrue(text.contains("task-789"))
    }
}
