package app.buzer.android.morningreview

import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.AlarmScheduler
import app.buzer.android.enforcement.ScheduledAlarm
import app.buzer.android.enforcement.ScheduledAlarmIdentity
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MorningReviewAlarmHandlerTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-08-03T08:00:00Z")
    private val tomorrowDaily = alarm(
        reason = MorningReviewAlarmReason.Daily,
        triggerAt = Instant.parse("2026-08-04T08:00:00Z"),
        reviewDate = LocalDate.of(2026, 8, 4)
    )

    @Test
    fun matchingValidDailyConsumesAndLaunches() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = daily))

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(identity(daily)))
        assertEquals(tomorrowDaily, fixture.store.read().scheduledAlarm)
    }

    @Test
    fun matchingValidSnoozeConsumesAndLaunches() {
        val snooze = alarm(MorningReviewAlarmReason.Snooze, now)
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = snooze))

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(identity(snooze)))
        assertEquals(tomorrowDaily, fixture.store.read().scheduledAlarm)
    }

    @Test
    fun staleOrWrongTaskOrKindIsIgnoredWithoutSchedulerMutation() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = daily))

        assertEquals(
            MorningReviewAlarmDecision.Ignore,
            fixture.handler.handle(identity(daily).copy(occurrenceKey = "stale-occurrence"))
        )
        assertEquals(
            MorningReviewAlarmDecision.Ignore,
            fixture.handler.handle(identity(daily).copy(taskId = "other-task"))
        )
        assertEquals(
            MorningReviewAlarmDecision.Ignore,
            fixture.handler.handle(identity(daily).copy(kind = AlarmKind.START_BLOCKER))
        )
        assertTrue(fixture.alarms.scheduled.isEmpty())
        assertTrue(fixture.alarms.cancelled.isEmpty())
        assertEquals(daily, fixture.store.read().scheduledAlarm)
    }

    @Test
    fun persistedKeyDateOrReasonMismatchIsIgnoredAndReconciled() {
        val dailyKeyForTomorrow = alarm(
            reason = MorningReviewAlarmReason.Daily,
            triggerAt = now,
            reviewDate = LocalDate.of(2026, 8, 4)
        )
        val dateMismatch = dailyKeyForTomorrow.copy(reviewDate = LocalDate.of(2026, 8, 3))
        val dateFixture = fixture(MorningReviewLocalState(scheduledAlarm = dateMismatch))

        assertEquals(MorningReviewAlarmDecision.Ignore, dateFixture.handler.handle(identity(dateMismatch)))
        assertEquals(tomorrowDaily, dateFixture.store.read().scheduledAlarm)

        val snoozeKey = alarm(MorningReviewAlarmReason.Snooze, now)
        val reasonMismatch = snoozeKey.copy(reason = MorningReviewAlarmReason.Daily)
        val reasonFixture = fixture(MorningReviewLocalState(scheduledAlarm = reasonMismatch))

        assertEquals(MorningReviewAlarmDecision.Ignore, reasonFixture.handler.handle(identity(reasonMismatch)))
        assertEquals(tomorrowDaily, reasonFixture.store.read().scheduledAlarm)
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

        assertEquals(MorningReviewAlarmDecision.Ignore, fixture.handler.handle(identity(daily)))
        assertEquals(tomorrowDaily, fixture.store.read().scheduledAlarm)
    }

    @Test
    fun wrongLocalDateAfterTimezoneChangeIsIgnoredAndReconciled() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            MorningReviewLocalState(scheduledAlarm = daily),
            zone = ZoneId.of("Pacific/Honolulu")
        )

        assertEquals(MorningReviewAlarmDecision.Ignore, fixture.handler.handle(identity(daily)))
        assertEquals(
            alarm(
                reason = MorningReviewAlarmReason.Daily,
                triggerAt = Instant.parse("2026-08-03T18:00:00Z"),
                reviewDate = LocalDate.of(2026, 8, 3)
            ),
            fixture.store.read().scheduledAlarm
        )
    }

    @Test
    fun activeStartDefersReview() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            MorningReviewLocalState(scheduledAlarm = daily),
            snapshot = snapshot(task(start = "2026-08-03T07:00:00Z", durationMinutes = 120))
        )

        assertEquals(MorningReviewAlarmDecision.Deferred, fixture.handler.handle(identity(daily)))
        assertEquals(
            alarm(MorningReviewAlarmReason.PriorityRetry, Instant.parse("2026-08-03T08:01:00Z")),
            fixture.store.read().scheduledAlarm
        )
    }

    @Test
    fun overdueTaskNeverDefersReview() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            MorningReviewLocalState(scheduledAlarm = daily),
            snapshot = snapshot(task(start = "2026-08-03T06:00:00Z", durationMinutes = 120))
        )

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(identity(daily)))
        assertEquals(tomorrowDaily, fixture.store.read().scheduledAlarm)
    }

    @Test
    fun activeStartStopsDeferringFifteenMinutesAfterReviewTime() {
        val activeStart = snapshot(task(start = "2026-08-03T07:00:00Z", durationMinutes = 120))
        val justBefore = Instant.parse("2026-08-03T08:14:59Z")
        val atCap = Instant.parse("2026-08-03T08:15:00Z")
        val earlyRetry = alarm(MorningReviewAlarmReason.PriorityRetry, justBefore)
        val lateRetry = alarm(MorningReviewAlarmReason.PriorityRetry, atCap)
        val early = fixture(MorningReviewLocalState(scheduledAlarm = earlyRetry), now = justBefore, snapshot = activeStart)
        val late = fixture(MorningReviewLocalState(scheduledAlarm = lateRetry), now = atCap, snapshot = activeStart)

        assertEquals(MorningReviewAlarmDecision.Deferred, early.handler.handle(identity(earlyRetry)))
        assertEquals(MorningReviewAlarmDecision.Launch, late.handler.handle(identity(lateRetry)))
        assertEquals(tomorrowDaily, late.store.read().scheduledAlarm)
    }

    @Test
    fun noCachedSnapshotConsumesAndLaunchesInsteadOfSuppressingReview() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = daily), snapshot = null)

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(identity(daily)))
        assertEquals(tomorrowDaily, fixture.store.read().scheduledAlarm)
    }

    @Test
    fun corruptCacheIsReportedAndTreatedAsUnavailable() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            initialState = MorningReviewLocalState(scheduledAlarm = daily),
            loader = { error("cache decode failed") }
        )

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(identity(daily)))
        assertEquals(tomorrowDaily, fixture.store.read().scheduledAlarm)
        assertEquals(listOf(Error("priority", "cache decode failed")), fixture.errors)
    }

    @Test
    fun failedReconcileIsReportedAndStillIgnoresAcknowledgedReview() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            initialState = MorningReviewLocalState(
                lastAcknowledgedDate = LocalDate.of(2026, 8, 3),
                scheduledAlarm = daily
            ),
            failFirstScheduleMessage = "reconcile failed"
        )

        assertEquals(MorningReviewAlarmDecision.Ignore, fixture.handler.handle(identity(daily)))
        assertEquals(listOf(Error("reconcile", "reconcile failed")), fixture.errors)
    }

    @Test
    fun failedPriorityDeferIsReportedAndStillDefersReview() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            initialState = MorningReviewLocalState(scheduledAlarm = daily),
            snapshot = snapshot(task(start = "2026-08-03T07:00:00Z", durationMinutes = 120)),
            failFirstScheduleMessage = "defer failed"
        )

        assertEquals(MorningReviewAlarmDecision.Deferred, fixture.handler.handle(identity(daily)))
        assertEquals(listOf(Error("defer", "defer failed")), fixture.errors)
    }

    @Test
    fun failedConsumeIsReportedAndStillLaunchesDueReview() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            initialState = MorningReviewLocalState(scheduledAlarm = daily),
            failFirstScheduleMessage = "consume failed"
        )

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(identity(daily)))
        assertEquals(listOf(Error("consume", "consume failed")), fixture.errors)
    }

    private fun fixture(
        initialState: MorningReviewLocalState,
        now: Instant = this.now,
        snapshot: SnapshotViewState? = snapshot(),
        zone: ZoneId = this.zone,
        loader: (() -> SnapshotViewState?)? = null,
        failFirstScheduleMessage: String? = null
    ): Fixture {
        val store = MorningReviewLocalStateStore(InMemoryMorningReviewStateStorage())
        store.replace(initialState)
        val alarms = RecordingAlarmScheduler(failFirstScheduleMessage)
        val scheduler = MorningReviewScheduler(
            stateStore = store,
            alarmScheduler = alarms,
            calculator = MorningReviewScheduleCalculator(reviewTime = DEFAULT_MORNING_REVIEW_TIME),
            snoozeDuration = Duration.ofMinutes(5),
            priorityRetryDuration = Duration.ofMinutes(1)
        )
        val errors = mutableListOf<Error>()
        return Fixture(
            store = store,
            alarms = alarms,
            errors = errors,
            handler = MorningReviewAlarmHandler(
                stateStore = store,
                scheduler = scheduler,
                cachedSnapshotLoader = loader ?: { snapshot },
                nowProvider = { now },
                zoneProvider = { zone },
                errorReporter = { operation, _, error -> errors += Error(operation, error.message.orEmpty()) }
            )
        )
    }

    private fun identity(alarm: MorningReviewScheduledAlarm): ScheduledAlarmIdentity = ScheduledAlarmIdentity(
        taskId = MORNING_REVIEW_TASK_ID,
        occurrenceKey = alarm.occurrenceKey,
        kind = AlarmKind.MORNING_REVIEW
    )

    private fun alarm(
        reason: MorningReviewAlarmReason,
        triggerAt: Instant,
        reviewDate: LocalDate = LocalDate.of(2026, 8, 3)
    ): MorningReviewScheduledAlarm = MorningReviewScheduledAlarm(
        reviewDate = reviewDate,
        reason = reason,
        triggerAt = triggerAt,
        occurrenceKey = morningReviewOccurrenceKey(reason, reviewDate, triggerAt)
    )

    private fun snapshot(vararg tasks: TaskSnapshotItem): SnapshotViewState = SnapshotViewState(
        scheduleVersion = 1,
        serverTime = now.toString(),
        invalidTasks = emptyList(),
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
        val alarms: RecordingAlarmScheduler,
        val errors: List<Error>,
        val handler: MorningReviewAlarmHandler
    )

    private data class Error(val operation: String, val message: String)

    private class RecordingAlarmScheduler(
        private var failFirstScheduleMessage: String?
    ) : AlarmScheduler {
        val scheduled = mutableListOf<ScheduledAlarm>()
        val cancelled = mutableListOf<ScheduledAlarmIdentity>()

        override fun schedule(alarm: ScheduledAlarm) {
            failFirstScheduleMessage?.let { message ->
                failFirstScheduleMessage = null
                error(message)
            }
            scheduled += alarm
        }

        override fun cancel(identity: ScheduledAlarmIdentity) {
            cancelled += identity
        }
    }
}
