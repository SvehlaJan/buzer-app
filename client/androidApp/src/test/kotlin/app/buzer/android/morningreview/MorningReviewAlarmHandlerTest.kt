package app.buzer.android.morningreview

import app.buzer.android.enforcement.AlarmScheduler
import app.buzer.android.enforcement.ScheduledAlarm
import app.buzer.android.enforcement.ScheduledAlarmIdentity
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class MorningReviewAlarmHandlerTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-08-03T08:00:00Z")

    @Test
    fun matchingValidDailyLaunchesAndSchedulesTheNextDailyOccurrence() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = daily))

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(daily.occurrenceKey))
        assertEquals(LocalDate.of(2026, 8, 4), fixture.store.read().scheduledAlarm?.reviewDate)
    }

    @Test
    fun matchingValidSnoozeLaunchesAndConsumesPendingSnoozeMetadata() {
        val snooze = alarm(MorningReviewAlarmReason.Snooze, now)
        val fixture = fixture(
            MorningReviewLocalState(
                pendingSnoozeDate = LocalDate.of(2026, 8, 3),
                pendingSnoozeAt = now,
                scheduledAlarm = snooze
            )
        )

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(snooze.occurrenceKey))
        assertEquals(null, fixture.store.read().pendingSnoozeDate)
        assertEquals(null, fixture.store.read().pendingSnoozeAt)
    }

    @Test
    fun staleOccurrenceIsIgnoredWithoutMutation() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = daily))

        assertEquals(MorningReviewAlarmDecision.Ignore, fixture.handler.handle("stale-occurrence"))
        assertEquals(daily, fixture.store.read().scheduledAlarm)
    }

    @Test
    fun acknowledgedDailyIsIgnoredAndReconciled() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            MorningReviewLocalState(
                lastAcknowledgedDate = LocalDate.of(2026, 8, 3),
                scheduledAlarm = daily
            )
        )

        assertEquals(MorningReviewAlarmDecision.Ignore, fixture.handler.handle(daily.occurrenceKey))
        assertEquals(LocalDate.of(2026, 8, 4), fixture.store.read().scheduledAlarm?.reviewDate)
    }

    @Test
    fun wrongLocalDateAfterTimezoneChangeIsIgnoredAndReconciled() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now, reviewDate = LocalDate.of(2026, 8, 3))
        val zoneAfterChange = ZoneId.of("Pacific/Honolulu")
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = daily), zone = zoneAfterChange)

        assertEquals(MorningReviewAlarmDecision.Ignore, fixture.handler.handle(daily.occurrenceKey))
        assertEquals(LocalDate.of(2026, 8, 3), fixture.store.read().scheduledAlarm?.reviewDate)
        assertEquals(Instant.parse("2026-08-03T18:00:00Z"), fixture.store.read().scheduledAlarm?.triggerAt)
    }

    @Test
    fun activeStartOrDeadlineDefersReviewByOneMinute() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            MorningReviewLocalState(scheduledAlarm = daily),
            snapshot = snapshot(task(start = "2026-08-03T07:00:00Z", durationMinutes = 120))
        )

        assertEquals(MorningReviewAlarmDecision.Deferred, fixture.handler.handle(daily.occurrenceKey))
        assertEquals(MorningReviewAlarmReason.PriorityRetry, fixture.store.read().scheduledAlarm?.reason)
        assertEquals(now.plus(Duration.ofMinutes(1)), fixture.store.read().scheduledAlarm?.triggerAt)
    }

    @Test
    fun noCachedSnapshotLaunchesInsteadOfSuppressingReview() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = daily), snapshot = null)

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(daily.occurrenceKey))
    }

    private fun fixture(
        initialState: MorningReviewLocalState,
        snapshot: SnapshotViewState? = snapshot(),
        zone: ZoneId = this.zone
    ): Fixture {
        val store = MorningReviewLocalStateStore(InMemoryMorningReviewStateStorage())
        store.replace(initialState)
        val scheduler = MorningReviewScheduler(
            stateStore = store,
            alarmScheduler = RecordingAlarmScheduler(),
            calculator = MorningReviewScheduleCalculator(LocalTime.of(8, 0)),
            snoozeDuration = Duration.ofMinutes(5),
            priorityRetryDuration = Duration.ofMinutes(1)
        )
        return Fixture(
            store = store,
            handler = MorningReviewAlarmHandler(
                stateStore = store,
                scheduler = scheduler,
                cachedSnapshotLoader = { snapshot },
                nowProvider = { now },
                zoneProvider = { zone }
            )
        )
    }

    private fun alarm(
        reason: MorningReviewAlarmReason,
        triggerAt: Instant,
        reviewDate: LocalDate = LocalDate.of(2026, 8, 3)
    ): MorningReviewScheduledAlarm = MorningReviewScheduledAlarm(
        reviewDate = reviewDate,
        reason = reason,
        triggerAt = triggerAt,
        occurrenceKey = "morning-review:${reason.name.lowercase()}:$reviewDate:${triggerAt.toEpochMilli()}"
    )

    private fun snapshot(vararg tasks: TaskSnapshotItem): SnapshotViewState = SnapshotViewState(
        scheduleVersion = 1,
        serverTime = now.toString(),
        invalidTasks = emptyList(),
        activeBlockers = emptyList(),
        inProgressTasks = emptyList(),
        startingToday = emptyList(),
        healthWarnings = emptyList(),
        tasks = tasks.toList()
    )

    private fun task(
        id: String = "task",
        start: String,
        durationMinutes: Int
    ): TaskSnapshotItem = TaskSnapshotItem(
        id = id,
        todoistTaskId = "todoist-$id",
        title = "Task $id",
        projectId = "project",
        assigneeId = "user",
        creatorId = "user",
        dueDatetime = start,
        durationMinutes = durationMinutes,
        startAt = start,
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
        occurrenceKey = "occurrence-$id"
    )

    private data class Fixture(
        val store: MorningReviewLocalStateStore,
        val handler: MorningReviewAlarmHandler
    )

    private class RecordingAlarmScheduler : AlarmScheduler {
        override fun schedule(alarm: ScheduledAlarm) = Unit
        override fun cancel(identity: ScheduledAlarmIdentity) = Unit
    }
}
