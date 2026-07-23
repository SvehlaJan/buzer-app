package app.buzer.android.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.theme.BuzerTheme
import app.buzer.shared.model.ActivityLogEntry
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.ui.blockers.BlockerKind
import app.buzer.shared.ui.blockers.buildBlockerUiModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DeadlineBlockerUpdateHostTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rescheduleRecoveryRendersChecklistBeforeActivityAndEnablesDirectChecklistControls() {
        val task = task()
        composeRule.setContent {
            BuzerTheme {
                DeadlineBlockerScreen(
                    onBack = {},
                    task = task,
                    onUpdate = { CoordinatorActionResult.Success },
                    occurrenceKey = task.occurrenceKey,
                    model = buildBlockerUiModel(BlockerKind.Deadline, task),
                    loadProjects = { listOf(project) },
                    refreshProjects = { listOf(project) }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Reschedule action").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Reschedule task").fetchSemanticsNodes().isNotEmpty()
        }

        val checklist = composeRule
            .onNodeWithContentDescription("Checklist: Checklist · 0/1 done")
            .fetchSemanticsNode()
        val activity = composeRule.onNodeWithText("Activity").fetchSemanticsNode()

        assertTrue(checklist.boundsInRoot.top < activity.boundsInRoot.top)

        composeRule.onNodeWithText("Checklist").performScrollTo().performClick()
        composeRule.onNodeWithText("Existing checklist").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Remove item").performScrollTo().assertIsEnabled()
    }

    private fun task() = TaskSnapshotItem(
        id = "deadline-task",
        todoistTaskId = "todoist-deadline-task",
        title = "Finish the report",
        description = "Include the final budget table.",
        projectId = project.id,
        assigneeId = currentUser.id,
        creatorId = currentUser.id,
        dueDatetime = "2026-07-24T09:00:00",
        dueDate = null,
        durationMinutes = 30,
        startAt = null,
        state = CommitmentState.planned,
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
        occurrenceKey = "deadline-occurrence",
        taskRevision = "deadline-revision",
        activityLogEntries = listOf(
            ActivityLogEntry(
                timestamp = "2026-07-23T08:00:00Z",
                timeZone = "UTC",
                message = "Deadline committed"
            )
        ),
        checklistItems = listOf(
            ChecklistItem(
                id = "child-1",
                title = "Check budget table",
                description = null,
                checked = false,
                order = 1,
                childRevision = "child-revision"
            )
        )
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
