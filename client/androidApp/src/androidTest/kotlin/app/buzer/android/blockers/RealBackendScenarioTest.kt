package app.buzer.android.blockers

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.buzer.android.enforcement.AlarmIntentFactory
import java.io.File
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fault-injection presets only exist on the real-backend E2E server
 * (backend/test/e2eServer.ts). Against tools/fake-backend these tests self-skip so the
 * Phase 1 fake-backend script and CI keep passing unchanged.
 */
@RunWith(AndroidJUnit4::class)
class RealBackendScenarioTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val fakeBackend = FakeBackendClient()

    @Before
    fun requireScenarioSupport() {
        assumeTrue(
            "backend has no /__scenario (tools/fake-backend) - skipping preset tests",
            runCatching { fakeBackend.setScenario("none") }.isSuccess
        )
        File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "latest_snapshot.json"
        ).delete()
        fakeBackend.resetCalls()
    }

    @Test
    fun deadlineDone_underCommandTimeout_goesReconciliationPending() {
        runScenarioDone("after_command_sent_timeout")
    }

    @Test
    fun deadlineDone_underSyncLag_goesReconciliationPending() {
        runScenarioDone("sync_lag_effect_unknown")
    }

    private fun runScenarioDone(scenarioName: String) {
        fakeBackend.setScenario(scenarioName)
        try {
            val scenario = launchDeadlineBlocker("task-deadline")
            try {
                waitForText("Done")
                composeRule.onNodeWithText("Done").performClick()
                waitForRecordedCall("/tasks/task-deadline/done")
                waitForReconciliationStatus("task-deadline", "pending")
            } finally {
                scenario.close()
            }
        } finally {
            fakeBackend.setScenario("none")
        }
    }

    private fun launchDeadlineBlocker(taskId: String): ActivityScenario<DeadlineBlockerActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), DeadlineBlockerActivity::class.java).apply {
                putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
                putExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY, "occurrence-$taskId-1")
            }
        )

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText(text, substring = true).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitForRecordedCall(path: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var lastCalls = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastCalls = runCatching { fakeBackend.calls() }.getOrDefault(lastCalls)
            if (lastCalls.contains("\"path\":\"$path\"")) return
            SystemClock.sleep(100)
        }
        fail("Expected backend call $path. Recorded calls: $lastCalls")
    }

    private fun waitForReconciliationStatus(taskId: String, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var last: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            last = runCatching { fakeBackend.taskReconciliationStatus(taskId) }.getOrNull() ?: last
            if (last == expected) return
            SystemClock.sleep(200)
        }
        fail("Expected $taskId reconciliationStatus=$expected, last seen: $last")
    }
}
