package app.buzer.android.morningreview

import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.ScheduledAlarmIdentity
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class MorningReviewAlarmHandlerTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-08-03T08:00:00Z")

    @Test
    fun matchingValidDailyConsumesAndLaunches() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = daily))

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(identity(daily)))
        assertEquals(listOf<Call>(Consume(daily)), fixture.actions.calls)
    }

    @Test
    fun matchingValidSnoozeConsumesAndLaunches() {
        val snooze = alarm(MorningReviewAlarmReason.Snooze, now)
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = snooze))

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(identity(snooze)))
        assertEquals(listOf<Call>(Consume(snooze)), fixture.actions.calls)
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
        assertEquals(emptyList<Call>(), fixture.actions.calls)
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
        assertEquals(listOf<Call>(Reconcile), dateFixture.actions.calls)

        val snoozeKey = alarm(MorningReviewAlarmReason.Snooze, now)
        val reasonMismatch = snoozeKey.copy(reason = MorningReviewAlarmReason.Daily)
        val reasonFixture = fixture(MorningReviewLocalState(scheduledAlarm = reasonMismatch))

        assertEquals(MorningReviewAlarmDecision.Ignore, reasonFixture.handler.handle(identity(reasonMismatch)))
        assertEquals(listOf<Call>(Reconcile), reasonFixture.actions.calls)
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
        assertEquals(listOf<Call>(Reconcile), fixture.actions.calls)
    }

    @Test
    fun wrongLocalDateAfterTimezoneChangeIsIgnoredAndReconciled() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            MorningReviewLocalState(scheduledAlarm = daily),
            zone = ZoneId.of("Pacific/Honolulu")
        )

        assertEquals(MorningReviewAlarmDecision.Ignore, fixture.handler.handle(identity(daily)))
        assertEquals(listOf<Call>(Reconcile), fixture.actions.calls)
    }

    @Test
    fun activeStartDefersReview() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            MorningReviewLocalState(scheduledAlarm = daily),
            snapshot = snapshot(task(start = "2026-08-03T07:00:00Z", durationMinutes = 120))
        )

        assertEquals(MorningReviewAlarmDecision.Deferred, fixture.handler.handle(identity(daily)))
        assertEquals(listOf<Call>(Defer(daily)), fixture.actions.calls)
    }

    @Test
    fun overdueTaskNeverDefersReview() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            MorningReviewLocalState(scheduledAlarm = daily),
            snapshot = snapshot(task(start = "2026-08-03T06:00:00Z", durationMinutes = 120))
        )

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(identity(daily)))
        assertEquals(listOf<Call>(Consume(daily)), fixture.actions.calls)
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
        assertEquals(listOf<Call>(Consume(lateRetry)), late.actions.calls)
    }

    @Test
    fun noCachedSnapshotConsumesAndLaunchesInsteadOfSuppressingReview() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = daily), snapshot = null)

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(identity(daily)))
        assertEquals(listOf<Call>(Consume(daily)), fixture.actions.calls)
    }

    @Test
    fun corruptCacheIsReportedAndTreatedAsUnavailable() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            initialState = MorningReviewLocalState(scheduledAlarm = daily),
            loader = { error("cache decode failed") }
        )

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(identity(daily)))
        assertEquals(listOf<Call>(Consume(daily)), fixture.actions.calls)
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
            failingAction = Action.Reconcile
        )

        assertEquals(MorningReviewAlarmDecision.Ignore, fixture.handler.handle(identity(daily)))
        assertEquals(listOf<Call>(Reconcile), fixture.actions.calls)
        assertEquals(listOf(Error("reconcile", "reconcile failed")), fixture.errors)
    }

    @Test
    fun failedPriorityDeferIsReportedAndStillDefersReview() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            initialState = MorningReviewLocalState(scheduledAlarm = daily),
            snapshot = snapshot(task(start = "2026-08-03T07:00:00Z", durationMinutes = 120)),
            failingAction = Action.Defer
        )

        assertEquals(MorningReviewAlarmDecision.Deferred, fixture.handler.handle(identity(daily)))
        assertEquals(listOf<Call>(Defer(daily)), fixture.actions.calls)
        assertEquals(listOf(Error("defer", "defer failed")), fixture.errors)
    }

    @Test
    fun failedConsumeIsReportedAndStillLaunchesDueReview() {
        val daily = alarm(MorningReviewAlarmReason.Daily, now)
        val fixture = fixture(
            initialState = MorningReviewLocalState(scheduledAlarm = daily),
            failingAction = Action.Consume
        )

        assertEquals(MorningReviewAlarmDecision.Launch, fixture.handler.handle(identity(daily)))
        assertEquals(listOf<Call>(Consume(daily)), fixture.actions.calls)
        assertEquals(listOf(Error("consume", "consume failed")), fixture.errors)
    }

    private fun fixture(
        initialState: MorningReviewLocalState,
        now: Instant = this.now,
        snapshot: SnapshotViewState? = snapshot(),
        zone: ZoneId = this.zone,
        loader: (() -> SnapshotViewState?)? = null,
        failingAction: Action? = null
    ): Fixture {
        val store = MorningReviewLocalStateStore(InMemoryMorningReviewStateStorage())
        store.replace(initialState)
        val actions = RecordingActions(failingAction)
        val errors = mutableListOf<Error>()
        return Fixture(
            actions = actions,
            errors = errors,
            handler = MorningReviewAlarmHandler(
                stateStore = store,
                actions = actions,
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
        val actions: RecordingActions,
        val errors: List<Error>,
        val handler: MorningReviewAlarmHandler
    )

    private enum class Action { Reconcile, Consume, Defer }
    private sealed interface Call
    private data object Reconcile : Call
    private data class Consume(val alarm: MorningReviewScheduledAlarm) : Call
    private data class Defer(val alarm: MorningReviewScheduledAlarm) : Call
    private data class Error(val operation: String, val message: String)

    private class RecordingActions(
        private val failingAction: Action?
    ) : MorningReviewAlarmActions {
        val calls = mutableListOf<Call>()

        override fun reconcile(now: Instant, zoneId: ZoneId) {
            calls += Reconcile
            if (failingAction == Action.Reconcile) error("reconcile failed")
        }

        override fun consume(fired: MorningReviewScheduledAlarm, now: Instant, zoneId: ZoneId) {
            calls += Consume(fired)
            if (failingAction == Action.Consume) error("consume failed")
        }

        override fun deferForPriority(fired: MorningReviewScheduledAlarm, now: Instant, zoneId: ZoneId) {
            calls += Defer(fired)
            if (failingAction == Action.Defer) error("defer failed")
        }
    }
}
