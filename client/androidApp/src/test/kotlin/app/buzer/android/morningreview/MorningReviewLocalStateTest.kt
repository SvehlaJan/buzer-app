package app.buzer.android.morningreview

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MorningReviewLocalStateTest {
    @Test
    fun emptyStorageReadsDefaultState() {
        val store = MorningReviewLocalStateStore(InMemoryMorningReviewStateStorage())

        assertEquals(MorningReviewLocalState(), store.read())
    }

    @Test
    fun completeStateRoundTrips() {
        val storage = InMemoryMorningReviewStateStorage()
        val store = MorningReviewLocalStateStore(storage)
        val state = MorningReviewLocalState(
            lastAcknowledgedDate = LocalDate.of(2026, 8, 2),
            pendingSnoozeDate = LocalDate.of(2026, 8, 3),
            pendingSnoozeAt = Instant.parse("2026-08-03T06:05:00Z"),
            scheduledAlarm = MorningReviewScheduledAlarm(
                reviewDate = LocalDate.of(2026, 8, 3),
                reason = MorningReviewAlarmReason.Snooze,
                triggerAt = Instant.parse("2026-08-03T06:05:00Z"),
                occurrenceKey = "morning-review:snooze:2026-08-03:1785737100000"
            )
        )

        store.replace(state)

        assertEquals(state, store.read())
    }

    @Test
    fun malformedOptionalFieldsFallBackSafely() {
        val store = MorningReviewLocalStateStore(
            InMemoryMorningReviewStateStorage(
                mapOf(
                    "last_acknowledged_date" to "not-a-date",
                    "pending_snooze_date" to "2026-08-03",
                    "pending_snooze_at" to "not-an-instant",
                    "scheduled_review_date" to "2026-08-03",
                    "scheduled_reason" to "not-a-reason",
                    "scheduled_trigger_at" to "not-an-instant",
                    "scheduled_occurrence_key" to "bad"
                )
            )
        )

        val state = store.read()

        assertNull(state.lastAcknowledgedDate)
        assertEquals(LocalDate.of(2026, 8, 3), state.pendingSnoozeDate)
        assertNull(state.pendingSnoozeAt)
        assertNull(state.scheduledAlarm)
    }

    @Test
    fun wholeStateReplacementClearsSnoozeMetadata() {
        val storage = InMemoryMorningReviewStateStorage()
        val store = MorningReviewLocalStateStore(storage)
        store.replace(
            MorningReviewLocalState(
                pendingSnoozeDate = LocalDate.of(2026, 8, 3),
                pendingSnoozeAt = Instant.parse("2026-08-03T06:05:00Z")
            )
        )

        store.replace(MorningReviewLocalState(lastAcknowledgedDate = LocalDate.of(2026, 8, 3)))

        assertEquals(
            MorningReviewLocalState(lastAcknowledgedDate = LocalDate.of(2026, 8, 3)),
            store.read()
        )
    }

    @Test
    fun failingCommitPropagatesFromReplacement() {
        val store = MorningReviewLocalStateStore(
            InMemoryMorningReviewStateStorage(commitSucceeds = { false })
        )

        assertFailsWith<IllegalStateException> {
            store.replace(MorningReviewLocalState(lastAcknowledgedDate = LocalDate.of(2026, 8, 3)))
        }
    }
}
