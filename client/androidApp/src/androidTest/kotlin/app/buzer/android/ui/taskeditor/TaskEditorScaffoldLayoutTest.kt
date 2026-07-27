package app.buzer.android.ui.taskeditor

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
    fun invalidUpdateShowsScheduleFirstWithSharedValidationAndFieldError() {
        val currentUser = currentUser()
        val draft = TaskEditorDraft(
            title = "",
            assignee = currentUser
        )
        val validation = TaskEditorValidation(
            errors = mapOf(
                TaskEditorField.Title to "Title is required",
                TaskEditorField.Schedule to "Start time is required"
            )
        ).visible(
            exposure = ValidationExposure.OnEntry,
            touchedFields = emptySet(),
            submitAttempted = false
        )

        composeRule.setContent {
            BuzerTheme {
                TaskEditorScaffold(
                    presentation = TaskEditorPresentation(
                        screenTitle = "Repair task",
                        primaryVerb = "Repair"
                    ),
                    draft = draft,
                    now = now(),
                    projects = emptyList(),
                    currentUser = currentUser,
                    checklistDoneCount = 0,
                    checklistTotalCount = 0,
                    checklistContent = {},
                    onDraftChange = {},
                    onRetryCollaborators = {},
                    onPrimaryAction = {},
                    onCancel = {},
                    validation = validation
                )
            }
        }

        val schedule = composeRule
            .onNodeWithContentDescription("Schedule: Set date, time & duration")
            .fetchSemanticsNode()
        val project = composeRule
            .onNodeWithContentDescription("Project: Choose project")
            .fetchSemanticsNode()

        assertEquals(true, schedule.boundsInRoot.top < project.boundsInRoot.top)
        composeRule.onNodeWithText("Fix the highlighted fields").assertIsDisplayed()
        composeRule.onNodeWithText("Start time is required").assertIsDisplayed()
        composeRule.onNodeWithText("Repair").assertIsNotEnabled()
    }

    @Test
    fun longUpdateActionHasTheSameWidthAsCancel() {
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
        composeRule.setContent {
            BuzerTheme {
                TaskEditorScaffold(
                    presentation = TaskEditorPresentation(
                        screenTitle = "Repair task",
                        primaryVerb = "Repair"
                    ),
                    draft = draft,
                    now = LocalDateTime.of(2026, 7, 22, 20, 40),
                    projects = emptyList(),
                    currentUser = currentUser,
                    checklistDoneCount = 0,
                    checklistTotalCount = 0,
                    checklistContent = {},
                    onDraftChange = {},
                    onRetryCollaborators = {},
                    onPrimaryAction = {},
                    onCancel = {},
                    validation = validValidation()
                )
            }
        }

        val cancelWidth = composeRule.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot.width
        val saveWidth = composeRule.onNodeWithText("Repair 20:45–21:15").fetchSemanticsNode().boundsInRoot.width

        assertEquals(cancelWidth, saveWidth, 1f)
    }

    @Test
    fun quickScheduleChoiceSendsTheUpdatedDraftToTheHost() {
        val currentUser = currentUser()
        val draft = TaskEditorDraft(title = "Review", assignee = currentUser)
        val updatedDraft = mutableStateOf<TaskEditorDraft?>(null)

        composeRule.setContent {
            BuzerTheme {
                TaskEditorScaffold(
                    presentation = TaskEditorPresentation("Add task", "Add"),
                    draft = draft,
                    now = now(),
                    projects = emptyList(),
                    currentUser = currentUser,
                    checklistDoneCount = 0,
                    checklistTotalCount = 0,
                    checklistContent = {},
                    onDraftChange = { updatedDraft.value = it },
                    onRetryCollaborators = {},
                    onPrimaryAction = {},
                    onCancel = {},
                    validation = validValidation()
                )
            }
        }

        composeRule.onNodeWithContentDescription("Schedule: Set date, time & duration").performClick()
        composeRule.onNodeWithText("Today").performClick()

        composeRule.runOnIdle {
            assertEquals(now().toLocalDate(), updatedDraft.value?.schedule?.date)
        }
    }

    @Test
    fun customSchedulePickerAppliesThroughTheHostDraftCallback() {
        val currentUser = currentUser()
        val draft = TaskEditorDraft(title = "Review", assignee = currentUser)
        val updatedDraft = mutableStateOf<TaskEditorDraft?>(null)

        composeRule.setContent {
            BuzerTheme {
                TaskEditorScaffold(
                    presentation = TaskEditorPresentation("Add task", "Add"),
                    draft = draft,
                    now = now(),
                    projects = emptyList(),
                    currentUser = currentUser,
                    checklistDoneCount = 0,
                    checklistTotalCount = 0,
                    checklistContent = {},
                    onDraftChange = { updatedDraft.value = it },
                    onRetryCollaborators = {},
                    onPrimaryAction = {},
                    onCancel = {},
                    validation = validValidation()
                )
            }
        }

        composeRule.onNodeWithContentDescription("Schedule: Set date, time & duration").performClick()
        composeRule.onNodeWithContentDescription("Custom date").performClick()
        composeRule.onNodeWithText("Custom date").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Custom date dialog apply").performClick()

        composeRule.runOnIdle {
            assertEquals(now().toLocalDate(), updatedDraft.value?.schedule?.date)
        }
    }

    @Test
    fun updateFooterFollowsChecklist() {
        val currentUser = currentUser()

        composeRule.setContent {
            BuzerTheme {
                TaskEditorScaffold(
                    presentation = TaskEditorPresentation(
                        screenTitle = "Edit task",
                        primaryVerb = "Update",
                        contextMessage = "This task needs attention"
                    ),
                    draft = TaskEditorDraft(title = "Review", assignee = currentUser),
                    now = now(),
                    projects = emptyList(),
                    currentUser = currentUser,
                    checklistDoneCount = 0,
                    checklistTotalCount = 0,
                    checklistContent = {},
                    footerContent = { Text("Activity log") },
                    onDraftChange = {},
                    onRetryCollaborators = {},
                    onPrimaryAction = {},
                    onCancel = {},
                    validation = validValidation()
                )
            }
        }

        val checklist = composeRule
            .onNodeWithContentDescription("Checklist: Checklist · 0/0 done")
            .fetchSemanticsNode()
        val footer = composeRule.onNodeWithText("Activity log").fetchSemanticsNode()

        composeRule.onNodeWithText("This task needs attention").assertIsDisplayed()
        assertEquals(true, checklist.boundsInRoot.top < footer.boundsInRoot.top)
        composeRule.onNodeWithText("Activity log").performScrollTo().assertIsDisplayed()
    }

    private fun currentUser() = ProjectCollaborator(
        id = "me",
        displayName = "Me",
        isCurrentUser = true
    )

    private fun now() = LocalDateTime.of(2026, 7, 22, 20, 40)

    private fun validValidation() = VisibleTaskEditorValidation(
        errors = emptyMap(),
        unavailableFields = emptySet(),
        canSubmit = true,
        bannerMessage = null
    )
}
