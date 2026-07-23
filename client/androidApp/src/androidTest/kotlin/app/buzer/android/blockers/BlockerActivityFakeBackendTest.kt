package app.buzer.android.blockers

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.closeSoftKeyboard
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
        fakeBackend.assertRecorded("/tasks/task-start/start", "\"actionId\":", "\"deviceId\":", "\"expectedOccurrenceKey\":")
    }

    @Test
    fun startBlocker_systemBackDoesNotCloseProductionBlocker() {
        resetTestState()
        val scenario = launchStartBlocker("task-start")
        try {
            waitForText("I'm starting")
            pressBack()

            assertActivityNotDestroyed(scenario)
        } finally {
            scenario.close()
        }
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
        fakeBackend.assertRecorded("/tasks/task-deadline/done", "\"actionId\":", "\"deviceId\":", "\"expectedOccurrenceKey\":")
    }

    @Test
    fun deadlineBlocker_systemBackDoesNotCloseProductionBlocker() {
        resetTestState()
        val scenario = launchDeadlineBlocker("task-deadline")
        try {
            waitForText("Done")
            pressBack()

            composeRule.onNodeWithText("Done").assertIsDisplayed()
            assertActivityNotDestroyed(scenario)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun deadlineBlocker_rescheduleCallsFakeBackend() {
        resetTestState()

        val scenario = launchDeadlineBlocker("task-reschedule")

        composeRule.onNodeWithContentDescription("Reschedule action").performScrollTo().performClick()
        waitForContentDescription("Start picker")
        composeRule.onNodeWithContentDescription("Start picker").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Duration picker").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Save task").assertIsDisplayed()
        composeRule.onAllNodesWithText("Done").assertCountEquals(0)
        selectDefaultDeadline()
        val saveLabel = waitForComputedSaveLabel()
        composeRule.onNodeWithText(saveLabel).performScrollTo().performClick()

        waitForActivityDestroyed(scenario)
        waitForRecordedCall("/tasks/task-reschedule/reschedule")
        fakeBackend.assertRecorded(
            "/tasks/task-reschedule/reschedule",
            "\"actionId\":",
            "\"deviceId\":",
            "\"expectedOccurrenceKey\":"
        )
    }

    @Test
    fun startBlocker_backendFailureShowsRetryDialogAndWritesErrorLog() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            val scenario = launchStartBlocker("task-start-fail")
            try {
                waitForText("I'm starting")
                composeRule.onNodeWithText("I'm starting").assertIsDisplayed()
                composeRule.onNodeWithText("I'm starting").performClick()

                waitForRecordedCall("/tasks/task-start-fail/start")
                waitForErrorLog("action=START_TASK taskId=task-start-fail", "forced_failure")
                waitForText("Action failed")
                assertFailureDialogSurvivesBack(scenario)
            } finally {
                scenario.close()
            }
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
            try {
                composeRule.onNodeWithContentDescription("Reschedule action").performScrollTo().performClick()
                selectDefaultDeadline()
                val saveLabel = waitForComputedSaveLabel()
                composeRule.onNodeWithText(saveLabel).performScrollTo().performClick()

                waitForRecordedCall("/tasks/task-reschedule-fail/reschedule")
                waitForErrorLog("action=RESCHEDULE_TASK taskId=task-reschedule-fail", "forced_failure")
                waitForText("Action failed")
                assertFailureDialogSurvivesBack(scenario)
            } finally {
                scenario.close()
            }
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
            try {
                waitForText("Done")
                composeRule.onNodeWithText("Done").assertIsDisplayed()
                composeRule.onNodeWithText("Done").performClick()

                waitForRecordedCall("/tasks/task-deadline-fail/done")
                waitForErrorLog("action=COMPLETE_TASK taskId=task-deadline-fail", "forced_failure")
                waitForText("Action failed")
                assertFailureDialogSurvivesBack(scenario)
            } finally {
                scenario.close()
            }
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    @Test
    fun morningReview_showsInvalidTaskFromSnapshot() {
        resetTestState()

        launchMorningReview()

        composeRule.onNodeWithText("tasks need attention", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("unresolved", substring = true).assertIsDisplayed()
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
        fakeBackend.assertRecorded("/tasks/task-deadline/done", "\"actionId\":", "\"deviceId\":", "\"expectedOccurrenceKey\":")
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
        repairInvalidTask()
        waitForContentDescription("Schedule: Set date, time & duration")
        composeRule.onNodeWithContentDescription("Schedule: Set date, time & duration").assertIsDisplayed()
        composeRule.onNodeWithText("Repair").performScrollTo().assertIsDisplayed()

        waitForAnyText("Start time is required")
        composeRule.onNodeWithText("Start time is required").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun morningReview_repairCallsFakeBackend() {
        resetTestState()
        val scenario = launchMorningReview()

        waitForText("Invalid assigned fixture")
        repairInvalidTask()
        waitForContentDescription("Schedule: Set date, time & duration")
        composeRule.onNodeWithContentDescription("Schedule: Set date, time & duration").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Notes: Clarify the missing commitment fields.")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithContentDescription("Notes").performTextInput(" Updated repair note")
        closeSoftKeyboard()
        selectDefaultRepairSchedule()
        val repairLabel = waitForComputedRepairLabel()
        composeRule.onNodeWithText(repairLabel).performScrollTo().performClick()

        waitForRecordedCall("/tasks/task-invalid/update")
        fakeBackend.assertRecorded(
            "/tasks/task-invalid/update",
            "\"actionId\":",
            "\"deviceId\":",
            "\"expectedOccurrenceKey\":"
        )
        composeRule.onAllNodesWithText("Invalid assigned fixture").assertCountEquals(0)
    }

    @Test
    fun morningReview_repairBackendFailureShowsErrorAndStaysOpen() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            val scenario = launchMorningReview()

            waitForText("Invalid assigned fixture")
            repairInvalidTask()
            selectDefaultRepairSchedule()
            val repairLabel = waitForComputedRepairLabel()
            composeRule.onNodeWithText(repairLabel).performScrollTo().performClick()

            waitForRecordedCall("/tasks/task-invalid/update")
            waitForErrorLog("action=UPDATE_TASK taskId=task-invalid", "forced_failure")
            waitForText("Action failed")
            waitForAnyText("Retry")
            waitForAnyText("Dismiss")
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
        composeRule.onNodeWithTag("morning-snooze-task-invalid").performClick()

        waitForActivityDestroyed(scenario)
        waitForRecordedCall("/review/dismiss")
        fakeBackend.assertRecorded("/review/dismiss")
    }

    private fun resetTestState() {
        // Each test replaces the fake backend's fixture snapshot. Clear the persisted authority
        // first so monotonic snapshot protection cannot intentionally retain the prior test's
        // snapshot after a fake-backend restart.
        File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "latest_snapshot.json").delete()
        fakeBackend.setFailActions(false)
        fakeBackend.setFailSnapshot(false)
        fakeBackend.resetCalls()
        blockerErrorLog().delete()
    }

    private fun repairInvalidTask() {
        composeRule.onNodeWithTag("morning-repair-task-invalid").performClick()
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

    private fun waitForComputedRepairLabel(): String {
        val matcher = Regex("""Repair \d{2}:\d{2}–\d{2}:\d{2}""")
        var match = ""
        composeRule.waitUntil(timeoutMillis = 15_000) {
            match = composeRule.onAllNodesWithText("Repair ", substring = true)
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
        // "Start picker" is a row of two side-by-side buttons (date, time); performClick() on
        // the row itself lands at its geometric center, which falls in the gap between the two
        // buttons and never opens the dialog. Click the first actual clickable child instead.
        composeRule.onNodeWithContentDescription("Start picker")
            .performScrollTo()
            .onChildren()
            .filter(hasClickAction())
            .onFirst()
            .performClick()
        waitForText("OK")
        composeRule.onNodeWithText("OK").performClick()
    }

    private fun selectDefaultRepairSchedule() {
        composeRule.onNodeWithText("Today").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Custom start time").performScrollTo().performClick()
        waitForContentDescription("Custom start time dialog apply")
        composeRule.onNodeWithContentDescription("Custom start time dialog apply").performClick()
        composeRule.onNodeWithText("30 min").performScrollTo().performClick()
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
                putExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY, "occurrence-$taskId-1")
            }
        )

    private fun launchDeadlineBlocker(taskId: String): ActivityScenario<DeadlineBlockerActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), DeadlineBlockerActivity::class.java).apply {
                putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
                putExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY, "occurrence-$taskId-1")
            }
        )

    private fun launchMorningReview(): ActivityScenario<MorningReviewActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), MorningReviewActivity::class.java)
        )

    private fun blockerErrorLog(): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "blocker_action_errors.log")
}
