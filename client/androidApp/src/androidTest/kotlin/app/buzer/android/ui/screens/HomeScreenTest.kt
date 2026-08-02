package app.buzer.android.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.buzer.android.ui.theme.BuzerTheme
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.ui.home.HomeAgendaRow
import app.buzer.shared.ui.home.HomeAgendaRowState
import app.buzer.shared.ui.home.HomeAttention
import app.buzer.shared.ui.home.HomeAttentionAction
import app.buzer.shared.ui.home.HomeCurrentBlock
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fabStackRendersAddTaskOnlyAndNotMorningReview() {
        var addTaskClicked = false
        val state = buildEmptyReadyState()

        composeRule.setContent {
            BuzerTheme {
                HomeScreen(
                    state = state,
                    onAddTask = { addTaskClicked = true }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Add task").assertIsDisplayed().performClick()
        assertTrue(addTaskClicked)

        composeRule.onNodeWithContentDescription("Morning review").assertDoesNotExist()
    }

    @Test
    fun morningReviewOpensFromReviewAttentionAction() {
        var morningReviewOpened = false
        val state = buildEmptyReadyState().copy(
            reviewAttention = HomeAttention(
                message = "1 invalid task needs review",
                action = HomeAttentionAction.OpenMorningReview
            )
        )

        composeRule.setContent {
            BuzerTheme {
                HomeScreen(
                    state = state,
                    onOpenMorningReview = { morningReviewOpened = true }
                )
            }
        }

        composeRule.onNodeWithText("1 invalid task needs review").assertIsDisplayed()
        composeRule.onNodeWithText("Review").assertIsDisplayed().performClick()
        assertTrue(morningReviewOpened)
    }

    @Test
    fun heroBlockRendersChecklistEditAndDone() {
        var doneClickedTaskId: String? = null
        var editClickedTaskId: String? = null
        var toggledChecklist: Pair<String, String>? = null

        val currentBlock = HomeCurrentBlock(
            taskId = "hero-1",
            title = "Focus on critical task",
            blockWindow = "09:00-10:00",
            remainingLabel = "45m remaining",
            description = "Detailed hero description",
            doneContentDescription = "Finish current task",
            checkedCount = 0,
            totalCount = 1,
            nextUncheckedChildTitle = "Subtask 1",
            checklistItems = listOf(
                ChecklistItem(
                    id = "child-1",
                    title = "Subtask 1",
                    description = null,
                    checked = false,
                    order = 1,
                    childRevision = "rev-1"
                )
            ),
            doneEnabled = true,
            extendEnabled = true
        )
        val state = buildEmptyReadyState().copy(
            currentBlock = currentBlock,
            allClear = false
        )

        composeRule.setContent {
            BuzerTheme {
                HomeScreen(
                    state = state,
                    onDoneTask = { doneClickedTaskId = it },
                    onEditTask = { editClickedTaskId = it },
                    onToggleChecklistItem = { taskId, itemId -> toggledChecklist = Pair(taskId, itemId) }
                )
            }
        }

        composeRule.onNodeWithText("45m remaining").assertIsDisplayed()
        composeRule.onNodeWithText("Focus on critical task").assertIsDisplayed()
        composeRule.onNodeWithText("Subtask 1").assertIsDisplayed()

        // Toggle checklist
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.Checkbox
            ).and(hasAnySibling(hasText("Subtask 1")))
        ).performClick()
        assertEquals(Pair("hero-1", "child-1"), toggledChecklist)

        // Click Edit
        composeRule.onNodeWithText("Edit").assertIsDisplayed().performClick()
        assertEquals("hero-1", editClickedTaskId)

        // Click Done
        composeRule.onNodeWithContentDescription("Finish current task").assertIsDisplayed().performClick()
        assertEquals("hero-1", doneClickedTaskId)
    }

    @Test
    fun renderOrderHeaderOverdueUnassignedFutureAndAllClear() {
        val overdueRow = HomeAgendaRow(
            taskId = "overdue-1",
            timeLabel = "08:00-08:30",
            title = "Overdue task",
            state = HomeAgendaRowState.Overdue,
            subline = "Overdue - ended 1h ago",
            description = "Overdue description",
            checkedCount = 0,
            totalCount = 0,
            nextUncheckedChildTitle = null,
            doneEnabled = true,
            pendingLabel = null,
            actionAvailable = true
        )
        val unassignedRow = HomeAgendaRow(
            taskId = "unassigned-1",
            timeLabel = "",
            title = "Unassigned task",
            state = HomeAgendaRowState.Unassigned,
            subline = "Unassigned",
            description = null,
            checkedCount = 0,
            totalCount = 0,
            nextUncheckedChildTitle = null,
            doneEnabled = false,
            pendingLabel = null,
            actionAvailable = true
        )
        val state = buildEmptyReadyState().copy(
            weekdayLabel = "Sunday",
            dateLabel = "August 2",
            updatedLabel = "Updated 18:00",
            overdue = HomeExpandableSection(totalCount = 1, rows = listOf(overdueRow), expanded = true),
            unassigned = HomeExpandableSection(totalCount = 1, rows = listOf(unassignedRow), expanded = true),
            futureGroups = listOf(
                HomeDateGroup(
                    date = LocalDate.parse("2026-08-03"),
                    heading = "Tomorrow · Monday, August 3",
                    rows = listOf(
                        HomeAgendaRow(
                            taskId = "future-1",
                            timeLabel = "09:00-10:00",
                            title = "Future task",
                            state = HomeAgendaRowState.Upcoming,
                            subline = "Upcoming - starts in 1d",
                            description = null,
                            checkedCount = 0,
                            totalCount = 0,
                            nextUncheckedChildTitle = null,
                            doneEnabled = true,
                            pendingLabel = null,
                            actionAvailable = true
                        )
                    )
                )
            ),
            allClear = false
        )

        composeRule.setContent {
            BuzerTheme {
                HomeScreen(state = state)
            }
        }

        composeRule.onNodeWithText("Sunday").assertIsDisplayed()
        composeRule.onNodeWithText("August 2").assertIsDisplayed()
        composeRule.onNodeWithText("Updated 18:00").assertIsDisplayed()

        composeRule.onNodeWithText("Overdue (1)").assertIsDisplayed()
        composeRule.onNodeWithText("Overdue task").assertIsDisplayed()

        composeRule.onNodeWithText("Unassigned (1)").assertIsDisplayed()
        composeRule.onNodeWithText("Unassigned task").assertIsDisplayed()
        composeRule.onNodeWithText("Assign").assertIsDisplayed()

        composeRule.onNodeWithText("Tomorrow · Monday, August 3").assertIsDisplayed()
        composeRule.onNodeWithText("Future task").assertIsDisplayed()
    }

    @Test
    fun allClearStateRendersWhenAllClearIsTrue() {
        val state = buildEmptyReadyState().copy(allClear = true)

        composeRule.setContent {
            BuzerTheme {
                HomeScreen(state = state)
            }
        }

        composeRule.onNodeWithText("No active or upcoming commitments.").assertIsDisplayed()
    }

    @Test
    fun refreshTasksAccessibilityActionTriggersRefresh() {
        var refreshed = false
        val state = buildEmptyReadyState()

        composeRule.setContent {
            BuzerTheme {
                HomeScreen(
                    state = state,
                    onRefreshTasks = { refreshed = true }
                )
            }
        }

        val node = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
        )
        node.assertIsDisplayed()

        val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val refreshAction = actions.firstOrNull { it.label == "Refresh tasks" }
        assertTrue(refreshAction != null)

        val result = refreshAction!!.action()
        assertTrue(result)
        assertTrue(refreshed)
    }

    @Test
    fun agendaActionsRouteTheStableTaskIdForEachSection() {
        var doneTaskId: String? = null
        var editTaskId: String? = null
        val overdue = agendaRow("overdue-1", "Overdue task", HomeAgendaRowState.Overdue)
        val unassigned = agendaRow("unassigned-1", "Unassigned task", HomeAgendaRowState.Unassigned)
        val future = agendaRow("future-1", "Future task", HomeAgendaRowState.Upcoming)
        val state = buildEmptyReadyState().copy(
            overdue = HomeExpandableSection(1, listOf(overdue), true),
            unassigned = HomeExpandableSection(1, listOf(unassigned), true),
            futureGroups = listOf(
                HomeDateGroup(LocalDate.parse("2026-08-03"), "Tomorrow · Monday, August 3", listOf(future))
            ),
            allClear = false
        )

        composeRule.setContent {
            BuzerTheme {
                HomeScreen(
                    state = state,
                    onDoneTask = { doneTaskId = it },
                    onEditTask = { editTaskId = it }
                )
            }
        }

        composeRule.onNodeWithTag("home-done:overdue-1").performScrollTo().performClick()
        assertEquals("overdue-1", doneTaskId)

        composeRule.onNodeWithTag("home-row:overdue-1").performScrollTo().performClick()
        assertEquals("overdue-1", editTaskId)

        composeRule.onNodeWithTag("home-assign:unassigned-1").performScrollTo().performClick()
        assertEquals("unassigned-1", editTaskId)

        composeRule.onNodeWithTag("home-row:future-1").performScrollTo().performClick()
        assertEquals("future-1", editTaskId)
    }

    @Test
    fun futureDateHeaderRemainsPinnedWhileScrollingWithinItsGroup() {
        val firstDate = LocalDate.parse("2026-08-03")
        val state = buildEmptyReadyState().copy(
            futureGroups = listOf(
                HomeDateGroup(
                    firstDate,
                    "Tomorrow · Monday, August 3",
                    (1..20).map { index ->
                        agendaRow("future-first-$index", "First group task $index", HomeAgendaRowState.Upcoming)
                    }
                ),
                HomeDateGroup(
                    LocalDate.parse("2026-08-04"),
                    "Tuesday, August 4",
                    listOf(agendaRow("future-second", "Second group task", HomeAgendaRowState.Upcoming))
                ),
                HomeDateGroup(
                    LocalDate.parse("2026-08-05"),
                    "Wednesday, August 5",
                    listOf(agendaRow("future-third", "Third group task", HomeAgendaRowState.Upcoming))
                )
            ),
            allClear = false
        )

        composeRule.setContent {
            BuzerTheme { HomeScreen(state = state) }
        }

        composeRule.onNodeWithTag("home-row:future-first-10").performScrollTo()
        composeRule.onNodeWithTag("home-agenda-list").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val header = composeRule.onNodeWithTag("date-header:$firstDate").assertIsDisplayed()
        val firstTop = header.fetchSemanticsNode().boundsInRoot.top

        composeRule.onNodeWithTag("home-agenda-list").performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        val secondTop = composeRule.onNodeWithTag("date-header:$firstDate")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        assertTrue(
            "Expected the sticky date header to remain pinned; top moved from $firstTop to $secondTop",
            abs(firstTop - secondTop) < 2f
        )
    }

    private fun agendaRow(
        taskId: String,
        title: String,
        state: HomeAgendaRowState
    ) = HomeAgendaRow(
        taskId = taskId,
        timeLabel = "09:00-10:00",
        title = title,
        state = state,
        subline = state.name,
        description = null,
        checkedCount = 0,
        totalCount = 0,
        nextUncheckedChildTitle = null,
        doneEnabled = true,
        pendingLabel = null,
        actionAvailable = true
    )

    private fun buildEmptyReadyState() = HomeScreenState(
        weekdayLabel = "Sunday",
        dateLabel = "August 2",
        updatedLabel = null,
        contentState = HomeContentState.Ready,
        refreshInFlight = false,
        actionStatusMessage = null,
        reviewAttention = null,
        refreshAttention = null,
        currentBlock = null,
        overlappingCurrentRows = emptyList(),
        overdue = HomeExpandableSection(0, emptyList(), false),
        unassigned = HomeExpandableSection(0, emptyList(), false),
        futureGroups = emptyList(),
        allClear = true
    )
}
