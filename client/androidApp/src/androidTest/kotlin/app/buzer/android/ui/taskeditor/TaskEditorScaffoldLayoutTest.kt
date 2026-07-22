package app.buzer.android.ui.taskeditor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.buzer.android.ui.theme.BuzerTheme
import app.buzer.shared.model.ProjectCollaborator
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TaskEditorScaffoldLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun scheduledSaveActionHasTheSameWidthAsCancel() {
        val currentUser = ProjectCollaborator(
            id = "me",
            displayName = "Me",
            isCurrentUser = true
        )
        val draft = TaskEditorDraft(
            title = "Review finances",
            assignee = currentUser,
            schedule = TaskEditorSchedule(
                date = LocalDate.of(2026, 7, 23),
                hour = 20,
                minute = 45,
                durationMinutes = 30
            )
        )
        val saveLabel = saveActionLabel(draft)

        composeRule.setContent {
            BuzerTheme {
                TaskEditorScaffold(
                    screenTitle = "Add task",
                    title = draft.title,
                    onTitleChange = {},
                    draft = draft,
                    now = LocalDateTime.of(2026, 7, 22, 20, 40),
                    projects = emptyList(),
                    currentUser = currentUser,
                    checklistDoneCount = 0,
                    checklistTotalCount = 0,
                    checklistContent = {},
                    onScheduleChange = {},
                    onNoSchedule = {},
                    onProjectSelected = {},
                    onAssigneeSelected = {},
                    onRecurrencePresetSelected = {},
                    onDescriptionChange = {},
                    onRetryCollaborators = {},
                    onSave = {},
                    onBack = {},
                    onCancel = {}
                )
            }
        }

        val cancelWidth = composeRule.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot.width
        val saveWidth = composeRule.onNodeWithText(saveLabel).fetchSemanticsNode().boundsInRoot.width

        assertEquals(cancelWidth, saveWidth, 1f)
    }
}
