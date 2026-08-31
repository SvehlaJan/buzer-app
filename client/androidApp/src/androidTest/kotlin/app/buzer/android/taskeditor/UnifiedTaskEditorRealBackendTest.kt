package app.buzer.android.taskeditor

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.buzer.android.MainActivity
import app.buzer.android.BuildConfig
import app.buzer.android.blockers.DeadlineBlockerActivity
import app.buzer.android.blockers.MorningReviewActivity
import app.buzer.android.e2e.E2EServerClient
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.tasks.AddTaskDependencies
import app.buzer.android.ui.screens.EditTaskScreen
import app.buzer.shared.api.BuzerApiClient
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Authoritative integration coverage for the unified Update editor. These tests only use the
 * main backend's test adapter (backend/test/e2eServer.ts) through E2EServerClient.
 */
@RunWith(AndroidJUnit4::class)
class UnifiedTaskEditorRealBackendTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val e2eServer = E2EServerClient()

    @Before
    fun resetAuthoritativeState() {
        File(ApplicationProvider.getApplicationContext<Context>().filesDir, "latest_snapshot.json").delete()
        e2eServer.reset()
        resetProjectRepository()
    }

    @Test
    fun invalidEdit_correctsSchedule_postsUpdate_andReturnsToHomeAfterConfirmation() {
        renderRealEditHost("task-invalid")
        assertEntryValidation("Save", "Start time is required")

        chooseCompleteSchedule()
        submitAction("Save")

        waitForUpdateCall("task-invalid")
        assertRootPatchHasSchedule("task-invalid")
        waitForText("Edit returned")
    }

    @Test
    fun invalidRepair_correctsSchedule_postsUpdate_andReturnsToRemainingReview() {
        val scenario = ActivityScenario.launch(MorningReviewActivity::class.java)
        try {
            waitForText("Invalid assigned fixture")
            composeRule.onNodeWithTag("morning-repair-task-invalid").performClick()
            waitForText("Repair task")
            assertEntryValidation("Repair", "Start time is required")

            chooseCompleteSchedule()
            submitAction("Repair")

            waitForUpdateCall("task-invalid")
            assertRootPatchHasSchedule("task-invalid")
            waitForText("Invalid deadline blocker fixture")
            waitUntil("Repaired task remained in the Morning Review list") {
                composeRule.onAllNodesWithText("Invalid assigned fixture").fetchSemanticsNodes().isEmpty()
            }
            assertActivityNotDestroyed(scenario)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun invalidDeadlineRecovery_correctsDuration_postsUpdate_andClosesOnlyAfterConfirmation() {
        val scenario = launchDeadlineBlocker("task-invalid-blocker")
        try {
            waitForRescheduleAction()
            composeRule.onNodeWithContentDescription("Reschedule action").performClick()
            waitForText("Reschedule task")
            assertEntryValidation("Reschedule", "Duration is required")

            composeRule.onNodeWithText("30 min").performScrollTo().performClick()
            submitAction("Reschedule")

            waitForUpdateResolution(scenario)
            waitForUpdateCall("task-invalid-blocker")
            assertRootPatchHasSchedule("task-invalid-blocker")
        } finally {
            scenario.close()
        }
    }

    @Test
    fun uncertainEdit_showsWarningBannerAndAllowsRetryToExit() {
        assumeTrue(
            "backend has no /__scenario (tools/fake-backend) - skipping real-backend-only test",
            runCatching { e2eServer.setScenario("none") }.isSuccess
        )
        renderRealEditHost("task-one-time-empty")
        try {
            e2eServer.setScenario("delivery_unknown")
            val actionLabel = actionLabel("Save")
            composeRule.onNodeWithText(actionLabel).performClick()

            waitForUpdateCall("task-one-time-empty")
            waitForText("Refresh")
            composeRule.onNodeWithTag("session-warning-banner").assertIsDisplayed()
            composeRule.onNodeWithText(actionLabel).assertIsEnabled()
            assertEquals(1, e2eServer.updateCallCount("task-one-time-empty"))

            e2eServer.setScenario("none")
            composeRule.onNodeWithText(actionLabel).performClick()
            waitUntil("Update was not retried after clearing scenario") {
                e2eServer.updateCallCount("task-one-time-empty") == 2
            }
            waitForText("Edit returned")
        } finally {
            e2eServer.setScenario("none")
        }
    }

    @Test
    fun blockerSupportedDirectChecklistEdit_postsFullUpdate() {
        val blocker = launchDeadlineBlocker("task-one-time-partial")
        try {
            waitForRescheduleAction()
            composeRule.onNodeWithContentDescription("Reschedule action").performClick()
            waitForText("Reschedule task")
            composeRule.onNodeWithText("Checklist").performScrollTo().performClick()
            val openChecklistToggle = composeRule.onAllNodes(isToggleable())[1]
            openChecklistToggle.performScrollTo().assertIsOff().performClick()
            openChecklistToggle.assertIsOn()
            submitAction("Reschedule")

            waitForUpdateResolution(blocker)
            waitForUpdateCall("task-one-time-partial")
            val childMutations = e2eServer.updatePayloads("task-one-time-partial")
                .single()
                .getValue("childMutations")
                .jsonArray
            assertTrue("Expected a supported direct checklist mutation, got $childMutations", childMutations.isNotEmpty())
        } finally {
            blocker.close()
        }
    }

    @Test
    fun nestedChecklistHierarchy_staysReadOnlyInEditHost() {
        renderRealEditHost("task-unsupported-hierarchy")
        composeRule.onNodeWithContentDescription("Task title").assertIsEnabled()
        composeRule.onNodeWithText("Checklist").performScrollTo().performClick()
        waitForText("child_has_schedule")
        composeRule.onNodeWithText("Add checklist item").assertIsNotEnabled()
    }

    private fun openEditor(title: String) {
        waitForText(title)
        composeRule.onNodeWithText(title).performClick()
        waitForText("Edit task")
    }

    private fun assertEntryValidation(primaryVerb: String, fieldError: String) {
        waitForText("Fix the highlighted fields")
        composeRule.onNodeWithText("Fix the highlighted fields").assertIsDisplayed()
        composeRule.onNodeWithText(fieldError).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(primaryVerb).assertIsNotEnabled()
    }

    private fun chooseCompleteSchedule() {
        composeRule.onNodeWithText("Today").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Custom start time").performScrollTo().performClick()
        waitForContentDescription("Custom start time dialog apply")
        composeRule.onNodeWithContentDescription("Custom start time dialog apply").performClick()
        composeRule.onNodeWithText("30 min").performScrollTo().performClick()
    }

    private fun submitAction(verb: String) {
        composeRule.onNodeWithText(actionLabel(verb)).performClick()
    }

    private fun actionLabel(verb: String): String {
        val matcher = Regex("""$verb \d{2}:\d{2}–\d{2}:\d{2}""")
        var label = ""
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            label = composeRule.onAllNodesWithText("$verb ", substring = true)
                .fetchSemanticsNodes()
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }
                .firstOrNull(matcher::matches)
                .orEmpty()
            if (label.isNotEmpty()) return label
            if (runCatching {
                composeRule.onNodeWithText(verb).assertIsDisplayed()
            }.isSuccess) return verb
            SystemClock.sleep(100)
        }
        throw AssertionError("No visible primary action with verb $verb")
    }

    private fun waitForUpdateCall(taskId: String) {
        waitUntil("Expected POST /tasks/$taskId/update") { e2eServer.updateCallCount(taskId) >= 1 }
    }

    private fun assertRootPatchHasSchedule(taskId: String) {
        val rootPatch = e2eServer.updatePayloads(taskId).single().getValue("rootPatch").toString()
        assertTrue("Expected dueDatetime in $rootPatch", rootPatch.contains("dueDatetime"))
        assertTrue("Expected durationMinutes in $rootPatch", rootPatch.contains("durationMinutes"))
    }

    private fun waitForRescheduleAction() = waitForContentDescription("Reschedule action")

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching { composeRule.onNodeWithText(text, substring = true).assertIsDisplayed() }.isSuccess
        }
    }

    private fun waitForContentDescription(contentDescription: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching { composeRule.onNodeWithContentDescription(contentDescription).assertIsDisplayed() }.isSuccess
        }
    }

    private fun waitForActivityDestroyed(scenario: ActivityScenario<out android.app.Activity>) {
        waitUntil("Expected blocker activity to finish, but state was ${scenario.state}") {
            scenario.state == Lifecycle.State.DESTROYED
        }
    }

    /**
     * Waits for the blocker to resolve its update request without preempting an in-flight request.
     * A visible error dialog is a terminal test observation too, so a transport failure is reported
     * with the UI state rather than being misreported as an unexplained activity timeout.
     */
    private fun waitForUpdateResolution(scenario: ActivityScenario<out android.app.Activity>) {
        runCatching {
            composeRule.waitUntil(timeoutMillis = 45_000) {
                scenario.state == Lifecycle.State.DESTROYED || visibleUpdateFailureState() != null
            }
        }

        if (scenario.state == Lifecycle.State.DESTROYED) return

        fail(
            "Expected blocker activity to finish after confirmed update, but state was ${scenario.state}. " +
                "Visible update UI: ${visibleUpdateFailureState() ?: "no failure dialog before 45s"}"
        )
    }

    private fun visibleUpdateFailureState(): String? {
        val retryVisible = runCatching {
            composeRule.onNodeWithText("Retry").assertIsDisplayed()
        }.isSuccess
        if (!retryVisible) return null

        return when {
            runCatching { composeRule.onNodeWithText("Task update").assertIsDisplayed() }.isSuccess ->
                "Task update error dialog with Retry"
            runCatching { composeRule.onNodeWithText("Action failed").assertIsDisplayed() }.isSuccess ->
                "Action failed dialog with Retry"
            else -> null
        }
    }

    private fun assertActivityNotDestroyed(scenario: ActivityScenario<out android.app.Activity>) {
        if (scenario.state == Lifecycle.State.DESTROYED) {
            fail("Expected host activity to stay open, but it was destroyed")
        }
    }

    private fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching(predicate).getOrDefault(false)) return
            SystemClock.sleep(100)
        }
        fail("$message. Recorded update calls: ${runCatching { e2eServer.updatePayloads("task-invalid") }.getOrDefault(emptyList())}")
    }

    private fun launchDeadlineBlocker(taskId: String): ActivityScenario<DeadlineBlockerActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), DeadlineBlockerActivity::class.java).apply {
                putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
                putExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY, "occurrence-$taskId-1")
            }
        )

    private fun renderRealEditHost(taskId: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val task = runBlocking {
            BuzerApiClient(
                baseUrl = BuildConfig.BUZER_BACKEND_BASE_URL,
                token = BuildConfig.BUZER_BACKEND_TOKEN
            ).getSnapshot().tasks.first { it.id == taskId }
        }
        val coordinator = EnforcementDependencies.coordinator(context)
        val deviceId = EnforcementDependencies.deviceIdentityStore(context).deviceId()
        val exited = mutableStateOf(false)
        composeRule.setContent {
            if (exited.value) {
                androidx.compose.material3.Text("Edit returned")
            } else {
                EditTaskScreen(
                    task = task,
                    deviceId = deviceId,
                    onBack = { exited.value = true },
                    onUpdate = coordinator::updateTask
                )
            }
        }
        waitForText("Edit task")
    }

    private fun resetProjectRepository() {
        val field = AddTaskDependencies::class.java.getDeclaredField("cachedProjectRepository")
        field.isAccessible = true
        field.set(if (Modifier.isStatic(field.modifiers)) null else AddTaskDependencies, null)
    }
}
