package app.buzer.android.morningreview

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

data class MorningReviewAvailability(
    val today: LocalDate,
    val bannerVisible: Boolean,
    val catchUpRequired: Boolean,
    val snoozedNow: Boolean,
    val nextReevaluationAt: Instant
)

class MorningReviewScheduleCalculator(
    private val reviewTime: LocalTime = LocalTime.of(8, 0)
) {
    fun desiredAlarm(
        state: MorningReviewLocalState,
        now: Instant,
        zoneId: ZoneId
    ): MorningReviewScheduledAlarm {
        val normalizedState = normalize(state, now, zoneId)
        val nowZoned = now.atZone(zoneId)
        val today = nowZoned.toLocalDate()
        val snoozeAt = normalizedState.pendingSnoozeAt

        if (normalizedState.pendingSnoozeDate == today && snoozeAt?.isAfter(now) == true) {
            return alarmFor(today, MorningReviewAlarmReason.Snooze, snoozeAt)
        }

        var reviewDate = if (
            normalizedState.lastAcknowledgedDate != today && nowZoned.toLocalTime().isBefore(reviewTime)
        ) {
            today
        } else {
            today.plusDays(1)
        }
        while (reviewDate == normalizedState.lastAcknowledgedDate) {
            reviewDate = reviewDate.plusDays(1)
        }

        return alarmFor(
            reviewDate,
            MorningReviewAlarmReason.Daily,
            reviewDate.atTime(reviewTime).atZone(zoneId).toInstant()
        )
    }

    fun availability(
        state: MorningReviewLocalState,
        ownerBlockingCount: Int,
        now: Instant,
        zoneId: ZoneId
    ): MorningReviewAvailability {
        val normalizedState = normalize(state, now, zoneId)
        val nowZoned = now.atZone(zoneId)
        val today = nowZoned.toLocalDate()
        val todayAtReviewTime = today.atTime(reviewTime).atZone(zoneId).toInstant()
        val snoozeAt = normalizedState.pendingSnoozeAt
        val snoozedNow = normalizedState.pendingSnoozeDate == today && snoozeAt?.isAfter(now) == true
        val reevaluations = buildList {
            add(today.plusDays(1).atStartOfDay(zoneId).toInstant())
            if (todayAtReviewTime.isAfter(now)) add(todayAtReviewTime)
            if (snoozedNow) add(requireNotNull(snoozeAt))
        }

        return MorningReviewAvailability(
            today = today,
            bannerVisible = !snoozedNow && (
                normalizedState.lastAcknowledgedDate != today || ownerBlockingCount > 0
            ),
            catchUpRequired = !snoozedNow &&
                normalizedState.lastAcknowledgedDate != today &&
                !nowZoned.toLocalTime().isBefore(reviewTime),
            snoozedNow = snoozedNow,
            nextReevaluationAt = reevaluations.minOrNull()!!
        )
    }

    fun normalize(
        state: MorningReviewLocalState,
        now: Instant,
        zoneId: ZoneId
    ): MorningReviewLocalState {
        val today = now.atZone(zoneId).toLocalDate()
        val snoozeIsActive = state.pendingSnoozeDate == today && state.pendingSnoozeAt?.isAfter(now) == true
        return if (snoozeIsActive) state else state.copy(pendingSnoozeDate = null, pendingSnoozeAt = null)
    }

    private fun alarmFor(
        reviewDate: LocalDate,
        reason: MorningReviewAlarmReason,
        triggerAt: Instant
    ): MorningReviewScheduledAlarm = MorningReviewScheduledAlarm(
        reviewDate = reviewDate,
        reason = reason,
        triggerAt = triggerAt,
        occurrenceKey = "morning-review:${reason.name.lowercase(Locale.ROOT)}:$reviewDate:${triggerAt.toEpochMilli()}"
    )
}
