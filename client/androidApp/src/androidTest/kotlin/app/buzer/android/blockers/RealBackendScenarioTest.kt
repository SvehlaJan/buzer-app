package app.buzer.android.blockers

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.buzer.android.e2e.E2EServerClient
import app.buzer.android.enforcement.AlarmIntentFactory
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
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

    private val e2eServer = E2EServerClient()

    @Before
    fun requireScenarioSupport() {
        assumeTrue(
            "backend has no /__scenario (tools/fake-backend) - skipping preset tests",
            runCatching { e2eServer.setScenario("none") }.isSuccess
        )
        File(
            ApplicationProvider.getApplicationContext<Context>().filesDir,
            "latest_snapshot.json"
        ).delete()
        e2eServer.reset()
        e2eServer.blockQueue(false)
        e2eServer.failSnapshot(false)
        e2eServer.failProjects(false)
        e2eServer.failNextChildAdd(false)
    }

    @After
    fun cleanUpFaultInjection() {
        runCatching {
            e2eServer.setScenario("none")
            e2eServer.blockQueue(false)
            e2eServer.failSnapshot(false)
            e2eServer.failProjects(false)
            e2eServer.failNextChildAdd(false)
        }
    }

    @Test
    fun forcedRefreshFailure_keepsExistingSnapshotAndShowsBanner() {
        val initialScenario = launchDeadlineBlocker("task-deadline")
        try {
            waitForText("Deadline blocker fixture")
            waitForText("Done")
        } finally {
            initialScenario.close()
        }

        e2eServer.failSnapshot(true)
        val scenario = launchDeadlineBlocker("task-deadline")
        try {
            waitForText("Deadline blocker fixture")
            waitForText("Done")

            waitForText("Refresh")
            composeRule.onNodeWithTag("session-warning-banner").assertIsDisplayed()
            composeRule.onNodeWithTag("session-warning-refresh").assertIsDisplayed()
            composeRule.onNodeWithTag("session-warning-open-todoist").assertIsDisplayed()

            composeRule.onNodeWithTag("session-warning-refresh").performClick()
            waitForRecordedCall("/tasks/snapshot")

            waitForText("Deadline blocker fixture")
            composeRule.onNodeWithTag("session-warning-banner").assertIsDisplayed()
            composeRule.onNodeWithTag("session-warning-refresh").assertIsDisplayed()
            composeRule.onNodeWithTag("session-warning-open-todoist").assertIsDisplayed()

            e2eServer.failSnapshot(false)
            composeRule.onNodeWithTag("session-warning-refresh").performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runCatching {
                    composeRule.onNodeWithTag("session-warning-banner").assertDoesNotExist()
                }.isSuccess
            }
            composeRule.onNodeWithText("Deadline blocker fixture").assertIsDisplayed()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun todoistRejection_stopsBatchAndPresentsWarningBanner() {
        e2eServer.setScenario("command_rejected")
        val scenario = launchDeadlineBlocker("task-deadline")
        try {
            waitForText("Done")
            composeRule.onNodeWithText("Done").performClick()
            waitForRecordedCall("/tasks/task-deadline/done")

            waitForText("Refresh")
            composeRule.onNodeWithTag("session-warning-banner").assertIsDisplayed()
            composeRule.onNodeWithTag("session-warning-refresh").assertIsDisplayed()
            composeRule.onNodeWithTag("session-warning-open-todoist").assertIsDisplayed()

            composeRule.onNodeWithText("Done").assertIsDisplayed().assertIsEnabled()
            assertTrue(scenario.state != Lifecycle.State.DESTROYED)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun deliveryUnknown_presentsWarningBannerWithAttachedSnapshot() {
        e2eServer.setScenario("delivery_unknown")
        val scenario = launchDeadlineBlocker("task-deadline")
        try {
            waitForText("Done")
            composeRule.onNodeWithText("Done").performClick()
            waitForRecordedCall("/tasks/task-deadline/done")

            waitForText("Refresh")
            composeRule.onNodeWithTag("session-warning-banner").assertIsDisplayed()
            composeRule.onNodeWithTag("session-warning-refresh").assertIsDisplayed()
            composeRule.onNodeWithTag("session-warning-open-todoist").assertIsDisplayed()

            composeRule.onNodeWithText("Done").assertIsDisplayed().assertIsEnabled()
            composeRule.onNodeWithText("Extend 30m").assertIsDisplayed().assertIsEnabled()
            composeRule.onNodeWithContentDescription("Reschedule action").assertIsDisplayed().assertIsEnabled()
            assertTrue(scenario.state != Lifecycle.State.DESTROYED)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun acceptedStale_presentsWarningBanner() {
        e2eServer.setScenario("accepted_stale")
        val scenario = launchDeadlineBlocker("task-deadline")
        try {
            waitForText("Done")
            composeRule.onNodeWithText("Done").performClick()
            waitForRecordedCall("/tasks/task-deadline/done")

            waitForText("Refresh")
            composeRule.onNodeWithTag("session-warning-banner").assertIsDisplayed()
            composeRule.onNodeWithTag("session-warning-refresh").assertIsDisplayed()
            composeRule.onNodeWithTag("session-warning-open-todoist").assertIsDisplayed()

            composeRule.onNodeWithText("Done").assertIsDisplayed().assertIsEnabled()
            assertTrue(scenario.state != Lifecycle.State.DESTROYED)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun busyBeforeSend_presentsWarningBanner() {
        e2eServer.blockQueue(true)
        val scenario = launchDeadlineBlocker("task-deadline")
        try {
            waitForText("Done")
            composeRule.onNodeWithText("Done").performClick()
            waitForRecordedCall("/tasks/task-deadline/done")

            waitForText("Refresh")
            composeRule.onNodeWithTag("session-warning-banner").assertIsDisplayed()
            composeRule.onNodeWithTag("session-warning-refresh").assertIsDisplayed()
            composeRule.onNodeWithTag("session-warning-open-todoist").assertIsDisplayed()

            e2eServer.blockQueue(false)
            composeRule.onNodeWithText("Done").assertIsDisplayed().assertIsEnabled()
            assertTrue(scenario.state != Lifecycle.State.DESTROYED)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun normalActionControlsReEnableImmediatelyAfterEveryOutcome() {
        e2eServer.setScenario("command_rejected")
        val scenario = launchDeadlineBlocker("task-deadline")
        try {
            waitForText("Done")
            composeRule.onNodeWithText("Done").performClick()
            waitForRecordedCall("/tasks/task-deadline/done")

            waitForText("Refresh")
            composeRule.onNodeWithText("Done").assertIsDisplayed().assertIsEnabled()
            composeRule.onNodeWithText("Extend 30m").assertIsDisplayed().assertIsEnabled()
            composeRule.onNodeWithContentDescription("Reschedule action").assertIsDisplayed().assertIsEnabled()

            e2eServer.setScenario("none")
            composeRule.onNodeWithText("Done").performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                scenario.state == Lifecycle.State.DESTROYED
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun checklistTogglesRemainInteractiveAfterFailedCheck() {
        val scenario = launchDeadlineBlocker("task-incomplete-checklist")
        try {
            waitForText("Incomplete checklist task")
            val checkboxMatcher = SemanticsMatcher.expectValue(
                SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.Checkbox
            ).and(hasAnySibling(hasText("Incomplete child item")))

            composeRule.onNode(checkboxMatcher).assertIsDisplayed().assertIsOff()

            e2eServer.setScenario("command_rejected")
            composeRule.onNode(checkboxMatcher).performClick()

            waitForRecordedCall("/tasks/task-incomplete-checklist/checklist/child-incomplete-open/check")
            waitForText("Refresh")
            composeRule.onNodeWithTag("session-warning-banner").assertIsDisplayed()
            composeRule.onNode(checkboxMatcher).assertIsDisplayed().assertIsOff().assertIsEnabled()

            e2eServer.setScenario("none")
            composeRule.onNode(checkboxMatcher).performClick()
            composeRule.waitUntil(timeoutMillis = 15_000) {
                runCatching {
                    composeRule.onNode(checkboxMatcher).assertIsOn()
                }.isSuccess
            }
        } finally {
            scenario.close()
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
            lastCalls = runCatching { e2eServer.calls() }.getOrDefault(lastCalls)
            if (lastCalls.contains("\"path\":\"$path\"")) return
            SystemClock.sleep(100)
        }
        fail("Expected backend call $path. Recorded calls: $lastCalls")
    }
}
