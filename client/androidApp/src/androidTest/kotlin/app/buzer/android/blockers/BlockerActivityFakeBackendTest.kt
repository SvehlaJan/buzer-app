package app.buzer.android.blockers

import android.content.Intent
import android.app.AlarmManager
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.buzer.android.MainActivity
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.AndroidAlarmScheduler
import app.buzer.android.enforcement.ScheduledAlarmIdentity
import app.buzer.android.morningreview.MorningReviewAlarmReason
import app.buzer.android.morningreview.MorningReviewLocalState
import app.buzer.android.morningreview.MorningReviewLocalStateStore
import app.buzer.android.morningreview.MorningReviewRuntimeClock
import app.buzer.android.morningreview.MorningReviewContract
import app.buzer.android.morningreview.MORNING_REVIEW_TASK_ID
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockerActivityFakeBackendTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val fakeBackend = FakeBackendClient()

    @Test
    fun startBlocker_startingCallsFakeBackend() {
        resetTestState()

        launchStartBlocker("task-start")

        waitForText("I'm starting")
        composeRule.onNodeWithText("Start now").assertIsDisplayed()
        composeRule.onNodeWithText("Start blocker fixture").assertIsDisplayed()
        composeRule.onNodeWithText("Bring the printed form and check opening hours.").assertIsDisplayed()
        waitForText("min block")
        composeRule.onNodeWithText("I'm starting").assertIsDisplayed()
        composeRule.onNodeWithText("Postpone 5 min").assertIsDisplayed()
        composeRule.onAllNodesWithText("Back").assertCountEquals(0)
        composeRule.onNodeWithText("I'm starting").performClick()

        waitForRecordedCall("/tasks/task-start/start")
        fakeBackend.assertRecorded("/tasks/task-start/start", "\"actionId\":", "\"deviceId\":", "\"expectedOccurrenceKey\":")
    }

    @Test
    fun startBlocker_systemBackDoesNotCloseProductionBlocker() {
        resetTestState()
        val scenario = launchStartBlocker("task-start")
        try {
            waitForText("I'm starting")
            pressBack()

            assertActivityNotDestroyed(scenario)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun deadlineBlocker_doneCallsFakeBackend() {
        resetTestState()

        launchDeadlineBlocker("task-deadline")

        waitForText("Done")
        composeRule.onNodeWithText("Block ended").assertIsDisplayed()
        composeRule.onNodeWithText("Deadline blocker fixture").assertIsDisplayed()
        composeRule.onNodeWithText("Share the final numbers before closing the block.").assertIsDisplayed()
        waitForText("1h block")
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNodeWithText("Extend 30m").assertIsDisplayed()
        composeRule.onNodeWithText("Reschedule").assertIsDisplayed()
        composeRule.onAllNodesWithText("Back").assertCountEquals(0)
        composeRule.onNodeWithText("Done").performClick()

        waitForRecordedCall("/tasks/task-deadline/done")
        fakeBackend.assertRecorded("/tasks/task-deadline/done", "\"actionId\":", "\"deviceId\":", "\"expectedOccurrenceKey\":")
    }

    @Test
    fun deadlineBlocker_systemBackDoesNotCloseProductionBlocker() {
        resetTestState()
        val scenario = launchDeadlineBlocker("task-deadline")
        try {
            waitForText("Done")
            pressBack()

            composeRule.onNodeWithText("Done").assertIsDisplayed()
            assertActivityNotDestroyed(scenario)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun deadlineBlocker_rescheduleUsesSharedUpdateUi() {
        runHomeSwipeRefreshPrecondition()
        resetTestState()

        val scenario = launchDeadlineBlocker("task-reschedule")

        composeRule.onNodeWithContentDescription("Reschedule action").performClick()
        waitForText("Reschedule task")
        composeRule.onNodeWithText("Schedule").assertIsDisplayed()
        composeRule.onNodeWithText("Notes").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Checklist").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Done").assertCountEquals(0)
        chooseThirtyMinuteRescheduleDuration()
        val rescheduleLabel = waitForComputedRescheduleLabel()
        composeRule.onNodeWithText(rescheduleLabel).performClick()

        waitForRecordedCall("/tasks/task-reschedule/update")
        Log.i(CORRELATION_TAG, "deadline_update backend_request_received")
        waitForBackendResponseCompleted("/tasks/task-reschedule/update")
        waitForActivityDestroyed(scenario)
        fakeBackend.assertRecorded(
            "/tasks/task-reschedule/update",
            "\"actionId\":",
            "\"deviceId\":",
            "\"expectedOccurrenceKey\":"
        )
    }

    @Test
    fun startBlocker_backendFailureShowsRetryDialogAndWritesErrorLog() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            val scenario = launchStartBlocker("task-start-fail")
            try {
                waitForText("I'm starting")
                composeRule.onNodeWithText("I'm starting").assertIsDisplayed()
                composeRule.onNodeWithText("I'm starting").performClick()

                waitForRecordedCall("/tasks/task-start-fail/start")
                waitForErrorLog("action=START_TASK taskId=task-start-fail", "forced_failure")
                waitForText("Action failed")
                assertFailureDialogSurvivesBack(scenario)
            } finally {
                scenario.close()
            }
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    @Test
    fun deadlineBlocker_rescheduleFailureUsesUpdateAction() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            val scenario = launchDeadlineBlocker("task-reschedule-fail")
            try {
                composeRule.onNodeWithContentDescription("Reschedule action").performClick()
                waitForText("Reschedule task")
                chooseThirtyMinuteRescheduleDuration()
                val rescheduleLabel = waitForComputedRescheduleLabel()
                composeRule.onNodeWithText(rescheduleLabel).performClick()

                waitForRecordedCall("/tasks/task-reschedule-fail/update")
                waitForErrorLog("action=UPDATE_TASK taskId=task-reschedule-fail", "forced_failure")
                waitForExactText("Task update")
                waitForExactText("Task update failed. Your draft is still here; retry.")
                composeRule.onNodeWithText("Task update").assertIsDisplayed()
                composeRule.onNodeWithText("Task update failed. Your draft is still here; retry.").assertIsDisplayed()
                composeRule.onNodeWithText("Retry").assertIsDisplayed()
                composeRule.onNodeWithText("Dismiss").assertIsDisplayed()
                assertActivityNotDestroyed(scenario)
            } finally {
                scenario.close()
            }
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    @Test
    fun deadlineBlocker_backendFailureShowsRetryDialogAndWritesErrorLog() {
        resetTestState()
        fakeBackend.setFailActions(true)
        try {
            val scenario = launchDeadlineBlocker("task-deadline-fail")
            try {
                waitForText("Done")
                composeRule.onNodeWithText("Done").assertIsDisplayed()
                composeRule.onNodeWithText("Done").performClick()

                waitForRecordedCall("/tasks/task-deadline-fail/done")
                waitForErrorLog("action=COMPLETE_TASK taskId=task-deadline-fail", "forced_failure")
                waitForText("Action failed")
                assertFailureDialogSurvivesBack(scenario)
            } finally {
                scenario.close()
            }
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    @Test
    fun morningReview_showsInvalidTaskFromSnapshot() {
        resetTestState("invalid")

        launchMorningReview()

        composeRule.onNodeWithText("1 task needs attention").assertIsDisplayed()
        waitForText("Invalid assigned fixture")
        composeRule.onNodeWithText("Invalid assigned fixture").assertIsDisplayed()
        composeRule.onNodeWithText("No start time or duration set").assertIsDisplayed()
        composeRule.onNodeWithText("Clarify the missing commitment fields.").assertIsDisplayed()
        composeRule.onNodeWithTag("morning-start-day").assertIsNotEnabled()
        composeRule.onAllNodesWithText("left after this task", substring = true).assertCountEquals(0)
    }

    @Test
    fun homeMorningReviewButtonOpensLiveMorningReview() {
        resetTestState()

        ActivityScenario.launch(MainActivity::class.java)

        waitForText("Review")
        composeRule.onNodeWithContentDescription("Morning review").assertDoesNotExist()
        composeRule.onNodeWithText("Review").performClick()

        waitForText("Invalid assigned fixture")
        composeRule.onNodeWithText("No start time or duration set").assertIsDisplayed()
    }

    @Test
    fun homeScreenShowsTimelineAgendaAndFabStack() {
        resetTestState()

        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Incomplete checklist task")
        waitForAnyText("remaining")
        composeRule.onNodeWithText("Incomplete checklist task").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Overdue (5)"))
        composeRule.onNodeWithText("Overdue (5)").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Unassigned (1)"))
        composeRule.onNodeWithText("Unassigned (1)").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Tomorrow task"))
        composeRule.onNodeWithText("Tomorrow task").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Add task").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Morning review").assertDoesNotExist()
        composeRule.onAllNodesWithText("Start blocker").assertCountEquals(0)
        composeRule.onAllNodesWithText("Deadline blocker").assertCountEquals(0)
    }

    @Test
    fun homeCurrentBlockDoneCallsFakeBackend() {
        resetTestState()

        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Incomplete checklist task")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Deadline blocker fixture"))
        composeRule.onAllNodesWithText("Done").filter(isEnabled()).onFirst().performClick()

        waitForRecordedCall("/tasks/task-deadline/done")
        fakeBackend.assertRecorded("/tasks/task-deadline/done", "\"actionId\":", "\"deviceId\":", "\"expectedOccurrenceKey\":")
    }

    @Test
    fun homeScreenShowsRefreshFailureBanner() {
        resetTestState()
        try {
            ActivityScenario.launch(MainActivity::class.java)

            waitForAnyText("Incomplete checklist task")
            fakeBackend.setFailSnapshot(true)
            invokeRefreshTasksAccessibilityAction()
            waitForRecordedCallCount("/tasks/sync", 1)
            waitForText("Retry")
            composeRule.onNodeWithText("Incomplete checklist task").assertIsDisplayed()

            composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
            waitForRecordedCallCount("/tasks/sync", 2)
            composeRule.onNodeWithText("Incomplete checklist task").assertIsDisplayed()
        } finally {
            fakeBackend.setFailSnapshot(false)
        }
    }

    @Test
    fun homeStickyDateHeaders_scrollsToNextYearContent() {
        resetTestState()
        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Incomplete checklist task")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Next year task"))
        composeRule.onNodeWithText("Next year task").assertIsDisplayed()
    }

    @Test
    fun homeBackToTopPill_appearsAfterDeepScrolling_scrollsToIndexZero() {
        resetTestState()
        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Incomplete checklist task")
        val (weekdayLabel, dateLabel) = displayedHomeDateLabels()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Next year task"))
        repeat(6) {
            composeRule.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        }
        waitForContentDescription("Back to top")
        composeRule.onNodeWithContentDescription("Back to top").performClick()
        waitForExactText(weekdayLabel)
        composeRule.onNodeWithText(weekdayLabel).assertIsDisplayed()
        composeRule.onNodeWithText(dateLabel).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to top").assertDoesNotExist()
    }

    @Test
    fun homeHero_notDuplicated_overlappingCurrentRowsVisible() {
        resetTestState()
        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Incomplete checklist task")
        composeRule.onNodeWithText("Incomplete checklist task").assertIsDisplayed()
        composeRule.onAllNodesWithText("Incomplete checklist task").assertCountEquals(1)
        composeRule.onNodeWithText("+5 overlapping").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Deadline blocker fixture"))
        composeRule.onNodeWithText("Deadline blocker fixture").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Overlapping current fixture"))
        composeRule.onNodeWithText("Overlapping current fixture").assertIsDisplayed()
    }

    @Test
    fun homeOverdueSection_showsThree_expandsAll_collapses() {
        resetTestState()
        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Incomplete checklist task")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Overdue (5)"))
        composeRule.onNodeWithText("Overdue (5)").assertIsDisplayed()
        composeRule.onNodeWithText("Overdue task 4").assertIsDisplayed()
        composeRule.onNodeWithText("Overdue task 3").assertIsDisplayed()
        composeRule.onAllNodesWithText("Overdue task 2").assertCountEquals(0)
        composeRule.onNodeWithText("Show all 5").assertIsDisplayed().performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Overdue task 1"))
        composeRule.onNodeWithText("Overdue task 1").assertIsDisplayed()
        composeRule.onNodeWithText("Show less").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Show all 5").assertIsDisplayed()
    }

    @Test
    fun homeUnassignedSection_initiallyCollapsed_expands_tappingAssignOpensEdit() {
        resetTestState()
        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Incomplete checklist task")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Unassigned (1)"))
        composeRule.onNodeWithText("Unassigned (1)").assertIsDisplayed()
        composeRule.onAllNodesWithText("Unassigned root fixture").assertCountEquals(0)
        composeRule.onNodeWithText("Show all 1").performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Unassigned root fixture"))
        composeRule.onNodeWithText("Unassigned root fixture").assertIsDisplayed()
        composeRule.onNodeWithText("Assign").performClick()
        waitForText("Edit task")
        waitForContentDescription("Task title")
        composeRule.onNodeWithContentDescription("Task title").assertTextContains("Unassigned root fixture")
    }

    @Test
    fun homeAssignedOtherTask_neverAppears() {
        resetTestState()
        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Incomplete checklist task")
        composeRule.onAllNodesWithText("Wife task assigned to other").assertCountEquals(0)
    }

    @Test
    fun homeHeroChecklist_togglesInvokeChildAction_parentDoneDisabledUntilComplete() {
        resetTestState()
        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Incomplete checklist task")
        composeRule.onNodeWithContentDescription("Finish current task").assertIsNotEnabled()
        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Checkbox)
                .and(hasAnySibling(hasText("Incomplete child item")))
        ).performClick()

        waitForRecordedCall("/tasks/task-incomplete-checklist/checklist/child-incomplete-open/check")
    }

    @Test
    fun homeFutureRow_editOpensTask() {
        resetTestState()
        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Incomplete checklist task")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Tomorrow task"))
        composeRule.onNodeWithText("Tomorrow task").performClick()
        waitForText("Edit task")
        waitForContentDescription("Task title")
        composeRule.onNodeWithContentDescription("Task title").assertTextContains("Tomorrow task")
    }

    @Test
    fun homeOverdueActions_routeDoneAndEditToTheExactTask() {
        resetTestState()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitForAnyText("Incomplete checklist task")
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Overdue task 4"))
            composeRule.onNodeWithTag("home-row:task-overdue-4").performClick()
            waitForText("Edit task")
            waitForContentDescription("Task title")
            composeRule.onNodeWithContentDescription("Task title").assertTextContains("Overdue task 4")

            pressBack()
            waitForAnyText("Incomplete checklist task")
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Overdue task 4"))
            composeRule.onNodeWithTag("home-done:task-overdue-4").performClick()

            waitForRecordedCall("/tasks/task-overdue-4/done")
            fakeBackend.assertRecorded(
                "/tasks/task-overdue-4/done",
                "\"actionId\":",
                "\"deviceId\":",
                "\"expectedOccurrenceKey\":"
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun homeSwipeToRefresh_recordsSync() {
        resetTestState()
        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Incomplete checklist task")
        composeRule.onNodeWithText("Incomplete checklist task").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performTouchInput {
            swipe(
                start = Offset(x = 300f, y = 300f),
                end = Offset(x = 300f, y = 1200f),
                durationMillis = 500
            )
        }

        composeRule.onNodeWithText("Incomplete checklist task").assertIsDisplayed()
        waitForRecordedCall("/tasks/sync")
        fakeBackend.assertRecorded("/tasks/sync")
    }

    @Test
    fun homeForcedRefresh_retainsCachedContentWhileSyncResponseIsHeld() {
        resetTestState()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitForAnyText("Incomplete checklist task")
            fakeBackend.holdNextSync()

            invokeRefreshTasksAccessibilityAction()
            waitForSyncHoldState("waiting")

            composeRule.onNodeWithText("Incomplete checklist task").assertIsDisplayed()

            fakeBackend.releaseSync()
            waitForBackendResponseCompleted("/tasks/sync")
            composeRule.onNodeWithText("Incomplete checklist task").assertIsDisplayed()
        } finally {
            fakeBackend.releaseSync()
            scenario.close()
        }
    }

    @Test
    fun homePullContainerSemantics_refreshTasksAction_recordsSync() {
        resetTestState()
        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Incomplete checklist task")
        invokeRefreshTasksAccessibilityAction()

        waitForRecordedCall("/tasks/sync")
        fakeBackend.assertRecorded("/tasks/sync")
    }

    @Test
    fun homeAbsentControls_calendarSearchOverflowSecondaryFabAbsent() {
        resetTestState()
        ActivityScenario.launch(MainActivity::class.java)

        waitForAnyText("Incomplete checklist task")
        composeRule.onNodeWithContentDescription("Morning review").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Search").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Calendar").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Overflow menu").assertDoesNotExist()
        composeRule.onAllNodesWithText("Search").assertCountEquals(0)
    }

    @Test
    fun morningReview_repairShowsValidationErrorForMissingDeadline() {
        resetTestState("invalid")

        launchMorningReview()

        waitForText("Invalid assigned fixture")
        repairInvalidTask()
        waitForContentDescription("Schedule: Set date, time & duration")
        composeRule.onNodeWithContentDescription("Schedule: Set date, time & duration").assertIsDisplayed()
        composeRule.onNodeWithText("Repair").assertIsDisplayed()

        waitForAnyText("Start time is required")
        composeRule.onNodeWithText("Start time is required").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun morningReview_repairCallsFakeBackend() {
        resetTestState("invalid")
        val scenario = launchMorningReview()

        waitForText("Invalid assigned fixture")
        repairInvalidTask()
        waitForContentDescription("Schedule: Set date, time & duration")
        composeRule.onNodeWithContentDescription("Schedule: Set date, time & duration").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Notes: Clarify the missing commitment fields.")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithContentDescription("Notes").performTextInput(" Updated repair note")
        closeSoftKeyboard()
        selectDefaultRepairSchedule()
        val repairLabel = waitForComputedRepairLabel()
        composeRule.onNodeWithText(repairLabel).performClick()

        waitForRecordedCall("/tasks/task-invalid/update")
        fakeBackend.assertRecorded(
            "/tasks/task-invalid/update",
            "\"actionId\":",
            "\"deviceId\":",
            "\"expectedOccurrenceKey\":"
        )
        waitForText("Morning review")
        composeRule.onNodeWithText("Start day").assertIsDisplayed()
        waitForTextAbsent("1 task needs attention")
        assertActivityNotDestroyed(scenario)
    }

    @Test
    fun morningReview_repairBackendFailureShowsErrorAndStaysOpen() {
        resetTestState("invalid")
        try {
            val scenario = launchMorningReview()

            waitForText("Invalid assigned fixture")
            repairInvalidTask()
            selectDefaultRepairSchedule()
            val repairLabel = waitForComputedRepairLabel()
            fakeBackend.setFailActions(true)
            composeRule.onNodeWithText(repairLabel).performClick()

            waitForRecordedCall("/tasks/task-invalid/update")
            waitForErrorLog("action=UPDATE_TASK taskId=task-invalid", "forced_failure")
            waitForExactText("Task update")
            waitForExactText("Task update failed. Your draft is still here; retry.")
            composeRule.onNodeWithText("Task update").assertIsDisplayed()
            composeRule.onNodeWithText("Task update failed. Your draft is still here; retry.").assertIsDisplayed()
            composeRule.onNodeWithText("Retry").assertIsDisplayed()
            composeRule.onNodeWithText("Dismiss").assertIsDisplayed()
            assertActivityNotDestroyed(scenario)
        } finally {
            fakeBackend.setFailActions(false)
        }
    }

    @Test
    fun morningReview_snoozeStaysLocalAndHides() {
        resetTestState("invalid")
        val scenario = launchMorningReview()

        waitForText("Invalid assigned fixture")
        composeRule.onNodeWithTag("morning-snooze").performClick()

        waitForActivityDestroyed(scenario)
        val state = MorningReviewLocalStateStore
            .fromContext(ApplicationProvider.getApplicationContext())
            .read()
        check(state.pendingSnoozeAt != null)
        check(state.scheduledAlarm?.reason == MorningReviewAlarmReason.Snooze)
        val obsoleteDismissPath = "/review/" + "dismiss"
        check(!fakeBackend.calls().contains("\"path\":\"$obsoleteDismissPath\""))
    }

    @Test
    fun morningReview_startAcknowledgesLocallyAndHides() {
        resetTestState("invalid")
        val scenario = launchMorningReview()
        try {
            waitForText("Invalid assigned fixture")
            repairInvalidTask()
            selectDefaultRepairSchedule()
            composeRule.onNodeWithText(waitForComputedRepairLabel()).performClick()
            waitForRecordedCall("/tasks/task-invalid/update")
            waitForText("Start day")
            waitForTextAbsent("1 task needs attention")

            composeRule.onNodeWithTag("morning-start-day").performScrollTo().performClick()

            waitForActivityDestroyed(scenario)
            val state = MorningReviewLocalStateStore
                .fromContext(ApplicationProvider.getApplicationContext())
                .read()
            check(state.lastAcknowledgedDate != null)
            check(state.pendingSnoozeAt == null)
            val obsoleteDismissPath = "/review/" + "dismiss"
            check(!fakeBackend.calls().contains("\"path\":\"$obsoleteDismissPath\""))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun morningReview_cleanAndNoDataStartAcknowledgeAndClose() {
        resetTestState("clean")
        setMorningReviewClock("2026-08-08T06:00:00Z")
        val clean = launchMorningReview()
        composeRule.onNodeWithTag("morning-start-day").performClick()
        waitForActivityDestroyed(clean)
        check(MorningReviewLocalStateStore.fromContext(ApplicationProvider.getApplicationContext()).read().lastAcknowledgedDate != null)

        resetTestState("clean")
        fakeBackend.setFailSnapshot(true)
        val noData = launchMorningReview()
        waitForText("Start day without data")
        composeRule.onNodeWithText("Start day without data").performClick()
        waitForActivityDestroyed(noData)
        check(MorningReviewLocalStateStore.fromContext(ApplicationProvider.getApplicationContext()).read().lastAcknowledgedDate != null)
        fakeBackend.setFailSnapshot(false)
    }

    @Test
    fun morningReview_overdueRowsAreActionableWhileStartIsEnabled() {
        resetTestState("overdue")
        val scenario = launchMorningReview()
        try {
            waitForText("Overdue task 1")
            composeRule.onNodeWithTag("morning-start-day").assertIsEnabled()
            composeRule.onNodeWithContentDescription("Mark Overdue task 1 done").assertIsDisplayed()
        } finally { scenario.close() }
    }

    @Test
    fun morningReview_automatedBackStaysWhileHomeBackCloses() {
        resetTestState("clean")
        val automated = launchMorningReview()
        automated.onActivity { (it as OnBackPressedDispatcherOwner).onBackPressedDispatcher.onBackPressed() }
        assertActivityNotDestroyed(automated)
        automated.close()

        val home = ActivityScenario.launch<MorningReviewActivity>(
            MorningReviewContract.homeIntent(Intent(ApplicationProvider.getApplicationContext(), MorningReviewActivity::class.java))
        )
        home.onActivity { (it as OnBackPressedDispatcherOwner).onBackPressedDispatcher.onBackPressed() }
        waitForActivityDestroyed(home)
    }

    @Test
    fun morningReview_refreshRetainsCachedInvalidAndRetryUsesTasksSync() {
        resetTestState("invalid")
        val scenario = launchMorningReview()
        try {
            waitForText("Invalid assigned fixture")
            fakeBackend.setFailSnapshot(true)
            composeRule.onNodeWithText("Retry refresh").performClick()
            waitForRecordedCall("/tasks/sync")
            waitForText("Retry refresh")
            composeRule.onNodeWithText("Invalid assigned fixture").assertIsDisplayed()
            fakeBackend.setFailSnapshot(false)
            composeRule.onNodeWithText("Retry refresh").performClick()
            waitForRecordedCallCount("/tasks/sync", 2)
            composeRule.onNodeWithText("Invalid assigned fixture").assertIsDisplayed()
        } finally { fakeBackend.setFailSnapshot(false); scenario.close() }
    }

    @Test
    fun morningReview_snoozeIsFiveMinutesAndSuppressesCatchUpBeforeExpiry() {
        resetTestState("clean")
        setMorningReviewClock("2026-08-08T07:00:00Z")
        val scenario = launchMorningReview()
        composeRule.onNodeWithTag("morning-snooze").performClick()
        waitForActivityDestroyed(scenario)
        val state = MorningReviewLocalStateStore.fromContext(ApplicationProvider.getApplicationContext()).read()
        check(state.pendingSnoozeAt?.toEpochMilli() in Instant.parse("2026-08-08T07:04:59Z").toEpochMilli()..Instant.parse("2026-08-08T07:05:01Z").toEpochMilli())
        setMorningReviewClock("2026-08-08T07:03:00Z")
        val home = ActivityScenario.launch(MainActivity::class.java)
        try { composeRule.onNodeWithText("Review").assertDoesNotExist() } finally { home.close() }
    }

    @Test
    fun morningReview_pre08HomeAcknowledgementRemovesBannerAndPreventsPost08CatchUp() {
        resetTestState("clean")
        setMorningReviewClock("2026-08-08T05:00:00Z")
        val home = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitForText("Review")
            composeRule.onNodeWithText("Review").performClick()
            waitForText("Start day")
            composeRule.onNodeWithTag("morning-start-day").performClick()
            waitForText("Buzer")
            composeRule.onNodeWithText("Review").assertDoesNotExist()
            setMorningReviewClock("2026-08-08T07:00:00Z")
            home.recreate()
            composeRule.onNodeWithText("Review").assertDoesNotExist()
        } finally { home.close() }
    }

    @Test
    fun morningReview_post08AppOpenLaunchesOnceThenReturnsHomeWithoutRelaunch() {
        resetTestState("clean")
        setMorningReviewClock("2026-08-08T07:00:00Z")
        val home = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitForText("Start day")
            composeRule.onNodeWithTag("morning-start-day").performClick()
            waitForText("Buzer")
            home.recreate()
            composeRule.onNodeWithText("Start day").assertDoesNotExist()
        } finally { home.close() }
    }

    @Test
    fun morningReview_ownerBlockingMixDisablesStartButUnassignedRefreshEnablesIt() {
        resetTestState("blockingMix")
        val scenario = launchMorningReview()
        try {
            waitForText("Invalid assigned fixture")
            composeRule.onNodeWithTag("morning-start-day").assertIsNotEnabled()
            fakeBackend.reset("unassigned")
            composeRule.onNodeWithText("Retry refresh").performClick()
            waitForText("Start day")
            composeRule.onNodeWithTag("morning-start-day").assertIsEnabled()
        } finally { scenario.close() }
    }

    private fun resetTestState(morningReviewScenario: String? = null) {
        MorningReviewRuntimeClock.setForTests(null)
        // Each test replaces the fake backend's fixture snapshot. Clear the persisted authority
        // first so monotonic snapshot protection cannot intentionally retain the prior test's
        // snapshot after a fake-backend restart.
        File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "latest_snapshot.json").delete()
        clearMorningReviewLocalState()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSystemService(NotificationManager::class.java).cancelAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(AlarmManager::class.java).cancelAll()
        }
        fakeBackend.setFailActions(false)
        fakeBackend.setFailSnapshot(false)
        fakeBackend.reset(morningReviewScenario)
        if (morningReviewScenario == null) {
            MorningReviewLocalStateStore.fromContext(context).replace(
                MorningReviewLocalState(lastAcknowledgedDate = LocalDate.now(ZoneId.systemDefault()))
            )
        }
        blockerErrorLog().delete()
    }

    private fun setMorningReviewClock(instant: String, zone: ZoneId = ZoneId.of("Europe/Bratislava")) {
        val fixed = Instant.parse(instant)
        MorningReviewRuntimeClock.setForTests { fixed to zone }
    }

    private fun clearMorningReviewLocalState() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = MorningReviewLocalStateStore.fromContext(context)
        store.read().scheduledAlarm?.let { alarm ->
            AndroidAlarmScheduler(context).cancel(
                ScheduledAlarmIdentity(
                    taskId = MORNING_REVIEW_TASK_ID,
                    occurrenceKey = alarm.occurrenceKey,
                    kind = AlarmKind.MORNING_REVIEW
                )
            )
        }
        store.replace(MorningReviewLocalState())
    }

    private fun runHomeSwipeRefreshPrecondition() {
        resetTestState()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitForAnyText("Incomplete checklist task")
            val snapshotFile = latestSnapshotFile()
            composeRule.onNode(hasScrollAction()).performTouchInput {
                swipe(
                    start = Offset(x = 300f, y = 300f),
                    end = Offset(x = 300f, y = 1200f),
                    durationMillis = 500
                )
            }
            waitForRecordedCall("/tasks/sync")
            Log.i(CORRELATION_TAG, "home_sync backend_request_received")
            val syncAuthority = waitForBackendResponseAuthority("/tasks/sync")
            Log.i(CORRELATION_TAG, "home_sync backend_response_completed")
            waitForPersistedSnapshotAuthority(snapshotFile, syncAuthority)
            Log.i(CORRELATION_TAG, "home_sync android_coordinator_applied")
        } finally {
            scenario.close()
        }
        waitForActivityDestroyed(scenario)
        Log.i(CORRELATION_TAG, "home_sync activity_finished")
    }

    private fun repairInvalidTask() {
        composeRule.onNodeWithTag("morning-repair-task-invalid").performClick()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText(text, substring = true).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitForExactText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText(text).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitForAnyText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForTextAbsent(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(text)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    private fun waitForContentDescription(contentDescription: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithContentDescription(contentDescription).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun displayedHomeDateLabels(): Pair<String, String> {
        val visibleTexts = composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
            .flatMap { node ->
                node.config.getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text }
            }
        val weekday = visibleTexts.firstOrNull(WEEKDAY_LABELS::contains)
            ?: throw AssertionError("Expected a displayed Home weekday label, found $visibleTexts")
        val date = visibleTexts.firstOrNull(HOME_DATE_LABEL::matches)
            ?: throw AssertionError("Expected a displayed Home date label, found $visibleTexts")
        return weekday to date
    }

    private fun waitForComputedRescheduleLabel(): String {
        val matcher = Regex("""Reschedule \d{2}:\d{2}–\d{2}:\d{2}""")
        var match = ""
        composeRule.waitUntil(timeoutMillis = 15_000) {
            match = composeRule.onAllNodesWithText("Reschedule ", substring = true)
                .fetchSemanticsNodes()
                .asSequence()
                .flatMap { node ->
                    node.config.getOrNull(SemanticsProperties.Text)
                        ?.asSequence()
                        ?.map { it.text }
                        ?: emptySequence()
                }
                .firstOrNull { matcher.matches(it) }
                .orEmpty()
            match.isNotEmpty()
        }
        return match
    }

    private fun waitForComputedRepairLabel(): String {
        val matcher = Regex("""Repair \d{2}:\d{2}–\d{2}:\d{2}""")
        var match = ""
        composeRule.waitUntil(timeoutMillis = 15_000) {
            match = composeRule.onAllNodesWithText("Repair ", substring = true)
                .fetchSemanticsNodes()
                .asSequence()
                .flatMap { node ->
                    node.config.getOrNull(SemanticsProperties.Text)
                        ?.asSequence()
                        ?.map { it.text }
                        ?: emptySequence()
                }
                .firstOrNull { matcher.matches(it) }
                .orEmpty()
            match.isNotEmpty()
        }
        return match
    }

    private fun waitForRecordedCall(path: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var lastCalls = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastCalls = runCatching { fakeBackend.calls() }.getOrDefault(lastCalls)
            if (lastCalls.contains("\"path\":\"$path\"")) return
            SystemClock.sleep(100)
        }
        fail(
            "Expected fake backend call $path.\n" +
                "Recorded calls: $lastCalls\n" +
                "Blocker error log: ${blockerErrorLog().takeIf { it.exists() }?.readText().orEmpty()}"
        )
    }

    private fun waitForBackendResponseCompleted(path: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching { fakeBackend.hasCompletedCall(path) }.getOrDefault(false)) return
            SystemClock.sleep(100)
        }
        fail("Expected fake backend response completion for $path. Recorded calls: ${fakeBackend.calls()}")
    }

    private fun waitForBackendResponseAuthority(path: String): RecordedResponseAuthority {
        var authority: RecordedResponseAuthority? = null
        composeRule.waitUntil(timeoutMillis = 15_000) {
            authority = runCatching { fakeBackend.completedCallResponseAuthority(path) }.getOrNull()
            authority != null
        }
        return checkNotNull(authority)
    }

    private fun waitForPersistedSnapshotAuthority(
        snapshotFile: File,
        expected: RecordedResponseAuthority
    ) {
        var observed = ""
        val applied = runCatching {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                observed = snapshotFile.takeIf(File::exists)?.readText().orEmpty()
                val snapshot = runCatching { Json.parseToJsonElement(observed).jsonObject }.getOrNull()
                snapshot?.get("serverTime")?.jsonPrimitive?.contentOrNull == expected.serverTime &&
                    snapshot["snapshotGeneration"]?.jsonPrimitive?.longOrNull == expected.snapshotGeneration
            }
        }.isSuccess
        if (!applied) {
            fail(
                "Expected persisted snapshot authority $expected.\n" +
                    "Persisted snapshot: $observed\n" +
                    "Recorded calls: ${fakeBackend.calls()}"
            )
        }
    }

    private fun waitForRecordedCallCount(path: String, expectedCount: Int) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var lastCalls = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            lastCalls = runCatching { fakeBackend.calls() }.getOrDefault(lastCalls)
            val count = Regex("""\"path\":\"${Regex.escape(path)}\"""").findAll(lastCalls).count()
            if (count >= expectedCount) return
            SystemClock.sleep(100)
        }
        fail("Expected at least $expectedCount fake backend calls to $path. Recorded calls: $lastCalls")
    }

    private fun waitForSyncHoldState(expectedState: String) {
        var observedState = "unknown"
        val matched = runCatching {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                observedState = runCatching { fakeBackend.syncHoldState() }.getOrDefault(observedState)
                observedState == expectedState
            }
        }.isSuccess
        if (!matched) {
            fail(
                "Expected sync hold state $expectedState, observed $observedState. " +
                    "Recorded calls: ${fakeBackend.calls()}"
            )
        }
    }

    private fun invokeRefreshTasksAccessibilityAction() {
        val node = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        node.assertIsDisplayed()
        val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        actions.first { it.label == "Refresh tasks" }.action()
    }

    private fun selectDefaultRepairSchedule() {
        val scheduleOpen = runCatching {
            composeRule.onNodeWithText("Date").performScrollTo().assertIsDisplayed()
        }.isSuccess
        if (!scheduleOpen) {
            composeRule.onNodeWithContentDescription("Schedule: Set date, time & duration")
                .performScrollTo()
                .performClick()
        }
        waitForText("Date")
        composeRule.onNodeWithText("Today").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Custom start time").performScrollTo().performClick()
        waitForContentDescription("Custom start time dialog apply")
        composeRule.onNodeWithContentDescription("Custom start time dialog apply").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("30 min").performScrollTo().performClick()
        composeRule.waitForIdle()
        waitForText("Ends at")
    }

    private fun chooseThirtyMinuteRescheduleDuration() {
        composeRule.onNodeWithText("Schedule").performScrollTo().performClick()
        composeRule.onNodeWithText("Tomorrow").performScrollTo().performClick()
        composeRule.onNodeWithText("30 min").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun waitForActivityDestroyed(scenario: ActivityScenario<out android.app.Activity>) {
        composeRule.waitUntil(timeoutMillis = 45_000) {
            scenario.state == Lifecycle.State.DESTROYED
        }
        composeRule.waitForIdle()
        Log.i(CORRELATION_TAG, "activity_finish observed")
    }

    private fun assertActivityNotDestroyed(scenario: ActivityScenario<out android.app.Activity>) {
        if (scenario.state == Lifecycle.State.DESTROYED) {
            fail("Expected blocker activity to stay open, but it was destroyed")
        }
    }

    private fun assertFailureDialogSurvivesBack(
        scenario: ActivityScenario<out android.app.Activity>,
        title: String = "Action failed"
    ) {
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onAllNodesWithText("Dismiss").assertCountEquals(0)

        scenario.onActivity { (it as OnBackPressedDispatcherOwner).onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onAllNodesWithText("Dismiss").assertCountEquals(0)
        assertActivityNotDestroyed(scenario)
    }

    private fun waitForErrorLog(vararg fragments: String) {
        var text = ""
        val completed = runCatching {
            composeRule.waitUntil(timeoutMillis = 15_000) {
                text = blockerErrorLog().takeIf { it.exists() }?.readText().orEmpty()
                fragments.all(text::contains)
            }
        }.isSuccess
        if (!completed) {
            fail(
                "Expected blocker error log to contain ${fragments.toList()}.\n" +
                    "Blocker error log: $text"
            )
        }
    }

    private fun launchStartBlocker(taskId: String): ActivityScenario<StartBlockerActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), StartBlockerActivity::class.java).apply {
                putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
                putExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY, "occurrence-$taskId-1")
            }
        )

    private fun launchDeadlineBlocker(taskId: String): ActivityScenario<DeadlineBlockerActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), DeadlineBlockerActivity::class.java).apply {
                putExtra(AlarmIntentFactory.EXTRA_TASK_ID, taskId)
                putExtra(AlarmIntentFactory.EXTRA_OCCURRENCE_KEY, "occurrence-$taskId-1")
            }
        )

    private fun launchMorningReview(): ActivityScenario<MorningReviewActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), MorningReviewActivity::class.java)
        )

    private fun blockerErrorLog(): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "blocker_action_errors.log")

    private fun latestSnapshotFile(): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "latest_snapshot.json")

    private companion object {
        const val CORRELATION_TAG = "BuzerE2ECorrelation"
        val WEEKDAY_LABELS = setOf(
            "Monday",
            "Tuesday",
            "Wednesday",
            "Thursday",
            "Friday",
            "Saturday",
            "Sunday"
        )
        val HOME_DATE_LABEL = Regex(
            "^(January|February|March|April|May|June|July|August|September|October|November|December) \\d{1,2}$"
        )
    }
}
