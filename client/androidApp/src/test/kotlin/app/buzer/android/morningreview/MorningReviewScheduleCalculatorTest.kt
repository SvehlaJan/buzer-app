package app.buzer.android.morningreview

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MorningReviewScheduleCalculatorTest {
    private val calculator = MorningReviewScheduleCalculator(reviewTime = LocalTime.of(8, 0))
    private val utc = ZoneId.of("UTC")

    @Test
    fun unacknowledgedAt0759SchedulesTodayAt0800() {
        val alarm = calculator.desiredAlarm(MorningReviewLocalState(), instant("2026-08-03T07:59:00Z"), utc)

        assertAlarm(alarm, "2026-08-03", MorningReviewAlarmReason.Daily, "2026-08-03T08:00:00Z")
    }

    @Test
    fun atAndAfter0800SchedulesTomorrowAndRequiresCatchUp() {
        listOf("2026-08-03T08:00:00Z", "2026-08-03T08:01:00Z").forEach { now ->
            val state = MorningReviewLocalState()

            assertAlarm(calculator.desiredAlarm(state, instant(now), utc), "2026-08-04", MorningReviewAlarmReason.Daily, "2026-08-04T08:00:00Z")
            assertTrue(calculator.availability(state, ownerBlockingCount = 0, now = instant(now), zoneId = utc).catchUpRequired)
        }
    }

    @Test
    fun acknowledgedTodaySchedulesTomorrowIncludingWeekends() {
        val now = instant("2026-08-08T07:00:00Z")
        val state = MorningReviewLocalState(lastAcknowledgedDate = LocalDate.of(2026, 8, 8))

        assertAlarm(calculator.desiredAlarm(state, now, utc), "2026-08-09", MorningReviewAlarmReason.Daily, "2026-08-09T08:00:00Z")
    }

    @Test
    fun earlyAcknowledgementRemovesTodaysAlarm() {
        val now = instant("2026-08-03T07:59:00Z")
        val state = MorningReviewLocalState(lastAcknowledgedDate = LocalDate.of(2026, 8, 3))

        assertAlarm(calculator.desiredAlarm(state, now, utc), "2026-08-04", MorningReviewAlarmReason.Daily, "2026-08-04T08:00:00Z")
    }

    @Test
    fun yesterdaysAcknowledgementDoesNotSuppressTodaysReview() {
        val now = instant("2026-08-03T07:59:00Z")
        val state = MorningReviewLocalState(lastAcknowledgedDate = LocalDate.of(2026, 8, 2))

        assertAlarm(calculator.desiredAlarm(state, now, utc), "2026-08-03", MorningReviewAlarmReason.Daily, "2026-08-03T08:00:00Z")
        calculator.availability(state, ownerBlockingCount = 0, now = now, zoneId = utc).also {
            assertEquals(LocalDate.of(2026, 8, 3), it.today)
            assertTrue(it.bannerVisible)
            assertFalse(it.catchUpRequired)
            assertEquals(instant("2026-08-03T08:00:00Z"), it.nextReevaluationAt)
        }
    }

    @Test
    fun availabilityRecomputesTodayAndBoundaryAcrossLocalMidnight() {
        val state = MorningReviewLocalState(lastAcknowledgedDate = LocalDate.of(2026, 8, 3))
        val beforeMidnight = calculator.availability(
            state,
            ownerBlockingCount = 0,
            now = instant("2026-08-03T23:59:59Z"),
            zoneId = utc
        )
        val afterMidnight = calculator.availability(
            state,
            ownerBlockingCount = 0,
            now = instant("2026-08-04T00:00:00Z"),
            zoneId = utc
        )

        assertEquals(LocalDate.of(2026, 8, 3), beforeMidnight.today)
        assertFalse(beforeMidnight.bannerVisible)
        assertEquals(instant("2026-08-04T00:00:00Z"), beforeMidnight.nextReevaluationAt)
        assertEquals(LocalDate.of(2026, 8, 4), afterMidnight.today)
        assertTrue(afterMidnight.bannerVisible)
        assertEquals(instant("2026-08-04T08:00:00Z"), afterMidnight.nextReevaluationAt)
    }

    @Test
    fun activeCurrentDateSnoozeWinsAndSuppressesBannerAndCatchUp() {
        val now = instant("2026-08-03T09:00:00Z")
        val state = MorningReviewLocalState(
            pendingSnoozeDate = LocalDate.of(2026, 8, 3),
            pendingSnoozeAt = instant("2026-08-03T09:05:00Z")
        )

        assertAlarm(calculator.desiredAlarm(state, now, utc), "2026-08-03", MorningReviewAlarmReason.Snooze, "2026-08-03T09:05:00Z")
        calculator.availability(state, ownerBlockingCount = 0, now = now, zoneId = utc).also {
            assertTrue(it.snoozedNow)
            assertFalse(it.bannerVisible)
            assertFalse(it.catchUpRequired)
        }
    }

    @Test
    fun expiredAndWrongDateSnoozesAreClearedBeforeScheduling() {
        val now = instant("2026-08-03T09:00:00Z")
        val expired = MorningReviewLocalState(
            pendingSnoozeDate = LocalDate.of(2026, 8, 3),
            pendingSnoozeAt = instant("2026-08-03T08:59:00Z")
        )
        val wrongDate = MorningReviewLocalState(
            pendingSnoozeDate = LocalDate.of(2026, 8, 4),
            pendingSnoozeAt = instant("2026-08-03T09:05:00Z")
        )

        assertEquals(MorningReviewLocalState(), calculator.normalize(expired, now, utc))
        assertEquals(MorningReviewLocalState(), calculator.normalize(wrongDate, now, utc))
        assertAlarm(calculator.desiredAlarm(expired, now, utc), "2026-08-04", MorningReviewAlarmReason.Daily, "2026-08-04T08:00:00Z")
    }

    @Test
    fun ownerBlockingReopensBannerWithoutCatchUpAfterAcknowledgement() {
        val now = instant("2026-08-03T09:00:00Z")
        val state = MorningReviewLocalState(lastAcknowledgedDate = LocalDate.of(2026, 8, 3))

        calculator.availability(state, ownerBlockingCount = 2, now = now, zoneId = utc).also {
            assertTrue(it.bannerVisible)
            assertFalse(it.catchUpRequired)
        }
    }

    @Test
    fun nextReevaluationUses0800MidnightOrSnoozeExpiryAcrossDst() {
        val bratislava = ZoneId.of("Europe/Bratislava")
        assertEquals(
            instant("2026-03-29T06:00:00Z"),
            calculator.availability(MorningReviewLocalState(), 0, instant("2026-03-29T05:30:00Z"), bratislava).nextReevaluationAt
        )
        assertEquals(
            instant("2026-03-29T22:00:00Z"),
            calculator.availability(MorningReviewLocalState(), 0, instant("2026-03-29T07:00:00Z"), bratislava).nextReevaluationAt
        )
        val snoozed = MorningReviewLocalState(
            pendingSnoozeDate = LocalDate.of(2026, 3, 29),
            pendingSnoozeAt = instant("2026-03-29T07:05:00Z")
        )
        assertEquals(
            instant("2026-03-29T07:05:00Z"),
            calculator.availability(snoozed, 0, instant("2026-03-29T07:00:00Z"), bratislava).nextReevaluationAt
        )
    }

    @Test
    fun zoneChangesRecomputeWallClockAndNeverRepeatAcknowledgedDate() {
        val state = MorningReviewLocalState(lastAcknowledgedDate = LocalDate.of(2026, 8, 3))
        val now = instant("2026-08-02T23:30:00Z")

        assertAlarm(calculator.desiredAlarm(state, now, ZoneId.of("Europe/Bratislava")), "2026-08-04", MorningReviewAlarmReason.Daily, "2026-08-04T06:00:00Z")
        assertAlarm(calculator.desiredAlarm(state, now, utc), "2026-08-04", MorningReviewAlarmReason.Daily, "2026-08-04T08:00:00Z")
    }

    @Test
    fun bratislavaDstSchedulingUsesCalendarDatesWith23And25HourSeparations() {
        val bratislava = ZoneId.of("Europe/Bratislava")
        val springFirst = calculator.desiredAlarm(MorningReviewLocalState(), instant("2026-03-28T06:00:00Z"), bratislava)
        val springSecond = calculator.desiredAlarm(MorningReviewLocalState(lastAcknowledgedDate = LocalDate.of(2026, 3, 28)), instant("2026-03-28T06:00:00Z"), bratislava)
        val fallFirst = calculator.desiredAlarm(MorningReviewLocalState(), instant("2026-10-24T05:59:00Z"), bratislava)
        val fallSecond = calculator.desiredAlarm(MorningReviewLocalState(lastAcknowledgedDate = LocalDate.of(2026, 10, 24)), instant("2026-10-24T05:59:00Z"), bratislava)

        assertEquals(Duration.ofHours(23), Duration.between(springFirst.triggerAt, springSecond.triggerAt))
        assertEquals(Duration.ofHours(25), Duration.between(fallFirst.triggerAt, fallSecond.triggerAt))
    }

    @Test
    fun productionOccurrenceKeysAreStableAndNonblank() {
        val now = instant("2026-08-03T07:59:00Z")
        val first = calculator.desiredAlarm(MorningReviewLocalState(), now, utc)
        val second = MorningReviewScheduleCalculator().desiredAlarm(MorningReviewLocalState(), now, utc)

        assertEquals(first.occurrenceKey, second.occurrenceKey)
        assertTrue(first.occurrenceKey.isNotBlank())
        assertTrue(first.occurrenceKey.contains(":daily:2026-08-03:"))
        assertNotEquals("morning-review", first.occurrenceKey)
    }

    private fun assertAlarm(
        alarm: MorningReviewScheduledAlarm,
        reviewDate: String,
        reason: MorningReviewAlarmReason,
        triggerAt: String
    ) {
        assertEquals(LocalDate.parse(reviewDate), alarm.reviewDate)
        assertEquals(reason, alarm.reason)
        assertEquals(instant(triggerAt), alarm.triggerAt)
    }

    private fun instant(value: String): Instant = Instant.parse(value)
}
