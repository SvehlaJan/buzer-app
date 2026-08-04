package app.buzer.android.morningreview

import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.AlarmScheduler
import app.buzer.android.enforcement.ScheduledAlarm
import app.buzer.android.enforcement.ScheduledAlarmIdentity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MorningReviewSchedulerTest {
    private val utc = ZoneId.of("UTC")
    private val calculator = MorningReviewScheduleCalculator(LocalTime.of(8, 0))

    @Test
    fun firstReconcileArmsAndPersistsTheDesiredAlarm() {
        val fixture = fixture()
        val now = instant("2026-08-03T07:00:00Z")

        fixture.scheduler.reconcile(now, utc)

        val desired = scheduledDaily("2026-08-03", "2026-08-03T08:00:00Z")
        assertEquals(
            listOf(
                Cancel(legacyIdentity),
                Schedule(desired.asAlarm())
            ),
            fixture.alarmScheduler.events
        )
        assertEquals(MorningReviewLocalState(scheduledAlarm = desired), fixture.store.read())
    }

    @Test
    fun identicalReconcileRearmsTheSameIdentityWithoutStackingOrChangingIt() {
        val desired = scheduledDaily("2026-08-03", "2026-08-03T08:00:00Z")
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = desired))

        fixture.scheduler.reconcile(instant("2026-08-03T07:00:00Z"), utc)

        assertEquals(
            listOf(Cancel(legacyIdentity), Schedule(desired.asAlarm())),
            fixture.alarmScheduler.events
        )
        assertEquals(MorningReviewLocalState(scheduledAlarm = desired), fixture.store.read())
    }

    @Test
    fun changedDateOrZoneCancelsOldIdentityBeforeSchedulingOneReplacement() {
        val old = scheduledDaily("2026-08-03", "2026-08-03T08:00:00Z")
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = old))

        fixture.scheduler.reconcile(instant("2026-08-03T05:00:00Z"), ZoneId.of("Europe/Warsaw"))

        val desired = scheduledDaily("2026-08-03", "2026-08-03T06:00:00Z")
        assertEquals(
            listOf(
                Cancel(legacyIdentity),
                Cancel(old.identity()),
                Schedule(desired.asAlarm())
            ),
            fixture.alarmScheduler.events
        )
        assertEquals(MorningReviewLocalState(scheduledAlarm = desired), fixture.store.read())
    }

    @Test
    fun everyReconcileCancelsTheFixedLegacyIdentity() {
        val fixture = fixture()

        fixture.scheduler.reconcile(instant("2026-08-03T07:00:00Z"), utc)
        fixture.alarmScheduler.events.clear()
        fixture.scheduler.reconcile(instant("2026-08-03T07:01:00Z"), utc)

        val desired = scheduledDaily("2026-08-03", "2026-08-03T08:00:00Z")
        assertEquals(
            listOf(Cancel(legacyIdentity), Schedule(desired.asAlarm())),
            fixture.alarmScheduler.events
        )
    }

    @Test
    fun acknowledgePersistsTodayClearsSnoozeAndSchedulesTomorrow() {
        val daily = scheduledDaily("2026-08-03", "2026-08-03T08:00:00Z")
        val fixture = fixture(
            MorningReviewLocalState(
                pendingSnoozeDate = LocalDate.of(2026, 8, 3),
                pendingSnoozeAt = instant("2026-08-03T07:10:00Z"),
                scheduledAlarm = daily
            )
        )

        fixture.scheduler.acknowledge(instant("2026-08-03T07:00:00Z"), utc)

        val tomorrow = scheduledDaily("2026-08-04", "2026-08-04T08:00:00Z")
        assertEquals(
            listOf(Cancel(legacyIdentity), Cancel(daily.identity()), Schedule(tomorrow.asAlarm())),
            fixture.alarmScheduler.events
        )
        assertEquals(
            MorningReviewLocalState(
                lastAcknowledgedDate = LocalDate.of(2026, 8, 3),
                scheduledAlarm = tomorrow
            ),
            fixture.store.read()
        )
    }

    @Test
    fun snoozePersistsTodayPlusFiveMinutesAndReplacesTheDailyAlarm() {
        val daily = scheduledDaily("2026-08-03", "2026-08-03T08:00:00Z")
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = daily))

        fixture.scheduler.snooze(instant("2026-08-03T07:00:00Z"), utc)

        val snooze = scheduled(
            reviewDate = "2026-08-03",
            reason = MorningReviewAlarmReason.Snooze,
            triggerAt = "2026-08-03T07:05:00Z"
        )
        assertEquals(
            listOf(Cancel(legacyIdentity), Cancel(daily.identity()), Schedule(snooze.asAlarm())),
            fixture.alarmScheduler.events
        )
        assertEquals(
            MorningReviewLocalState(
                pendingSnoozeDate = LocalDate.of(2026, 8, 3),
                pendingSnoozeAt = instant("2026-08-03T07:05:00Z"),
                scheduledAlarm = snooze
            ),
            fixture.store.read()
        )
    }

    @Test
    fun consumingAValidSnoozeClearsMetadataAndSchedulesTheNextDailyAlarm() {
        val snooze = scheduled(
            reviewDate = "2026-08-03",
            reason = MorningReviewAlarmReason.Snooze,
            triggerAt = "2026-08-03T08:05:00Z"
        )
        val fixture = fixture(
            MorningReviewLocalState(
                pendingSnoozeDate = LocalDate.of(2026, 8, 3),
                pendingSnoozeAt = snooze.triggerAt,
                scheduledAlarm = snooze
            )
        )

        fixture.scheduler.consume(snooze, instant("2026-08-03T08:05:00Z"), utc)

        val tomorrow = scheduledDaily("2026-08-04", "2026-08-04T08:00:00Z")
        assertEquals(
            listOf(Cancel(legacyIdentity), Cancel(snooze.identity()), Schedule(tomorrow.asAlarm())),
            fixture.alarmScheduler.events
        )
        assertEquals(MorningReviewLocalState(scheduledAlarm = tomorrow), fixture.store.read())
    }

    @Test
    fun staleFiredOccurrenceIsIgnoredWithoutMutation() {
        val current = scheduledDaily("2026-08-03", "2026-08-03T08:00:00Z")
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = current))
        val stale = scheduledDaily("2026-08-02", "2026-08-02T08:00:00Z")

        fixture.scheduler.consume(stale, instant("2026-08-03T08:00:00Z"), utc)

        assertEquals(emptyList(), fixture.alarmScheduler.events)
        assertEquals(MorningReviewLocalState(scheduledAlarm = current), fixture.store.read())
    }

    @Test
    fun deferForPriorityReplacesTheFiredAlarmWithOneMinutePriorityRetry() {
        val daily = scheduledDaily("2026-08-03", "2026-08-03T08:00:00Z")
        val fixture = fixture(MorningReviewLocalState(scheduledAlarm = daily))

        fixture.scheduler.deferForPriority(daily, instant("2026-08-03T08:00:00Z"), utc)

        val retry = scheduled(
            reviewDate = "2026-08-03",
            reason = MorningReviewAlarmReason.PriorityRetry,
            triggerAt = "2026-08-03T08:01:00Z"
        )
        assertEquals(
            listOf(Cancel(legacyIdentity), Cancel(daily.identity()), Schedule(retry.asAlarm())),
            fixture.alarmScheduler.events
        )
        assertEquals(MorningReviewLocalState(scheduledAlarm = retry), fixture.store.read())
    }

    @Test
    fun transientScheduleFailureCleansUpAndRetryArmsTheDesiredAlarm() {
        val old = scheduledDaily("2026-08-02", "2026-08-02T08:00:00Z")
        val fixture = fixture(
            initialState = MorningReviewLocalState(scheduledAlarm = old),
            scheduleFailuresRemaining = 1
        )

        assertFailsWith<IllegalStateException> {
            fixture.scheduler.reconcile(instant("2026-08-03T07:00:00Z"), utc)
        }

        val desired = scheduledDaily("2026-08-03", "2026-08-03T08:00:00Z")
        assertEquals(
            listOf(
                Cancel(legacyIdentity),
                Cancel(old.identity()),
                Schedule(desired.asAlarm()),
                Cancel(desired.identity())
            ),
            fixture.alarmScheduler.events
        )
        assertEquals(MorningReviewLocalState(scheduledAlarm = old), fixture.store.read())

        fixture.alarmScheduler.events.clear()
        fixture.scheduler.reconcile(instant("2026-08-03T07:00:00Z"), utc)

        assertEquals(
            listOf(Cancel(legacyIdentity), Cancel(old.identity()), Schedule(desired.asAlarm())),
            fixture.alarmScheduler.events
        )
        assertEquals(MorningReviewLocalState(scheduledAlarm = desired), fixture.store.read())
    }

    @Test
    fun transientStorageFailureAfterWriteCleansUpAndRetryRearmsTheSameDesiredIdentity() {
        val old = scheduledDaily("2026-08-02", "2026-08-02T08:00:00Z")
        val fixture = fixture(
            initialState = MorningReviewLocalState(scheduledAlarm = old),
            storageFailuresAfterWriteRemaining = 1
        )

        assertFailsWith<IllegalStateException> {
            fixture.scheduler.reconcile(instant("2026-08-03T07:00:00Z"), utc)
        }

        val desired = scheduledDaily("2026-08-03", "2026-08-03T08:00:00Z")
        assertEquals(
            listOf(
                Cancel(legacyIdentity),
                Cancel(old.identity()),
                Schedule(desired.asAlarm()),
                Cancel(desired.identity())
            ),
            fixture.alarmScheduler.events
        )
        assertEquals(MorningReviewLocalState(scheduledAlarm = desired), fixture.store.read())

        fixture.alarmScheduler.events.clear()
        fixture.scheduler.reconcile(instant("2026-08-03T07:00:00Z"), utc)

        assertEquals(
            listOf(Cancel(legacyIdentity), Schedule(desired.asAlarm())),
            fixture.alarmScheduler.events
        )
        assertEquals(MorningReviewLocalState(scheduledAlarm = desired), fixture.store.read())
    }

    private fun fixture(
        initialState: MorningReviewLocalState = MorningReviewLocalState(),
        scheduleFailuresRemaining: Int = 0,
        storageFailuresAfterWriteRemaining: Int = 0
    ): Fixture {
        val storage = TestStorage(initialState, storageFailuresAfterWriteRemaining)
        val store = MorningReviewLocalStateStore(storage)
        val alarmScheduler = RecordingAlarmScheduler(scheduleFailuresRemaining)
        return Fixture(
            store = store,
            alarmScheduler = alarmScheduler,
            scheduler = MorningReviewScheduler(
                stateStore = store,
                alarmScheduler = alarmScheduler,
                calculator = calculator,
                snoozeDuration = Duration.ofMinutes(5),
                priorityRetryDuration = Duration.ofMinutes(1)
            )
        )
    }

    private data class Fixture(
        val store: MorningReviewLocalStateStore,
        val alarmScheduler: RecordingAlarmScheduler,
        val scheduler: MorningReviewScheduler
    )

    private sealed interface AlarmEvent
    private data class Schedule(val alarm: ScheduledAlarm) : AlarmEvent
    private data class Cancel(val identity: ScheduledAlarmIdentity) : AlarmEvent

    private class RecordingAlarmScheduler(
        private var scheduleFailuresRemaining: Int
    ) : AlarmScheduler {
        val events = mutableListOf<AlarmEvent>()

        override fun schedule(alarm: ScheduledAlarm) {
            events += Schedule(alarm)
            if (scheduleFailuresRemaining > 0) {
                scheduleFailuresRemaining -= 1
                error("alarm manager unavailable")
            }
        }

        override fun cancel(identity: ScheduledAlarmIdentity) {
            events += Cancel(identity)
        }
    }

    private inner class TestStorage(
        initialState: MorningReviewLocalState,
        private var failuresAfterWriteRemaining: Int
    ) : MorningReviewStateStorage {
        private var values = encode(initialState)

        override fun read(): Map<String, String?> = values.toMap()

        override fun replace(values: Map<String, String?>) {
            this.values = values.toMap()
            if (failuresAfterWriteRemaining > 0) {
                failuresAfterWriteRemaining -= 1
                error("storage unavailable after write")
            }
        }
    }

    private fun encode(state: MorningReviewLocalState): Map<String, String?> {
        var encodedValues = emptyMap<String, String?>()
        MorningReviewLocalStateStore(object : MorningReviewStateStorage {
            override fun read(): Map<String, String?> = encodedValues
            override fun replace(values: Map<String, String?>) {
                encodedValues = values
            }
        }).replace(state)
        return encodedValues
    }

    private fun MorningReviewScheduledAlarm.asAlarm() = ScheduledAlarm(
        taskId = MORNING_REVIEW_TASK_ID,
        occurrenceKey = occurrenceKey,
        kind = AlarmKind.MORNING_REVIEW,
        triggerAtEpochMillis = triggerAt.toEpochMilli()
    )

    private fun MorningReviewScheduledAlarm.identity() = asAlarm().identity

    private fun scheduledDaily(reviewDate: String, triggerAt: String): MorningReviewScheduledAlarm =
        scheduled(reviewDate, MorningReviewAlarmReason.Daily, triggerAt)

    private fun scheduled(
        reviewDate: String,
        reason: MorningReviewAlarmReason,
        triggerAt: String
    ): MorningReviewScheduledAlarm {
        val trigger = instant(triggerAt)
        return MorningReviewScheduledAlarm(
            reviewDate = LocalDate.parse(reviewDate),
            reason = reason,
            triggerAt = trigger,
            occurrenceKey = "morning-review:${reason.name.lowercase()}:$reviewDate:${trigger.toEpochMilli()}"
        )
    }

    private fun instant(value: String): Instant = Instant.parse(value)

    private companion object {
        const val MORNING_REVIEW_TASK_ID = "morning-review"
        val legacyIdentity = ScheduledAlarmIdentity(
            taskId = MORNING_REVIEW_TASK_ID,
            occurrenceKey = MORNING_REVIEW_TASK_ID,
            kind = AlarmKind.MORNING_REVIEW
        )
    }
}
