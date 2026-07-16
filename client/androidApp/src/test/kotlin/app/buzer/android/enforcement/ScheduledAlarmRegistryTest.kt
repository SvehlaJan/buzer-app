package app.buzer.android.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduledAlarmRegistryTest {
    @Test
    fun reconciliationCancelsOnlyOldOccurrenceWhenTaskAndDueStayTheSame() {
        val oldAlarm = alarm("task-1", "occurrence-old", AlarmKind.START_BLOCKER, 1_000)
        val newAlarm = alarm("task-1", "occurrence-new", AlarmKind.START_BLOCKER, 1_000)
        val storage = FakeRegistryStorage(setOf(oldAlarm.identity))
        val scheduler = RecordingAlarmScheduler()
        val registry = ScheduledAlarmRegistry(storage)

        registry.reconcile(listOf(newAlarm), scheduler)

        assertEquals(listOf(oldAlarm.identity), scheduler.cancelled)
        assertEquals(listOf(newAlarm), scheduler.scheduled)
        assertEquals(setOf(newAlarm.identity), storage.identities)
    }

    @Test
    fun reconstructedRegistryCancelsObsoleteEntryDuringBootReconciliation() {
        val oldAlarm = alarm("task-1", "occurrence-old", AlarmKind.DEADLINE_BLOCKER, 2_000)
        val currentAlarm = alarm("task-1", "occurrence-current", AlarmKind.DEADLINE_BLOCKER, 3_000)
        val storage = FakeRegistryStorage(setOf(oldAlarm.identity))
        val scheduler = RecordingAlarmScheduler()

        ScheduledAlarmRegistry(storage).reconcile(listOf(currentAlarm), scheduler)

        assertEquals(listOf(oldAlarm.identity), scheduler.cancelled)
        assertEquals(setOf(currentAlarm.identity), storage.identities)
    }

    @Test
    fun cancellationForTaskUsesEveryPersistedOccurrenceIdentity() {
        val first = alarm("task-1", "occurrence-one", AlarmKind.START_BLOCKER, 1_000)
        val second = alarm("task-1", "occurrence-two", AlarmKind.DEADLINE_BLOCKER, 2_000)
        val unrelated = alarm("task-2", "occurrence-one", AlarmKind.START_BLOCKER, 3_000)
        val storage = FakeRegistryStorage(setOf(first.identity, second.identity, unrelated.identity))
        val scheduler = RecordingAlarmScheduler()

        ScheduledAlarmRegistry(storage).cancelAllForTask("task-1", scheduler)

        assertEquals(setOf(first.identity, second.identity), scheduler.cancelled.toSet())
        assertEquals(setOf(unrelated.identity), storage.identities)
    }

    @Test
    fun schedulerFailureCancelsAttemptedDesiredIdentityAndRetainsOldRegistry() {
        val oldAlarm = alarm("task-1", "occurrence-old", AlarmKind.START_BLOCKER, 1_000)
        val desiredAlarm = alarm("task-1", "occurrence-new", AlarmKind.START_BLOCKER, 2_000)
        val storage = FakeRegistryStorage(setOf(oldAlarm.identity))
        val scheduler = ThrowingAlarmScheduler(failSchedule = true)

        kotlin.test.assertFailsWith<IllegalStateException> {
            ScheduledAlarmRegistry(storage).reconcile(listOf(desiredAlarm), scheduler)
        }

        assertEquals(setOf(oldAlarm.identity), storage.identities)
        assertEquals(setOf(oldAlarm.identity, desiredAlarm.identity), scheduler.cancelled.toSet())
    }

    @Test
    fun persistenceFailureCancelsDesiredIdentityAndRetainsOldRegistry() {
        val oldAlarm = alarm("task-1", "occurrence-old", AlarmKind.DEADLINE_BLOCKER, 1_000)
        val desiredAlarm = alarm("task-1", "occurrence-new", AlarmKind.DEADLINE_BLOCKER, 2_000)
        val storage = ThrowingRegistryStorage(setOf(oldAlarm.identity))
        val scheduler = RecordingAlarmScheduler()

        kotlin.test.assertFailsWith<IllegalStateException> {
            ScheduledAlarmRegistry(storage).reconcile(listOf(desiredAlarm), scheduler)
        }

        assertEquals(setOf(oldAlarm.identity), storage.identities)
        assertEquals(setOf(oldAlarm.identity, desiredAlarm.identity), scheduler.cancelled.toSet())
    }

    @Test
    fun failureDoesNotCancelExistingIdentityThatRemainsDesired() {
        val retained = alarm("task-1", "occurrence-retained", AlarmKind.START_BLOCKER, 1_000)
        val newlyAttempted = alarm("task-2", "occurrence-new", AlarmKind.DEADLINE_BLOCKER, 2_000)
        val storage = FakeRegistryStorage(setOf(retained.identity))
        val scheduler = ThrowingAlarmScheduler(failSchedule = true)

        kotlin.test.assertFailsWith<IllegalStateException> {
            ScheduledAlarmRegistry(storage).reconcile(listOf(retained, newlyAttempted), scheduler)
        }

        assertEquals(setOf(retained.identity), storage.identities)
        assertEquals(listOf(newlyAttempted.identity), scheduler.cancelled)
    }

    private fun alarm(
        taskId: String,
        occurrenceKey: String,
        kind: AlarmKind,
        triggerAtEpochMillis: Long
    ) = ScheduledAlarm(taskId, occurrenceKey, kind, triggerAtEpochMillis)

    private class FakeRegistryStorage(initial: Set<ScheduledAlarmIdentity>) : ScheduledAlarmRegistryStorage {
        var identities = initial

        override fun load(): Set<ScheduledAlarmIdentity> = identities

        override fun replace(identities: Set<ScheduledAlarmIdentity>) {
            this.identities = identities
        }
    }

    private class RecordingAlarmScheduler : AlarmScheduler {
        val scheduled = mutableListOf<ScheduledAlarm>()
        val cancelled = mutableListOf<ScheduledAlarmIdentity>()

        override fun schedule(alarm: ScheduledAlarm) {
            scheduled += alarm
        }

        override fun cancel(identity: ScheduledAlarmIdentity) {
            cancelled += identity
        }

    }

    private class ThrowingAlarmScheduler(
        private val failSchedule: Boolean
    ) : AlarmScheduler {
        val cancelled = mutableListOf<ScheduledAlarmIdentity>()

        override fun schedule(alarm: ScheduledAlarm) {
            if (failSchedule) error("scheduler unavailable")
        }

        override fun cancel(identity: ScheduledAlarmIdentity) {
            cancelled += identity
        }
    }

    private class ThrowingRegistryStorage(
        initial: Set<ScheduledAlarmIdentity>
    ) : ScheduledAlarmRegistryStorage {
        var identities = initial

        override fun load(): Set<ScheduledAlarmIdentity> = identities

        override fun replace(identities: Set<ScheduledAlarmIdentity>) {
            error("persistence unavailable")
        }
    }
}
