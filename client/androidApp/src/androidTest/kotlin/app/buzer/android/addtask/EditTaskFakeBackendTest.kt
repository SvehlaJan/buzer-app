package app.buzer.android.addtask

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.buzer.android.MainActivity
import app.buzer.android.blockers.FakeBackendClient
import app.buzer.android.tasks.AddTaskDependencies
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditTaskFakeBackendTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val fakeBackend = FakeBackendClient()

    @Test
    fun editTask_projectAndAssigneeStayDraftOnlyUntilOneFullRootUpdate() {
        resetTestState()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            openEditorFor("One-time empty checklist")
            selectAssignee("Me", "Wife")
            waitForContentDescription("Assignee: Wife")
            selectProject("Test Shared", "Test Personal")
            waitForContentDescription("Project: Test Personal")
            waitForContentDescription("Assignee: Me")
            assertNoRecordedCall("/tasks/task-one-time-empty/update")

            clickSaveBlockRange()
            waitForRecordedCall("/tasks/task-one-time-empty/update")

            val payloads = fullUpdatePayloads()
            assertEquals(1, payloads.size)
            val rootPatch = payloads.single().getValue("rootPatch").jsonObject
            assertTrue(rootPatch.keys.containsAll(setOf(
                "expectedTaskRevision",
                "title",
                "description",
                "projectId",
                "assigneeId",
                "dueDatetime",
                "durationMinutes"
            )))
            assertEquals("project-personal", rootPatch["projectId"]?.jsonPrimitive?.contentOrNull)
            assertEquals(null, rootPatch["assigneeId"]?.jsonPrimitive?.contentOrNull)
            assertTrue(rootPatch["dueDatetime"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true)
            assertTrue((rootPatch["durationMinutes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) > 0)
            assertTrue(payloads.single().getValue("childMutations").jsonArray.isEmpty())
        } finally {
            scenario.close()
        }
    }

    @Test
    fun editTask_retryPreservesTheCapturedRootRequestAndDirtyExitConfirmsDiscard() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                openEditorFor("One-time empty checklist")
                composeRule.onNodeWithContentDescription("Task title")
                    .performTextInput(" updated")
                clickSaveBlockRange()
                waitForRecordedCall("/tasks/task-one-time-empty/update")
                waitForText("Task update")
                composeRule.onNodeWithText("Retry").performClick()
                waitForUpdateCallCount(2)
                val retriedPayloads = fullUpdatePayloads()
                assertEquals(1, retriedPayloads.map { it.getValue("actionId").jsonPrimitive.contentOrNull }.distinct().size)
                assertEquals(retriedPayloads.first().getValue("rootPatch"), retriedPayloads.last().getValue("rootPatch"))
                assertEquals(retriedPayloads.first().getValue("childMutations"), retriedPayloads.last().getValue("childMutations"))

                composeRule.onNodeWithText("Dismiss").performClick()
                composeRule.onNodeWithText("Cancel").performClick()
                waitForText("Discard changes?")
                composeRule.onNodeWithText("Keep editing").performClick()
                composeRule.onNodeWithText("Edit task").assertIsDisplayed()
                pressBack()
                waitForText("Discard changes?")
                composeRule.onNodeWithText("Keep editing").performClick()
                composeRule.onNodeWithText("Edit task").assertIsDisplayed()
            } finally {
                scenario.close()
            }
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    private fun openEditorFor(title: String) {
        waitForText(title)
        composeRule.onNodeWithText(title).performClick()
        waitForText("Edit task")
    }

    private fun selectProject(current: String, next: String) {
        composeRule.onNodeWithContentDescription("Project: $current")
            .performScrollTo()
            .performClick()
        waitForText(next)
        composeRule.onNodeWithText(next).performClick()
    }

    private fun selectAssignee(current: String, next: String) {
        composeRule.onNodeWithContentDescription("Assignee: $current")
            .performScrollTo()
            .performClick()
        waitForText(next)
        composeRule.onNodeWithText(next).performClick()
    }

    private fun clickSaveBlockRange() {
        val matcher = Regex("""Save \d{2}:\d{2}–\d{2}:\d{2}""")
        var label = ""
        composeRule.waitUntil(timeoutMillis = 15_000) {
            label = composeRule.onAllNodesWithText("Save ", substring = true)
                .fetchSemanticsNodes()
                .flatMap { node -> node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty() }
                .map { it.text }
                .firstOrNull(matcher::matches)
                .orEmpty()
            label.isNotEmpty()
        }
        composeRule.onNodeWithText(label).performClick()
    }

    private fun fullUpdatePayloads() = Json.parseToJsonElement(fakeBackend.fullCalls())
        .jsonObject.getValue("calls")
        .jsonArray
        .mapNotNull { call ->
            call.jsonObject.takeIf {
                it["path"]?.jsonPrimitive?.contentOrNull == "/tasks/task-one-time-empty/update"
            }?.get("payload")?.jsonObject
        }

    private fun resetTestState() {
        File(ApplicationProvider.getApplicationContext<Context>().filesDir, "latest_snapshot.json").delete()
        fakeBackend.setFailActions(false)
        fakeBackend.setFailProjects(false)
        fakeBackend.resetCalls()
        val field = AddTaskDependencies::class.java.getDeclaredField("cachedProjectRepository")
        field.isAccessible = true
        field.set(if (Modifier.isStatic(field.modifiers)) null else AddTaskDependencies, null)
    }

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

    private fun waitForRecordedCall(path: String) {
        waitUntil("Expected fake backend call $path") {
            fakeBackend.calls().contains("\"path\":\"$path\"")
        }
    }

    private fun assertNoRecordedCall(path: String) {
        val deadline = SystemClock.elapsedRealtime() + 800
        while (SystemClock.elapsedRealtime() < deadline) {
            check(!fakeBackend.calls().contains("\"path\":\"$path\"")) {
                "Expected no fake backend call $path"
            }
            SystemClock.sleep(100)
        }
    }

    private fun waitForUpdateCallCount(expectedCount: Int) {
        waitUntil("Expected $expectedCount update calls") { fullUpdatePayloads().size >= expectedCount }
    }

    private fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching(predicate).getOrDefault(false)) return
            SystemClock.sleep(100)
        }
        fail(message + "\nCalls: " + runCatching { fakeBackend.fullCalls() }.getOrDefault("unavailable"))
    }
}
