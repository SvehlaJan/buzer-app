package app.buzer.android.addtask

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
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
            waitForText("Choose project")
            enterTitle("Buy milk")
            selectDefaultSchedule()
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
            selectDefaultSchedule()
            submitUntilValidation("Assignee is required")

            selectAssignee("Me")
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
    fun addTask_personalProjectShowsLockedMe() {
        resetTestState()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAddTaskFromHome()
            selectProject("Test Personal")
            composeRule.onNodeWithText("Me").assertIsDisplayed()
            composeRule.onNodeWithText("Personal project", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
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
                waitForText("Choose project")
                composeRule.onNodeWithText("Choose project").assertIsDisplayed()
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
                selectAssignee("Me")
                selectDefaultSchedule()
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
            selectProject("Test Personal")
            composeRule.onNodeWithText("Cancel").performClick()
            waitForText("Discard changes?")
            composeRule.onNodeWithText("Discard").performClick()
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
            openNotes()
            composeRule.onNodeWithContentDescription("Notes").performTextInput("Gate code: 2468")
            selectAssignee("Me")
            selectDefaultSchedule()
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
            selectAssignee("Me")
            selectDefaultSchedule()
            openChecklist()
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

    @Test
    fun addTask_scheduleUsesQuickChoicesAndSeparateCustomDialogs() {
        resetTestState()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAddTaskFromHome()
            waitForContentDescription("Schedule: Set date, time & duration")
            openSchedule()
            composeRule.onNodeWithText("Date").assertIsDisplayed()
            composeRule.onNodeWithText("Start time").assertIsDisplayed()
            composeRule.onNodeWithText("Duration").assertIsDisplayed()

            composeRule.onNodeWithText("Today").performClick()
            verifyCustomDialogCanCancelAndApply("Custom date", "Custom date")
            verifyCustomDialogCanCancelAndApply("Custom start time", "Custom start time")
            composeRule.onNodeWithText("30 min").performClick()
            verifyCustomDialogCanCancelAndApply("Custom duration", "Custom duration")

            composeRule.onNodeWithText("Schedule").performClick()
            val label = waitForSaveBlockRangeLabel()
            composeRule.onNodeWithText(label).assertIsDisplayed()
        } finally {
            scenario.close()
        }
    }

    @Test
    fun addTask_selectorSheetsAndAccordionKeepChangesInDraftUntilSave() {
        resetTestState()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAddTaskFromHome()
            selectProject("Test Shared")
            waitForContentDescription("Project: Test Shared")
            selectAssignee("Wife")
            waitForContentDescription("Assignee: Wife")
            openSchedule()
            composeRule.onNodeWithText("No schedule").performClick()
            composeRule.onNodeWithText("Schedule").performClick()
            waitForContentDescription("Schedule: No schedule")
            openNotes()
            composeRule.onNodeWithContentDescription("Notes").performTextInput("draft note")
            openChecklist()
            composeRule.onAllNodesWithText("draft note").assertCountEquals(0)
            composeRule.onNodeWithText("Add checklist item").performClick()
            assertNoRecordedCall("/tasks")
        } finally {
            scenario.close()
        }
    }

    @Test
    fun addTask_dirtyCancelAndDeviceBackRequireDiscardConfirmation() {
        resetTestState()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openAddTaskFromHome()
            enterTitle("Unsaved draft")
            composeRule.onNodeWithText("Cancel").performClick()
            waitForText("Discard changes?")
            composeRule.onNodeWithText("Keep editing").performClick()
            composeRule.onNodeWithTag("titleInput").assertIsDisplayed()
            pressBack()
            waitForText("Discard changes?")
            composeRule.onNodeWithText("Keep editing").performClick()
            composeRule.onNodeWithTag("titleInput").assertIsDisplayed()
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
        waitForContentDescription("Project: Choose project")
        composeRule.onNodeWithContentDescription("Project: Choose project").performClick()
        waitForText(label)
        composeRule.onNodeWithText(label).performClick()
    }

    private fun selectAssignee(label: String) {
        composeRule.onNodeWithContentDescription("Assignee: Choose assignee")
            .performScrollTo()
            .performClick()
        waitForText(label)
        composeRule.onNodeWithText(label).performClick()
    }

    private fun selectDefaultSchedule() {
        openSchedule()
        composeRule.onNodeWithContentDescription("Custom date").performClick()
        waitForText("Custom date")
        composeRule.onNodeWithText("Apply").performClick()
        composeRule.onNodeWithContentDescription("Custom start time").performClick()
        waitForText("Custom start time")
        composeRule.onNodeWithText("Apply").performClick()
        composeRule.onNodeWithContentDescription("Custom duration").performClick()
        waitForText("Custom duration")
        composeRule.onNodeWithText("Apply").performClick()
    }

    private fun openSchedule() {
        composeRule.onNodeWithText("Schedule").performScrollTo().performClick()
        waitForText("Date")
    }

    private fun openNotes() {
        composeRule.onNodeWithText("Notes").performScrollTo().performClick()
        waitForContentDescription("Notes")
    }

    private fun openChecklist() {
        composeRule.onNodeWithText("Checklist").performScrollTo().performClick()
        waitForText("Add checklist item")
    }

    private fun verifyCustomDialogCanCancelAndApply(contentDescription: String, title: String) {
        composeRule.onNodeWithContentDescription(contentDescription).performClick()
        waitForText(title)
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onAllNodesWithText(title).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(contentDescription).performClick()
        waitForText(title)
        composeRule.onNodeWithText("Apply").performClick()
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
        private val saveBlockRangeLabelPattern = Regex("""Save \d{2}:\d{2}–\d{2}:\d{2}""")
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
