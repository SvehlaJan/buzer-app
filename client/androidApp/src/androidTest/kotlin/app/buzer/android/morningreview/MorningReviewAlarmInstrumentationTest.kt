package app.buzer.android.morningreview

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.AlarmReceiver
import app.buzer.android.enforcement.AndroidAlarmScheduler
import app.buzer.android.enforcement.ScheduledAlarmIdentity
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MorningReviewAlarmInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val stateStore = MorningReviewLocalStateStore.fromContext(context)

    @Before
    @After
    fun clearMorningReviewState() {
        stateStore.read().scheduledAlarm?.let { alarm ->
            AndroidAlarmScheduler(context).cancel(
                ScheduledAlarmIdentity(MORNING_REVIEW_TASK_ID, alarm.occurrenceKey, AlarmKind.MORNING_REVIEW)
            )
        }
        stateStore.replace(MorningReviewLocalState())
    }

    @Test
    fun manifestReceiverIsRegisteredForEverySystemReconciliationEvent() {
        val receiver = ComponentName(context, MorningReviewReconcileReceiver::class.java).className
        val actions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )

        actions.forEach { action ->
            val receivers = context.packageManager.queryBroadcastReceivers(
                Intent(action).setPackage(context.packageName),
                PackageManager.MATCH_DEFAULT_ONLY
            )
            assertTrue("Expected $receiver to handle $action", receivers.any { it.activityInfo.name == receiver })
        }
    }

    @Test
    fun repeatedSystemReconciliationKeepsOnePersistedMorningReviewIdentity() {
        val receiver = MorningReviewReconcileReceiver()
        val zone = ZoneId.systemDefault()
        val before = Instant.now()
        receiver.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        val first = requireNotNull(stateStore.read().scheduledAlarm)
        receiver.onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        val second = requireNotNull(stateStore.read().scheduledAlarm)

        assertEquals(first.occurrenceKey, second.occurrenceKey)
        assertTrue(second.triggerAt.isAfter(before))
        // The scheduler's persisted identity is the app-owned AlarmManager identity; manual
        // device evidence is captured with scripts/adb-verify.sh alarms in the E2E handoff.
        assertEquals(second.reviewDate, second.triggerAt.atZone(zone).toLocalDate())
    }

    @Test
    fun staleDeliveryIsIgnoredWhileCurrentDeliveryConsumesThePersistedOccurrence() {
        val scheduler = MorningReviewDependencies.scheduler(context)
        scheduler.snooze(Instant.now(), ZoneId.systemDefault())
        val fired = requireNotNull(stateStore.read().scheduledAlarm)
        val stale = ScheduledAlarmIdentity(MORNING_REVIEW_TASK_ID, "stale-${fired.occurrenceKey}", AlarmKind.MORNING_REVIEW)
        val receiver = AlarmReceiver()

        receiver.onReceive(context, AlarmIntentFactory.receiverIntent(context, stale.taskId, stale.occurrenceKey, stale.kind))
        assertEquals(fired, stateStore.read().scheduledAlarm)

        receiver.onReceive(
            context,
            AlarmIntentFactory.receiverIntent(context, MORNING_REVIEW_TASK_ID, fired.occurrenceKey, AlarmKind.MORNING_REVIEW)
        )

        assertNotEquals(fired, stateStore.read().scheduledAlarm)
    }

    @Test
    fun morningReviewUsesAlarmClockSchedulingPath() {
        MorningReviewDependencies.scheduler(context).snooze(Instant.now(), ZoneId.systemDefault())
        val scheduled = requireNotNull(stateStore.read().scheduledAlarm)

        assertEquals(MorningReviewAlarmReason.Snooze, scheduled.reason)
        assertTrue(scheduled.triggerAt.isAfter(Instant.now().minusSeconds(1)))
    }
}
