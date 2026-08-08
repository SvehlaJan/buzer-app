package app.buzer.android.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.theme.BuzerTheme
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.ProjectCollaborator
import app.buzer.shared.model.ProjectItem
import app.buzer.shared.model.TaskSnapshotItem
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MorningReviewRepairHostTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun blockingReviewShowsHeadingsCardsBeforeGlobalActionsAndOnlyOneSnooze() {
        setScreen(blockingState())

        composeRule.onNodeWithText("2 tasks need attention").assertHeading()
        composeRule.onNodeWithText("Friday, July 24, 2026").assertHeading()
        composeRule.onNodeWithText("Repair me").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for sync").assertIsDisplayed()
        composeRule.onNodeWithText("Start day").assertIsNotEnabled()
        composeRule.onAllNodesWithText("Snooze 5 min").assertCountEquals(1)
        composeRule.onNodeWithTag("morning-snooze-repair", useUnmergedTree = true).assertDoesNotExist()
        assertVerticallyBefore("morning-blocking-repair", "morning-start-day")
        assertVerticallyBefore("morning-blocking-pending", "morning-start-day")
        assertVerticallyBefore("morning-start-day", "morning-snooze")
    }

    @Test
    fun repeatedActionDescriptionsSelectTheCorrectTaskCallbacks() {
        var syncRetries = 0
        val first = task("first", "First repair")
        val showingSummary = mutableStateOf(false)
        val done = mutableListOf<String>()
        val edited = mutableListOf<String>()
        setScreenState(
            state = { if (showingSummary.value) multiOverdueState() else blockingState(cards = listOf(
                repairCard(first), MorningReviewBlockingCard.SyncPending("pending", "Waiting for sync")
            )) },
            taskLookup = { taskId -> first.takeIf { taskId == first.id } },
            onPendingRetry = { syncRetries++ },
            onOverdueDone = { done += it },
            onOverdueEdit = { edited += it }
        )

        composeRule.onNodeWithContentDescription("Repair First repair").performClick()
        composeRule.onNodeWithText("Repair task").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithContentDescription("Retry sync for Waiting for sync").performClick()
        assertEquals(1, syncRetries)

        showingSummary.value = true
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("First late task"))
        composeRule.onNodeWithContentDescription("Mark First late task done").performClick()
        composeRule.onNodeWithContentDescription("Edit Second late task").performClick()
        assertEquals(listOf("overdue-first"), done)
        assertEquals(listOf("overdue-second"), edited)
    }

    @Test
    fun repairingLastBlockerReturnsToSummaryAndMovesFocusWithoutClosingHost() {
        val repair = task("repair", "Repair me")
        val state = mutableStateOf(blockingState(cards = listOf(repairCard(repair))))
        val repaired = mutableListOf<String>()
        setScreenState({ state.value }, taskLookup = { if (it == repair.id) repair else null }, onRepairSuccess = {
            repaired += it
            state.value = summaryState()
        })

        composeRule.onNodeWithText("Repair").performClick()
        composeRule.onNodeWithText("Repair 09:00–09:30").performClick()
        composeRule.waitUntil(5_000) { repaired == listOf("repair") }
        composeRule.onNodeWithText("Morning review").assertIsDisplayed().assertIsFocused()
        composeRule.onNodeWithText("Start day").assertIsDisplayed()
    }

    @Test
    fun summaryOrdersOverdueBeforeTodayAndOnlyOverdueRowsHaveActions() {
        val done = mutableListOf<String>()
        val edited = mutableListOf<String>()
        setScreen(summaryState(), onOverdueDone = { done += it }, onOverdueEdit = { edited += it })

        composeRule.onNodeWithTag("morning-overdue-overdue").assertIsDisplayed()
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        assertVerticallyBefore("morning-overdue-overdue", "morning-today-today-1")
        assertVerticallyBefore("morning-overdue-overdue", "morning-today-heading")
        assertVerticallyBefore("morning-start-day", "morning-snooze")
        composeRule.onNodeWithTag("morning-today-today-1").assertNoActionText()
        composeRule.onAllNodesWithText("Done").assertCountEquals(1)
        composeRule.onAllNodesWithText("Edit").assertCountEquals(1)
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithText("Edit").performClick()
        assertEquals(listOf("overdue"), done)
        assertEquals(listOf("overdue"), edited)
        composeRule.onNodeWithText("Start day").assertIsEnabled()
        composeRule.onNodeWithText("09:00–09:30").assertIsDisplayed()
        composeRule.onNodeWithTag("morning-overdue-overdue").assertIsDisplayed()
    }

    @Test
    fun overdueEditorPreservesDraftOnOrdinaryRefreshAndClosesOnAuthorityInvalidation() {
        val overdue = task("overdue", "Late task")
        val currentTask = mutableStateOf(overdue)
        val state = mutableStateOf(summaryState())
        setScreenState({ state.value }, taskLookup = { id -> currentTask.value.takeIf { it.id == id } })

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Late task"))
        composeRule.onNodeWithContentDescription("Edit Late task").performClick()
        composeRule.onNodeWithText("Edit overdue task").assertIsDisplayed()
        composeRule.onNodeWithText("Late task").performTextClearance()
        composeRule.onNodeWithText("Late task").performTextInput("Changed draft")
        currentTask.value = currentTask.value.copy(description = "Authoritative refresh")
        composeRule.onNodeWithText("Changed draft").assertIsDisplayed()

        currentTask.value = currentTask.value.copy(taskRevision = "revision-replaced")
        composeRule.onNodeWithText("Morning review").assertIsDisplayed()
        composeRule.onNodeWithText("Edit overdue task").assertDoesNotExist()
    }

    @Test
    fun noDataProvidesRefreshStartWithoutDataAndSnooze() {
        var refreshes = 0
        var starts = 0
        var snoozes = 0
        setScreen(noDataState(), onRetryRefresh = { refreshes++ }, onStart = { starts++ }, onSnooze = { snoozes++ })

        composeRule.onNodeWithText("Retry refresh").performClick()
        composeRule.onNodeWithText("Start day without data").performClick()
        composeRule.onNodeWithText("Snooze 5 min").performClick()
        assertEquals(1, refreshes)
        assertEquals(1, starts)
        assertEquals(1, snoozes)
    }

    @Test
    fun homeShowsBackWhileAutomatedEntryDoesNot() {
        val showBack = mutableStateOf(true)
        setScreenState({ summaryState(showBack = showBack.value) })
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        showBack.value = false
        composeRule.onNodeWithText("Back").assertDoesNotExist()
    }

    @Test
    fun largeFontCanScrollToBothGlobalActions() {
        composeRule.activity.resources.configuration.fontScale = 2f
        setScreen(summaryState(todayCount = 24))

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Start day"))
        composeRule.onNodeWithText("Start day").assertIsDisplayed()
        composeRule.onNodeWithText("Snooze 5 min").performScrollTo().assertIsDisplayed()
    }

    private fun setScreen(
        state: MorningReviewScreenState,
        taskLookup: (String) -> TaskSnapshotItem? = { null },
        onRepairSuccess: (String) -> Unit = {},
        onStart: () -> Unit = {},
        onSnooze: () -> Unit = {},
        onOverdueDone: (String) -> Unit = {},
        onOverdueEdit: (String) -> Unit = {},
        onPendingRetry: () -> Unit = {},
        onRetryRefresh: () -> Unit = {}
    ) = setScreenState({ state }, taskLookup, onRepairSuccess, onStart, onSnooze, onOverdueDone, onOverdueEdit, onPendingRetry, onRetryRefresh)

    private fun setScreenState(
        state: () -> MorningReviewScreenState,
        taskLookup: (String) -> TaskSnapshotItem? = { null },
        onRepairSuccess: (String) -> Unit = {},
        onStart: () -> Unit = {},
        onSnooze: () -> Unit = {},
        onOverdueDone: (String) -> Unit = {},
        onOverdueEdit: (String) -> Unit = {},
        onPendingRetry: () -> Unit = {},
        onRetryRefresh: () -> Unit = {}
    ) = composeRule.setContent {
        BuzerTheme {
            MorningReviewScreen(
                screenState = state(),
                taskLookup = taskLookup,
                onBack = {},
                onRepair = { _, _ -> CoordinatorActionResult.Success },
                onRepairSuccess = onRepairSuccess,
                onStart = onStart,
                onSnooze = onSnooze,
                onOverdueDone = onOverdueDone,
                onOverdueEdit = onOverdueEdit,
                onPendingRetry = onPendingRetry,
                onRetryRefresh = onRetryRefresh,
                loadProjects = { listOf(project) },
                refreshProjects = { listOf(project) },
                nowProvider = { LocalDateTime.of(2026, 7, 24, 8, 0) }
            )
        }
    }

    private fun blockingState(cards: List<MorningReviewBlockingCard> = listOf(
        repairCard(task("repair", "Repair me")),
        MorningReviewBlockingCard.SyncPending("pending", "Waiting for sync")
    )) = baseState(MorningReviewPhase.Blocking, "2 tasks need attention").copy(
        blockingCards = cards,
        startDayEnabled = false
    )

    private fun summaryState(showBack: Boolean = true, todayCount: Int = 1) = baseState(MorningReviewPhase.Summary, "Morning review").copy(
        showBack = showBack,
        overdueRows = listOf(MorningReviewAgendaRow("overdue", "Late task", "09:00–09:30", "Overdue", true)),
        todayRows = (1..todayCount).map { MorningReviewAgendaRow("today-$it", "Today task $it", "10:00–10:30", "Today", true) }
    )

    private fun noDataState() = baseState(MorningReviewPhase.NoData, "Task data unavailable").copy(
        retryNotices = listOf(MorningReviewRetryNotice(MorningReviewRetryTarget.Refresh, "Refresh failed")),
        startDayLabel = "Start day without data"
    )

    private fun multiOverdueState() = summaryState().copy(
        overdueRows = listOf(
            MorningReviewAgendaRow("overdue-first", "First late task", "09:00–09:30", "Overdue", true),
            MorningReviewAgendaRow("overdue-second", "Second late task", "10:00–10:30", "Overdue", true)
        )
    )

    private fun baseState(phase: MorningReviewPhase, header: String) = MorningReviewScreenState(
        phase = phase, headerText = header, absoluteDateLabel = "Friday, July 24, 2026", warning = null,
        retryNotices = emptyList(), refreshing = false, blockingCards = emptyList(), overdueRows = emptyList(), todayRows = emptyList(),
        startDayLabel = "Start day", startDayEnabled = true, snoozeEnabled = true, showBack = true, actionInFlight = false
    )

    private fun repairCard(task: TaskSnapshotItem) = MorningReviewBlockingCard.Repair(task.id, task.title, "No start time", task.description)

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertHeading() = assert(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    )

    private fun assertVerticallyBefore(firstTag: String, secondTag: String) {
        val first = composeRule.onNodeWithTag(firstTag).fetchSemanticsNode().boundsInRoot.top
        val second = composeRule.onNodeWithTag(secondTag).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Expected $firstTag before $secondTag", first < second)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertNoActionText() {
        assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    private fun task(id: String, title: String) = TaskSnapshotItem(
        id = id, todoistTaskId = "todoist-$id", title = title, description = "Repair this commitment", projectId = project.id,
        assigneeId = currentUser.id, creatorId = currentUser.id, dueDatetime = "2026-07-24T09:00:00", dueDate = null,
        durationMinutes = 30, startAt = null, state = CommitmentState.invalid, isAssignedToUser = true, isCreatedByUser = true,
        isCompleted = false, isDeleted = false, validationErrors = emptyList(), startedAt = null, startDismissCount = 0,
        deadlineExtensionCount = 0, escalationSentAt = null, updatedAt = "2026-07-23T08:00:00Z", occurrenceKey = "occurrence-$id", taskRevision = "revision-$id"
    )

    private companion object {
        val currentUser = ProjectCollaborator("me", "Me", null, true)
        val project = ProjectItem("project", "Personal", false, false, false, listOf(currentUser), true)
    }
}
