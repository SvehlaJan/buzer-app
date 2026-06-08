package app.buzer.android.addtask

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.buzer.android.MainActivity
import app.buzer.android.blockers.FakeBackendClient
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
    fun addTask_createsTaskThroughFakeBackend() {
        resetTestState()

        ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNodeWithText("Add task").performClick()
        composeRule.onNodeWithContentDescription("Title input").performTextInput("Buy milk")
        composeRule.onNodeWithText("Me").performClick()
        selectDefaultDeadline()
        composeRule.onNodeWithContentDescription("Duration minutes input").performTextInput("45")
        composeRule.onNodeWithText("Save").performClick()

        waitForRecordedCall("/tasks")
        fakeBackend.assertRecorded(
            "/tasks",
            "Buy milk",
            "user-me",
            "durationMinutes",
            "45",
            "dueDatetime"
        )
    }

    @Test
    fun addTask_backendFailureKeepsFormAndEnteredTitle() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            ActivityScenario.launch(MainActivity::class.java)

            composeRule.onNodeWithText("Add task").performClick()
            composeRule.onNodeWithContentDescription("Title input").performTextInput("Buy milk")
            composeRule.onNodeWithText("Me").performClick()
            selectDefaultDeadline()
            composeRule.onNodeWithContentDescription("Duration minutes input").performTextInput("45")
            composeRule.onNodeWithText("Save").performClick()

            waitForRecordedCall("/tasks")
            composeRule.onNodeWithText("Add committed task").assertIsDisplayed()
            composeRule.onNodeWithText("Buy milk").assertIsDisplayed()
            composeRule.onNodeWithText("500", substring = true).assertIsDisplayed()
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    private fun selectDefaultDeadline() {
        composeRule.onNodeWithContentDescription("Deadline picker").performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.onNodeWithText("OK").performClick()
    }

    private fun resetTestState() {
        fakeBackend.setFailActions(false)
        fakeBackend.resetCalls()
    }

    private fun waitForRecordedCall(path: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var lastCalls = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastCalls = runCatching { fakeBackend.calls() }.getOrDefault(lastCalls)
            if (lastCalls.contains("\"path\":\"$path\"")) return
            SystemClock.sleep(100)
        }
        fail("Expected fake backend call $path. Recorded calls: $lastCalls")
    }
}
