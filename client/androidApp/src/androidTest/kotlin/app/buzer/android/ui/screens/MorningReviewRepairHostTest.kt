package app.buzer.android.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.theme.BuzerTheme
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.TaskSnapshotItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MorningReviewRepairHostTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun successfulRepairsUseTheHostCallbackToReturnToRemainingReviewThenFinishLastTask() {
        val first = task(id = "first", title = "First repair")
        val last = task(id = "last", title = "Last repair")
        val invalidTasks = mutableStateOf(listOf(first, last))
        val updateTaskIds = mutableListOf<String>()
        val finishCalled = mutableStateOf(false)

        composeRule.setContent {
            BuzerTheme {
                MorningReviewScreen(
                    invalidTasks = invalidTasks.value,
                    onBack = {},
                    onRepair = { taskId, _ ->
                        updateTaskIds += taskId
                        CoordinatorActionResult.Success
                    },
                    onRepairSuccess = { repairedTaskId ->
                        val completion = completeMorningReviewRepair(invalidTasks.value, repairedTaskId)
                        invalidTasks.value = completion.remainingInvalidTasks
                        if (completion.shouldFinish) finishCalled.value = true
                    },
                    loadProjects = { listOf(project) },
                    refreshProjects = { listOf(project) }
                )
            }
        }

        composeRule.onNodeWithTag("morning-repair-first").performClick()
        clickRepairAction()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("morning-repair-last").assertIsDisplayed()
            }.isSuccess
        }
        assertEquals(listOf("first"), updateTaskIds)
        assertEquals(listOf(last), invalidTasks.value)
        assertFalse(finishCalled.value)

        composeRule.onNodeWithTag("morning-repair-last").performClick()
        clickRepairAction()

        composeRule.waitUntil(timeoutMillis = 5_000) { finishCalled.value }
        assertEquals(listOf("first", "last"), updateTaskIds)
        assertTrue(invalidTasks.value.isEmpty())
    }

    @Test
    fun cancellingRepairReturnsToReviewWithoutInvokingUpdate() {
        val task = task(id = "only", title = "Only repair")
        val updateTaskIds = mutableListOf<String>()

        composeRule.setContent {
            BuzerTheme {
                MorningReviewScreen(
                    invalidTasks = listOf(task),
                    onBack = {},
                    onRepair = { taskId, _ ->
                        updateTaskIds += taskId
                        CoordinatorActionResult.Success
                    },
                    loadProjects = { listOf(project) },
                    refreshProjects = { listOf(project) }
                )
            }
        }

        composeRule.onNodeWithTag("morning-repair-only").performClick()
        composeRule.onNodeWithText("Repair 09:00–09:30").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("morning-repair-only").assertIsDisplayed()
            }.isSuccess
        }
        assertTrue(updateTaskIds.isEmpty())
    }

    private fun clickRepairAction() {
        composeRule.onNodeWithText("Repair 09:00–09:30").performScrollTo().performClick()
    }

    private fun task(id: String, title: String) = TaskSnapshotItem(
        id = id,
        todoistTaskId = "todoist-$id",
        title = title,
        description = "Repair this commitment",
        projectId = project.id,
        assigneeId = currentUser.id,
        creatorId = currentUser.id,
        dueDatetime = "2026-07-24T09:00:00",
        dueDate = null,
        durationMinutes = 30,
        startAt = null,
        state = CommitmentState.invalid,
        isAssignedToUser = true,
        isCreatedByUser = true,
        isCompleted = false,
        isDeleted = false,
        validationErrors = emptyList(),
        startedAt = null,
        startDismissCount = 0,
        deadlineExtensionCount = 0,
        escalationSentAt = null,
        updatedAt = "2026-07-23T08:00:00Z",
        occurrenceKey = "occurrence-$id",
        taskRevision = "revision-$id"
    )

    private companion object {
        val currentUser = ProjectCollaborator(
            id = "me",
            displayName = "Me",
            isCurrentUser = true
        )
        val project = ProjectItem(
            id = "project",
            name = "Personal",
            canAssignTasks = false,
            shared = false,
            isInboxProject = false,
            collaborators = listOf(currentUser),
            collaboratorsReady = true
        )
    }
}
