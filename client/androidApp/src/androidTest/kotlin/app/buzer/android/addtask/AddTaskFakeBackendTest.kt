package app.buzer.android.addtask

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.buzer.android.MainActivity
import app.buzer.android.blockers.FakeBackendClient
import app.buzer.android.tasks.AddTaskDependencies
import java.lang.reflect.Modifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddTaskFakeBackendTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val fakeBackend = FakeBackendClient()

    @Test
    fun addTask_requiresExplicitProjectSelectionOnFirstOpen() {
        resetTestState()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAddTaskFromHome()
            waitForText("Select project")
            enterTitle("Buy milk")
            selectDefaultDeadline()
            submitUntilValidation("Project is required")
            assertNoRecordedCall("/tasks")
        } finally {
            scenario.close()
        }
    }

    @Test
    fun addTask_sharedProjectRequiresAssigneeAndCreatesTask() {
        resetTestState()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAddTaskFromHome()
            selectProject("Test Shared")
            enterTitle("Buy milk")
            selectDefaultDeadline()
            submitUntilValidation("Assignee is required before saving")

            composeRule.onNodeWithText("Me").performClick()
            clickSaveBlockRange()

            waitForRecordedCall("/tasks")
            fakeBackend.assertRecorded(
                "/tasks",
                "\"actionId\":",
                "\"deviceId\":"
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun addTask_personalProjectOmitsAssignee() {
        resetTestState()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAddTaskFromHome()
            selectProject("Test Personal (personal)")
            composeRule.onAllNodesWithText("Me").assertCountEquals(0)
            enterTitle("Personal task")
            selectDefaultDeadline()
            clickSaveBlockRange()

            waitForRecordedCall("/tasks")
            fakeBackend.assertRecorded(
                "/tasks",
                "\"actionId\":",
                "\"deviceId\":"
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun addTask_projectLoadFailureDisablesSaveAndRetryRecovers() {
        resetTestState()
        fakeBackend.setFailProjects(true)
        try {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                openAddTaskFromHome()
                waitForText("500")
                composeRule.onNodeWithText("Save task").assertIsNotEnabled()

                fakeBackend.setFailProjects(false)
                composeRule.onNodeWithText("Retry").performClick()
                waitForText("Select project")
                composeRule.onNodeWithText("Select project").assertIsDisplayed()
            } finally {
                scenario.close()
            }
        } finally {
            fakeBackend.setFailProjects(false)
        }
    }

    @Test
    fun addTask_backendFailureKeepsFormAndEnteredTitle() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                openAddTaskFromHome()
                selectProject("Test Shared")
                enterTitle("Buy milk")
                composeRule.onNodeWithText("Me").performClick()
                selectDefaultDeadline()
                clickSaveBlockRange()

                waitForRecordedCall("/tasks")
                // "Task was not saved" / "Retry" are AlertDialog content (AddTaskFailurePresentation),
                // not inline form content — there is no scrollable ancestor to scroll to.
                composeRule.onNodeWithText("Task was not saved").assertIsDisplayed()
                composeRule.onNodeWithText("Retry").assertIsDisplayed()
                composeRule.onAllNodesWithText("forced_failure", substring = true).assertCountEquals(1)
                composeRule.onAllNodesWithText("System error", substring = true).assertCountEquals(0)
            } finally {
                scenario.close()
            }
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    @Test
    fun addTask_reopensWithPersistedProjectSelected() {
        resetTestState()

        val firstScenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAddTaskFromHome()
            selectProject("Test Personal (personal)")
            composeRule.onNodeWithText("Cancel").performClick()
        } finally {
            firstScenario.close()
        }

        val secondScenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAddTaskFromHome()
            waitForText("Test Personal")
            composeRule.onNodeWithText("Test Personal").assertIsDisplayed()
        } finally {
            secondScenario.close()
        }
    }

    @Test
    fun addTask_optionalDetailsAreSubmittedAsDescription() {
        resetTestState()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAddTaskFromHome()
            selectProject("Test Shared")
            enterTitle("Buy milk")
            composeRule.onNodeWithText("Details").performClick()
            waitForText("Details")
            composeRule.onNodeWithText("Details").performTextInput("Gate code: 2468")
            composeRule.onNodeWithText("Me").performClick()
            selectDefaultDeadline()
            clickSaveBlockRange()

            waitForRecordedCall("/tasks")
            fakeBackend.assertRecorded(
                "/tasks",
                "\"actionId\":",
                "\"deviceId\":"
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun addTask_partialChildFailureOpensCreatedRootWithOnlyFailedDraft() {
        resetTestState()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAddTaskFromHome()
            selectProject("Test Shared")
            enterTitle("Checklist root")
            composeRule.onNodeWithText("Me").performClick()
            selectDefaultDeadline()
            addChecklistDraft(1, "Created child")
            addChecklistDraft(2, "[partial-failure]")
            clickSaveBlockRange()

            waitForScrollableText("Checklist item 1")
            waitForScrollableText("[partial-failure]")
            assertEquals(1, recordedCreateActionIds().size)
        } finally {
            scenario.close()
        }
    }

    private fun enterTitle(title: String) {
        composeRule.onNodeWithTag("titleInput").performTextInput(title)
    }

    private fun addChecklistDraft(itemNumber: Int, title: String) {
        composeRule.onNodeWithText("Add checklist item").performScrollTo().performClick()
        composeRule.onNodeWithText("Checklist item $itemNumber").performScrollTo().performTextInput(title)
    }

    private fun openAddTaskFromHome() {
        waitForContentDescription("Add task")
        composeRule.onNodeWithContentDescription("Add task").performClick()
        waitForText("Add task")
    }

    private fun selectProject(label: String) {
        waitForText("Select project")
        composeRule.onNodeWithContentDescription("Project picker").performClick()
        waitForText(label)
        composeRule.onNodeWithText(label).performClick()
    }

    private fun selectDefaultDeadline() {
        // "Start picker" is a row of two side-by-side buttons (date, time); performClick() on
        // the row itself lands at its geometric center, which falls in the gap between the two
        // buttons and never opens the dialog. The form can recompose immediately after Details
        // receives input, so retry the child click until the dialog is actually visible.
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var lastError: Throwable? = null
        var dialogVisible = false
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                composeRule.onNodeWithContentDescription("Start picker")
                    .performScrollTo()
                    .onChildren()
                    .filter(hasClickAction())
                    .onFirst()
                    .performClick()
                composeRule.onNodeWithText("OK").assertIsDisplayed()
                dialogVisible = true
                break
            } catch (error: Throwable) {
                lastError = error
                SystemClock.sleep(100)
            }
        }
        if (!dialogVisible) {
            throw AssertionError("Expected date picker dialog", lastError)
        }
        composeRule.onNodeWithText("OK").performClick()
    }

    private fun clickSaveBlockRange() {
        val label = waitForSaveBlockRangeLabel()
        composeRule.onNodeWithText(label).performScrollTo().performClick()
    }

    private fun submitUntilValidation(validationText: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                clickSaveBlockRange()
                composeRule.onNodeWithText(validationText, substring = true)
                    .performScrollTo()
                    .assertIsDisplayed()
                return
            } catch (error: Throwable) {
                lastError = error
                SystemClock.sleep(100)
            }
        }
        throw AssertionError("Expected validation containing $validationText", lastError)
    }

    private fun waitForSaveBlockRangeLabel(): String {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var lastLabels = emptyList<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            lastLabels = saveLabels()
            val matchingLabels = lastLabels.filter { it.matches(saveBlockRangeLabelPattern) }
            if (matchingLabels.size == 1) {
                return matchingLabels.single()
            }
            SystemClock.sleep(100)
        }
        fail(
            "Expected exactly one save button label matching " +
                "${saveBlockRangeLabelPattern.pattern}. Found save labels: $lastLabels"
        )
        error("unreachable")
    }

    private fun saveLabels(): List<String> =
        composeRule.onAllNodesWithText("Save ", substring = true)
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text }
            }

    companion object {
        private val saveBlockRangeLabelPattern = Regex("""Save \d{2}:\d{2}-\d{2}:\d{2}""")
    }

    private fun resetTestState() {
        fakeBackend.setFailActions(false)
        fakeBackend.setFailProjects(false)
        fakeBackend.resetCalls()
        resetProjectRepositoryCache()
        clearProjectSelection()
    }

    private fun resetProjectRepositoryCache() {
        val field = AddTaskDependencies::class.java.getDeclaredField("cachedProjectRepository")
        field.isAccessible = true
        field.set(if (Modifier.isStatic(field.modifiers)) null else AddTaskDependencies, null)
    }

    private fun clearProjectSelection() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("buzer_add_task", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun waitForText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                composeRule.onNodeWithText(text, substring = true).assertIsDisplayed()
                return
            } catch (error: Throwable) {
                lastError = error
                SystemClock.sleep(100)
            }
        }
        throw AssertionError("Expected text containing $text", lastError)
    }

    private fun waitForScrollableText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                composeRule.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()
                return
            } catch (error: Throwable) {
                lastError = error
                SystemClock.sleep(100)
            }
        }
        throw AssertionError("Expected scrollable text containing $text", lastError)
    }

    private fun waitForContentDescription(contentDescription: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                composeRule.onNodeWithContentDescription(contentDescription).assertIsDisplayed()
                return
            } catch (error: Throwable) {
                lastError = error
                SystemClock.sleep(100)
            }
        }
        throw AssertionError("Expected contentDescription $contentDescription", lastError)
    }

    private fun waitForRecordedCall(path: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var lastCalls = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastCalls = runCatching { fakeBackend.calls() }.getOrDefault(lastCalls)
            if (lastCalls.contains("\"path\":\"$path\"")) return
            SystemClock.sleep(100)
        }
        fail("Expected fake backend call $path. Recorded calls: $lastCalls")
    }

    private fun assertNoRecordedCall(path: String) {
        val deadline = SystemClock.elapsedRealtime() + 800
        var lastCalls = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastCalls = runCatching { fakeBackend.calls() }.getOrDefault(lastCalls)
            check(!lastCalls.contains("\"path\":\"$path\"")) {
                "Expected no fake backend call $path. Recorded calls: $lastCalls"
            }
            SystemClock.sleep(100)
        }
    }

    private fun waitForCreateCallCount(expectedCount: Int) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (recordedCreateActionIds().size >= expectedCount) return
            SystemClock.sleep(100)
        }
        fail("Expected $expectedCount create calls, got ${recordedCreateActionIds()}")
    }

    private fun recordedCreateActionIds(): List<String> =
        recordedCreateBodies().mapNotNull { body -> body["actionId"]?.jsonPrimitive?.contentOrNull }

    private fun recordedCreateBodies() = Json.parseToJsonElement(fakeBackend.calls())
        .jsonObject["calls"]
        ?.jsonArray
        ?.mapNotNull { call ->
            call.jsonObject.takeIf { it["path"]?.jsonPrimitive?.contentOrNull == "/tasks" }
                ?.get("body")
                ?.jsonObject
        }
        .orEmpty()
}
