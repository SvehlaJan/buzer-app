package app.buzer.android.morningreview

import app.buzer.android.enforcement.AlarmScheduler
import app.buzer.android.enforcement.ScheduledAlarm
import app.buzer.android.enforcement.ScheduledAlarmIdentity
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MorningReviewEntryDeciderTest {
    @Test
    fun `unacknowledged midnight shows banner without automatic launch`() {
        val controller = MorningReviewEntryController().apply { onResume() }
        val decision = controller.evaluate(availability(banner = true, catchUp = false))

        assertTrue(decision.showHomeBanner)
        assertFalse(decision.launchAutomated)
    }

    @Test
    fun `catch up launches once unless a review is in flight`() {
        val controller = MorningReviewEntryController().apply { onResume() }
        val availability = availability(banner = true, catchUp = true)

        assertTrue(controller.evaluate(availability).launchAutomated)
        assertFalse(controller.evaluate(availability).launchAutomated)
    }

    @Test
    fun `active snooze hides banner and automatic launch`() {
        val controller = MorningReviewEntryController().apply { onResume() }
        val decision = controller.evaluate(availability(banner = false, catchUp = false, snoozed = true))

        assertFalse(decision.showHomeBanner)
        assertFalse(decision.launchAutomated)
    }

    @Test
    fun `same day repair reopening shows banner without automatic launch`() {
        val controller = MorningReviewEntryController().apply { onResume() }
        val decision = controller.evaluate(availability(banner = true, catchUp = false))

        assertTrue(decision.showHomeBanner)
        assertFalse(decision.launchAutomated)
    }

    @Test
    fun `default on create does not launch until resume`() {
        val controller = MorningReviewEntryController()

        assertFalse(controller.evaluate(availability(banner = true, catchUp = true)).launchAutomated)
    }

    @Test
    fun `resume catch up launches once and second evaluation is guarded`() {
        val controller = MorningReviewEntryController()
        controller.onResume()

        assertTrue(controller.evaluate(availability(banner = true, catchUp = true)).launchAutomated)
        assertFalse(controller.evaluate(availability(banner = true, catchUp = true)).launchAutomated)
    }

    @Test
    fun `pause prevents async evaluation and home launch`() {
        val controller = MorningReviewEntryController()
        controller.onResume()
        controller.onPause()

        assertFalse(controller.evaluate(availability(banner = true, catchUp = true)).launchAutomated)
        assertFalse(controller.openHome())
    }

    @Test
    fun `result clears in flight and requests recomputation`() {
        val controller = MorningReviewEntryController()
        controller.onResume()
        controller.evaluate(availability(banner = true, catchUp = true))

        assertTrue(controller.onResult())
        assertTrue(controller.evaluate(availability(banner = true, catchUp = true)).launchAutomated)
    }

    @Test
    fun `home cancelled before eight preserves its visible banner`() {
        val controller = MorningReviewEntryController()
        controller.onResume()
        assertTrue(controller.openHome())

        assertTrue(controller.onResult())
        val decision = controller.evaluate(availability(banner = true, catchUp = false))
        assertTrue(decision.showHomeBanner)
        assertFalse(decision.launchAutomated)
    }

    @Test
    fun `foreground transitions honor midnight eight and snooze boundaries`() {
        val controller = MorningReviewEntryController()
        controller.onResume()

        assertTrue(controller.evaluate(availability(banner = true, catchUp = false)).showHomeBanner)
        assertTrue(controller.evaluate(availability(banner = true, catchUp = true)).launchAutomated)

        val snoozedController = MorningReviewEntryController()
        snoozedController.onResume()
        assertFalse(snoozedController.evaluate(availability(banner = false, catchUp = false, snoozed = true)).showHomeBanner)
        assertTrue(snoozedController.evaluate(availability(banner = true, catchUp = true)).launchAutomated)
    }

    @Test
    fun `boundary timing delays only positive durations and immediately reevaluates zero or negative`() {
        assertEquals(MorningReviewBoundaryTiming.Delay(1), MorningReviewBoundaryTiming.from(1))
        assertEquals(MorningReviewBoundaryTiming.ReevaluateImmediately, MorningReviewBoundaryTiming.from(0))
        assertEquals(MorningReviewBoundaryTiming.ReevaluateImmediately, MorningReviewBoundaryTiming.from(-1))
    }

    @Test
    fun `coordinator reconciles calculates blocking count and guards automated and home launches`() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-08-08T08:30:00Z")
        val store = MorningReviewLocalStateStore(InMemoryMorningReviewStateStorage())
        val scheduler = MorningReviewScheduler(
            stateStore = store,
            alarmScheduler = object : AlarmScheduler {
                override fun schedule(alarm: ScheduledAlarm) = Unit
                override fun cancel(identity: ScheduledAlarmIdentity) = Unit
            },
            calculator = MorningReviewScheduleCalculator(reviewTime = LocalTime.of(8, 0)),
            snoozeDuration = Duration.ofMinutes(5),
            priorityRetryDuration = Duration.ofMinutes(1)
        )
        val coordinator = MorningReviewCoordinator(
            stateStore = store,
            scheduler = scheduler,
            clockProvider = { MorningReviewRuntimeClock.Snapshot(now, zone) }
        )
        val unsupportedTask = TaskSnapshotItem(
            id = "unsupported-1",
            todoistTaskId = "todoist-1",
            title = "Unsupported",
            projectId = "proj",
            assigneeId = "me",
            creatorId = "me",
            dueDatetime = "2026-08-08T12:00:00Z",
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
            updatedAt = now.toString(),
            hierarchyStatus = HierarchyStatus.unsupported
        )
        val snapshot = SnapshotViewState(
            scheduleVersion = 1,
            serverTime = now.toString(),
            invalidTasks = emptyList(),
            healthWarnings = emptyList(),
            tasks = listOf(unsupportedTask)
        )
        val scope = CoroutineScope(SupervisorJob())
        try {
            assertEquals(1, coordinator.blockingCount(snapshot))
            assertFalse(coordinator.openFromHome(isReady = false))

            var automatedLaunches = 0
            coordinator.onResume()
            coordinator.refreshAvailability(
                snapshot = snapshot,
                scope = scope,
                isReady = true,
                onLaunchAutomated = { automatedLaunches += 1 }
            )

            assertEquals(1, automatedLaunches)
            assertTrue(assertNotNull(coordinator.availability).bannerVisible)
            assertFalse(coordinator.openFromHome(isReady = true))
            assertTrue(coordinator.onActivityResult())
            assertTrue(coordinator.openFromHome(isReady = true))
            coordinator.onPause()
        } finally {
            scope.cancel()
        }
    }

    private fun availability(
        banner: Boolean,
        catchUp: Boolean,
        snoozed: Boolean = false
    ) = MorningReviewAvailability(
        today = LocalDate.of(2026, 8, 8),
        bannerVisible = banner,
        catchUpRequired = catchUp,
        snoozedNow = snoozed,
        nextReevaluationAt = Instant.parse("2026-08-09T00:00:00Z")
    )
}
