package app.buzer.android.blockers

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.buzer.android.enforcement.AlarmIntentFactory
import java.io.File
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockerActivityFakeBackendTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val fakeBackend = FakeBackendClient()

    @Test
    fun startBlocker_startingCallsFakeBackend() {
        resetTestState()

        launchStartBlocker("task-start")

        composeRule.onNodeWithText("I'm starting").assertIsDisplayed()
        composeRule.onNodeWithText("Postpone 5 min").assertIsDisplayed()
        composeRule.onNodeWithText("I'm starting").performClick()

        waitForRecordedCall("/tasks/task-start/start")
        fakeBackend.assertRecorded("/tasks/task-start/start")
    }

    @Test
    fun deadlineBlocker_doneCallsFakeBackend() {
        resetTestState()

        launchDeadlineBlocker("task-deadline")

        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNodeWithText("Extend 30 min").assertIsDisplayed()
        composeRule.onNodeWithText("Reschedule").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()

        waitForRecordedCall("/tasks/task-deadline/done")
        fakeBackend.assertRecorded("/tasks/task-deadline/done")
    }

    @Test
    fun startBlocker_backendFailureHidesAndWritesErrorLog() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            val scenario = launchStartBlocker("task-start-fail")

            composeRule.onNodeWithText("I'm starting").assertIsDisplayed()
            composeRule.onNodeWithText("I'm starting").performClick()

            waitForActivityDestroyed(scenario)
            waitForRecordedCall("/tasks/task-start-fail/start")
            waitForErrorLog("action=START_TASK taskId=task-start-fail", "500")
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    @Test
    fun deadlineBlocker_backendFailureHidesAndWritesErrorLog() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            val scenario = launchDeadlineBlocker("task-deadline-fail")

            composeRule.onNodeWithText("Done").assertIsDisplayed()
            composeRule.onNodeWithText("Done").performClick()

            waitForActivityDestroyed(scenario)
            waitForRecordedCall("/tasks/task-deadline-fail/done")
            waitForErrorLog("action=COMPLETE_TASK taskId=task-deadline-fail", "500")
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    private fun resetTestState() {
        fakeBackend.setFailActions(false)
        fakeBackend.resetCalls()
        blockerErrorLog().delete()
    }

    private fun waitForRecordedCall(path: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var lastCalls = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastCalls = runCatching { fakeBackend.calls() }.getOrDefault(lastCalls)
            if (lastCalls.contains("\"path\":\"$path\"")) return
            SystemClock.sleep(100)
        }
        fail(
            "Expected fake backend call $path.\n" +
                "Recorded calls: $lastCalls\n" +
                "Blocker error log: ${blockerErrorLog().takeIf { it.exists() }?.readText().orEmpty()}"
        )
    }

    private fun waitForActivityDestroyed(scenario: ActivityScenario<out android.app.Activity>) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (scenario.state == Lifecycle.State.DESTROYED) return
            SystemClock.sleep(100)
        }
        fail("Expected blocker activity to finish, but state was ${scenario.state}")
    }

    private fun waitForErrorLog(vararg fragments: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val text = blockerErrorLog().takeIf { it.exists() }?.readText().orEmpty()
            if (fragments.all(text::contains)) return
            SystemClock.sleep(100)
        }
        fail(
            "Expected blocker error log to contain ${fragments.toList()}.\n" +
                "Blocker error log: ${blockerErrorLog().takeIf { it.exists() }?.readText().orEmpty()}"
        )
    }

    private fun launchStartBlocker(taskId: String): ActivityScenario<StartBlockerActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), StartBlockerActivity::class.java).apply {
                putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
            }
        )

    private fun launchDeadlineBlocker(taskId: String): ActivityScenario<DeadlineBlockerActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), DeadlineBlockerActivity::class.java).apply {
                putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
            }
        )

    private fun blockerErrorLog(): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "blocker_action_errors.log")
}
