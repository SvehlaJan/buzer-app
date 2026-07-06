package app.buzer.android.addtask

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.buzer.android.MainActivity
import app.buzer.android.blockers.FakeBackendClient
import app.buzer.android.tasks.AddTaskDependencies
import java.lang.reflect.Modifier
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
            waitForText("Add task")
            composeRule.onNodeWithText("Add task").performClick()
            waitForText("Select project")
            composeRule.onNodeWithContentDescription("Title input").performTextInput("Buy milk")
            selectDefaultDeadline()
            composeRule.onNodeWithText("Save task").performClick()

            composeRule.onNodeWithText("Project is required").assertIsDisplayed()
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
            waitForText("Add task")
            composeRule.onNodeWithText("Add task").performClick()
            selectProject("Test Shared")
            composeRule.onNodeWithContentDescription("Title input").performTextInput("Buy milk")
            selectDefaultDeadline()
            composeRule.onNodeWithText("Save task").performClick()
            composeRule.onNodeWithText("Assignee is required for this project").assertIsDisplayed()

            composeRule.onNodeWithText("Me").performClick()
            composeRule.onNodeWithText("Save task").performClick()

            waitForRecordedCall("/tasks")
            fakeBackend.assertRecorded(
                "/tasks",
                "\"title\":\"Buy milk\"",
                "\"projectId\":\"project-test\"",
                "\"assigneeId\":\"user-me\"",
                "\"durationMinutes\":30",
                "dueDatetime"
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
            waitForText("Add task")
            composeRule.onNodeWithText("Add task").performClick()
            selectProject("Test Personal (personal)")
            composeRule.onAllNodesWithText("Me").assertCountEquals(0)
            composeRule.onNodeWithContentDescription("Title input").performTextInput("Personal task")
            selectDefaultDeadline()
            composeRule.onNodeWithText("Save task").performClick()

            waitForRecordedCall("/tasks")
            fakeBackend.assertRecorded(
                "/tasks",
                "\"title\":\"Personal task\"",
                "\"projectId\":\"project-personal\"",
                "\"durationMinutes\":30",
                "dueDatetime"
            )
            fakeBackend.assertRecordedWithout("/tasks", "assigneeId")
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
                waitForText("Add task")
                composeRule.onNodeWithText("Add task").performClick()
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
                waitForText("Add task")
                composeRule.onNodeWithText("Add task").performClick()
                selectProject("Test Shared")
                composeRule.onNodeWithContentDescription("Title input").performTextInput("Buy milk")
                composeRule.onNodeWithText("Me").performClick()
                selectDefaultDeadline()
                composeRule.onNodeWithText("Save task").performClick()

                waitForRecordedCall("/tasks")
                composeRule.onNodeWithText("Add committed task").assertIsDisplayed()
                composeRule.onNodeWithText("Buy milk").assertIsDisplayed()
                composeRule.onNodeWithText("500", substring = true).assertIsDisplayed()
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
            waitForText("Add task")
            composeRule.onNodeWithText("Add task").performClick()
            selectProject("Test Personal (personal)")
            composeRule.onNodeWithText("Back").performClick()
        } finally {
            firstScenario.close()
        }

        val secondScenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitForText("Add task")
            composeRule.onNodeWithText("Add task").performClick()
            waitForText("Test Personal")
            composeRule.onNodeWithText("Test Personal").assertIsDisplayed()
        } finally {
            secondScenario.close()
        }
    }

    private fun selectProject(label: String) {
        waitForText("Select project")
        composeRule.onNodeWithContentDescription("Project picker").performClick()
        waitForText(label)
        composeRule.onNodeWithText(label).performClick()
    }

    private fun selectDefaultDeadline() {
        composeRule.onNodeWithContentDescription("Start time picker").performClick()
        waitForText("OK")
        composeRule.onNodeWithText("OK").performClick()
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
}
