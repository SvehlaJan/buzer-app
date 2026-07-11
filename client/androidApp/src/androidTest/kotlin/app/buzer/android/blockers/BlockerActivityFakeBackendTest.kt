package app.buzer.android.blockers

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.pressBack
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.buzer.android.MainActivity
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

        waitForText("I'm starting")
        composeRule.onNodeWithText("Start now").assertIsDisplayed()
        composeRule.onNodeWithText("Start blocker fixture").assertIsDisplayed()
        composeRule.onNodeWithText("Bring the printed form and check opening hours.").assertIsDisplayed()
        waitForText("min block")
        composeRule.onNodeWithText("I'm starting").assertIsDisplayed()
        composeRule.onNodeWithText("Postpone 5 min").assertIsDisplayed()
        composeRule.onAllNodesWithText("Back").assertCountEquals(0)
        composeRule.onNodeWithText("I'm starting").performClick()

        waitForRecordedCall("/tasks/task-start/start")
        fakeBackend.assertRecorded("/tasks/task-start/start")
    }

    @Test
    fun startBlocker_systemBackDoesNotCloseProductionBlocker() {
        resetTestState()
        val scenario = launchStartBlocker("task-start")

        waitForText("I'm starting")
        pressBack()

        composeRule.onNodeWithText("I'm starting").assertIsDisplayed()
        assertActivityNotDestroyed(scenario)
        scenario.close()
    }

    @Test
    fun deadlineBlocker_doneCallsFakeBackend() {
        resetTestState()

        launchDeadlineBlocker("task-deadline")

        waitForText("Done")
        composeRule.onNodeWithText("Block ended").assertIsDisplayed()
        composeRule.onNodeWithText("Deadline blocker fixture").assertIsDisplayed()
        composeRule.onNodeWithText("Share the final numbers before closing the block.").assertIsDisplayed()
        waitForText("1h block")
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNodeWithText("Extend 30m").assertIsDisplayed()
        composeRule.onNodeWithText("Reschedule").assertIsDisplayed()
        composeRule.onAllNodesWithText("Back").assertCountEquals(0)
        composeRule.onNodeWithText("Done").performClick()

        waitForRecordedCall("/tasks/task-deadline/done")
        fakeBackend.assertRecorded("/tasks/task-deadline/done")
    }

    @Test
    fun deadlineBlocker_systemBackDoesNotCloseProductionBlocker() {
        resetTestState()
        val scenario = launchDeadlineBlocker("task-deadline")

        waitForText("Done")
        pressBack()

        composeRule.onNodeWithText("Done").assertIsDisplayed()
        assertActivityNotDestroyed(scenario)
        scenario.close()
    }

    @Test
    fun deadlineBlocker_rescheduleCallsFakeBackend() {
        resetTestState()

        val scenario = launchDeadlineBlocker("task-reschedule")

        waitForText("Reschedule")
        composeRule.onNodeWithContentDescription("Reschedule action").performScrollTo().performClick()
        waitForContentDescription("Start picker")
        composeRule.onNodeWithContentDescription("Start picker").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Duration picker").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Save task").assertIsDisplayed()
        composeRule.onAllNodesWithText("Done").assertCountEquals(0)
        selectDefaultDeadline()
        val saveLabel = waitForComputedSaveLabel()
        composeRule.onNodeWithText(saveLabel).performClick()

        waitForActivityDestroyed(scenario)
        waitForRecordedCall("/tasks/task-reschedule/reschedule")
        fakeBackend.assertRecorded(
            "/tasks/task-reschedule/reschedule",
            "durationMinutes",
            "30",
            "dueDatetime"
        )
    }

    @Test
    fun startBlocker_backendFailureShowsRetryDialogAndWritesErrorLog() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            val scenario = launchStartBlocker("task-start-fail")

            waitForText("I'm starting")
            composeRule.onNodeWithText("I'm starting").assertIsDisplayed()
            composeRule.onNodeWithText("I'm starting").performClick()

            waitForRecordedCall("/tasks/task-start-fail/start")
            waitForErrorLog("action=START_TASK taskId=task-start-fail", "500")
            waitForText("Action failed")
            assertFailureDialogSurvivesBack(scenario)
            scenario.close()
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    @Test
    fun deadlineBlocker_rescheduleBackendFailureShowsRetryDialogAndWritesErrorLog() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            val scenario = launchDeadlineBlocker("task-reschedule-fail")

            waitForText("Reschedule")
            composeRule.onNodeWithContentDescription("Reschedule action").performScrollTo().performClick()
            selectDefaultDeadline()
            val saveLabel = waitForComputedSaveLabel()
            composeRule.onNodeWithText(saveLabel).performClick()

            waitForRecordedCall("/tasks/task-reschedule-fail/reschedule")
            waitForErrorLog("action=RESCHEDULE_TASK taskId=task-reschedule-fail", "500")
            waitForText("Action failed")
            assertFailureDialogSurvivesBack(scenario)
            scenario.close()
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    @Test
    fun deadlineBlocker_backendFailureShowsRetryDialogAndWritesErrorLog() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            val scenario = launchDeadlineBlocker("task-deadline-fail")

            waitForText("Done")
            composeRule.onNodeWithText("Done").assertIsDisplayed()
            composeRule.onNodeWithText("Done").performClick()

            waitForRecordedCall("/tasks/task-deadline-fail/done")
            waitForErrorLog("action=COMPLETE_TASK taskId=task-deadline-fail", "500")
            waitForText("Action failed")
            assertFailureDialogSurvivesBack(scenario)
            scenario.close()
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    @Test
    fun morningReview_showsInvalidTaskFromSnapshot() {
        resetTestState()

        launchMorningReview()

        composeRule.onNodeWithText("1 task needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("1 unresolved").assertIsDisplayed()
        waitForText("Invalid assigned fixture")
        composeRule.onNodeWithText("Invalid assigned fixture").assertIsDisplayed()
        composeRule.onNodeWithText("No start time or duration set").assertIsDisplayed()
        composeRule.onNodeWithText("Clarify the missing commitment fields.").assertIsDisplayed()
        composeRule.onAllNodesWithText("left after this task", substring = true).assertCountEquals(0)
    }

    @Test
    fun homeMorningReviewButtonOpensLiveMorningReview() {
        resetTestState()

        ActivityScenario.launch(MainActivity::class.java)

        waitForContentDescription("Morning review")
        composeRule.onNodeWithContentDescription("Morning review").performClick()

        waitForText("Invalid assigned fixture")
        composeRule.onNodeWithText("No start time or duration set").assertIsDisplayed()
    }

    @Test
    fun homeScreenShowsTimelineAgendaAndFabStack() {
        resetTestState()

        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Deadline blocker fixture")
        waitForAnyText("remaining")
        composeRule.onNodeWithText("Start blocker fixture").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add task").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Morning review").assertIsDisplayed()
        composeRule.onAllNodesWithText("Start blocker").assertCountEquals(0)
        composeRule.onAllNodesWithText("Deadline blocker").assertCountEquals(0)
    }

    @Test
    fun homeCurrentBlockDoneCallsFakeBackend() {
        resetTestState()

        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Deadline blocker fixture")
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Finish current task").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Finish current task").performClick()

        waitForRecordedCall("/tasks/task-deadline/done")
        fakeBackend.assertRecorded("/tasks/task-deadline/done")
    }

    @Test
    fun homeScreenShowsRefreshFailureBanner() {
        resetTestState()
        fakeBackend.setFailSnapshot(true)
        try {
            ActivityScenario.launch(MainActivity::class.java)

            waitForText("500")
            composeRule.onNodeWithText("500", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText("Retry").assertIsDisplayed()
        } finally {
            fakeBackend.setFailSnapshot(false)
        }
    }

    @Test
    fun morningReview_repairShowsValidationErrorForMissingDeadline() {
        resetTestState()

        launchMorningReview()

        waitForText("Invalid assigned fixture")
        composeRule.onNodeWithText("Repair").performClick()
        waitForContentDescription("Start picker")
        composeRule.onNodeWithContentDescription("Start picker").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Duration picker").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Save task").performScrollTo().performClick()

        composeRule.onNodeWithText("Start time is required").assertIsDisplayed()
    }

    @Test
    fun morningReview_repairCallsFakeBackend() {
        resetTestState()
        val scenario = launchMorningReview()

        waitForText("Invalid assigned fixture")
        composeRule.onNodeWithText("Repair").performClick()
        waitForContentDescription("Start picker")
        composeRule.onNodeWithContentDescription("Start picker").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Duration picker").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("More").performScrollTo().performClick()
        composeRule.onNodeWithText("Details").performTextInput(" Updated repair note")
        selectDefaultDeadline()
        val saveLabel = waitForComputedSaveLabel()
        composeRule.onNodeWithText(saveLabel).performClick()

        waitForActivityDestroyed(scenario)
        waitForRecordedCall("/tasks/task-invalid/reschedule")
        fakeBackend.assertRecorded(
            "/tasks/task-invalid/reschedule",
            "durationMinutes",
            "30",
            "dueDatetime",
            "description",
            "Updated repair note"
        )
    }

    @Test
    fun morningReview_repairBackendFailureShowsErrorAndStaysOpen() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            val scenario = launchMorningReview()

            waitForText("Invalid assigned fixture")
            composeRule.onNodeWithText("Repair").performClick()
            selectDefaultDeadline()
            val saveLabel = waitForComputedSaveLabel()
            composeRule.onNodeWithText(saveLabel).performClick()

            waitForRecordedCall("/tasks/task-invalid/reschedule")
            waitForErrorLog("action=RESCHEDULE_TASK taskId=task-invalid", "500")
            waitForText("Action failed")
            composeRule.onNodeWithText("Retry").assertIsDisplayed()
            composeRule.onNodeWithText("Dismiss").assertIsDisplayed()
            assertActivityNotDestroyed(scenario)
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    @Test
    fun morningReview_snoozeCallsFakeBackendAndHides() {
        resetTestState()
        val scenario = launchMorningReview()

        waitForText("Invalid assigned fixture")
        composeRule.onNodeWithText("Snooze").performClick()

        waitForActivityDestroyed(scenario)
        waitForRecordedCall("/review/dismiss")
        fakeBackend.assertRecorded("/review/dismiss")
    }

    private fun resetTestState() {
        fakeBackend.setFailActions(false)
        fakeBackend.setFailSnapshot(false)
        fakeBackend.resetCalls()
        blockerErrorLog().delete()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText(text, substring = true).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitForAnyText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForContentDescription(contentDescription: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithContentDescription(contentDescription).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitForComputedSaveLabel(): String {
        val matcher = Regex("""Save \d{2}:\d{2}-\d{2}:\d{2}""")
        var match = ""
        composeRule.waitUntil(timeoutMillis = 15_000) {
            match = composeRule.onAllNodesWithText("Save ", substring = true)
                .fetchSemanticsNodes()
                .asSequence()
                .flatMap { node ->
                    node.config.getOrNull(SemanticsProperties.Text)
                        ?.asSequence()
                        ?.map { it.text }
                        ?: emptySequence()
                }
                .firstOrNull { matcher.matches(it) }
                .orEmpty()
            match.isNotEmpty()
        }
        return match
    }

    private fun waitForRecordedCall(path: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
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

    private fun selectDefaultDeadline() {
        composeRule.onNodeWithContentDescription("Start picker").performClick()
        waitForText("OK")
        composeRule.onNodeWithText("OK").performClick()
    }

    private fun waitForActivityDestroyed(scenario: ActivityScenario<out android.app.Activity>) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (scenario.state == Lifecycle.State.DESTROYED) return
            SystemClock.sleep(100)
        }
        fail("Expected blocker activity to finish, but state was ${scenario.state}")
    }

    private fun assertActivityNotDestroyed(scenario: ActivityScenario<out android.app.Activity>) {
        if (scenario.state == Lifecycle.State.DESTROYED) {
            fail("Expected blocker activity to stay open, but it was destroyed")
        }
    }

    private fun assertFailureDialogSurvivesBack(scenario: ActivityScenario<out android.app.Activity>) {
        composeRule.onNodeWithText("Action failed").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onAllNodesWithText("Dismiss").assertCountEquals(0)

        pressBack()

        composeRule.onNodeWithText("Action failed").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onAllNodesWithText("Dismiss").assertCountEquals(0)
        assertActivityNotDestroyed(scenario)
    }

    private fun waitForErrorLog(vararg fragments: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
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

    private fun launchMorningReview(): ActivityScenario<MorningReviewActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), MorningReviewActivity::class.java)
        )

    private fun blockerErrorLog(): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "blocker_action_errors.log")
}
