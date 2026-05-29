package app.buzer.android.enforcement

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class BlockerErrorReporterTest {
    @Test
    fun writesFailureToLocalLog() {
        val directory = Files.createTempDirectory("blocker-errors").toFile()
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
}
