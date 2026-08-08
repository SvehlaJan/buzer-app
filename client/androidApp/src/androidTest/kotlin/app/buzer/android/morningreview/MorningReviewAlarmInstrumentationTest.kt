package app.buzer.android.morningreview

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.AlarmReceiver
import app.buzer.android.enforcement.AndroidAlarmScheduler
import app.buzer.android.enforcement.ScheduledAlarmIdentity
import app.buzer.android.enforcement.EnforcementDependencies
import app.buzer.android.blockers.FakeBackendClient
import java.time.Instant
import java.time.ZoneId
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class MorningReviewAlarmInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val stateStore = MorningReviewLocalStateStore.fromContext(context)
    private val fakeBackend = FakeBackendClient()

    @Before
    @After
    fun clearMorningReviewState() {
        MorningReviewRuntimeClock.setForTests(null)
        File(context.filesDir, "latest_snapshot.json").delete()
        context.getSystemService(NotificationManager::class.java).cancelAll()
        stateStore.read().scheduledAlarm?.let { alarm ->
            AndroidAlarmScheduler(context).cancel(
                ScheduledAlarmIdentity(MORNING_REVIEW_TASK_ID, alarm.occurrenceKey, AlarmKind.MORNING_REVIEW)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(AlarmManager::class.java).cancelAll()
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
                0
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
        grantNotifications()
        val notificationId = AlarmIntentFactory.requestCode(MORNING_REVIEW_TASK_ID, fired.occurrenceKey, AlarmKind.MORNING_REVIEW)
        val notifications = context.getSystemService(NotificationManager::class.java)

        receiver.onReceive(context, AlarmIntentFactory.receiverIntent(context, stale.taskId, stale.occurrenceKey, stale.kind))
        assertEquals(fired, stateStore.read().scheduledAlarm)
        assertTrue(notifications.activeNotifications.none { it.id == notificationId })

        receiver.onReceive(
            context,
            AlarmIntentFactory.receiverIntent(context, MORNING_REVIEW_TASK_ID, fired.occurrenceKey, AlarmKind.MORNING_REVIEW)
        )

        val posted = waitForNotification(notifications, notificationId)
        assertEquals(1, posted.size)
        assertEquals("Morning review", posted.single().notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("blockers", posted.single().notification.channelId)
        assertTrue(posted.single().notification.fullScreenIntent != null)
    }

    @Test
    fun morningReviewUsesAlarmClockSchedulingPath() {
        val scheduler = MorningReviewDependencies.scheduler(context)
        scheduler.snooze(Instant.now(), ZoneId.systemDefault())
        val scheduled = requireNotNull(stateStore.read().scheduledAlarm)
        val firstTrigger = requireNotNull(context.getSystemService(AlarmManager::class.java).nextAlarmClock).triggerTime
        scheduler.reconcile(Instant.now(), ZoneId.systemDefault())
        val secondTrigger = requireNotNull(context.getSystemService(AlarmManager::class.java).nextAlarmClock).triggerTime

        assertEquals(MorningReviewAlarmReason.Snooze, scheduled.reason)
        assertEquals(scheduled.triggerAt.toEpochMilli(), firstTrigger)
        assertEquals(firstTrigger, secondTrigger)
    }

    @Test
    fun cachedStartAndDeadlinePriorityDeferReviewThenClearedCacheAllowsRetryDelivery() {
        listOf("today", "priorityDeadline").forEach { scenario ->
        clearMorningReviewState()
        fakeBackend.reset(scenario)
        runBlocking { EnforcementDependencies.coordinator(context).forceSyncAndReconcile() }
        grantNotifications()
        val scheduler = MorningReviewDependencies.scheduler(context)
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        scheduler.snooze(now, zone)
        val fired = requireNotNull(stateStore.read().scheduledAlarm)
        val receiver = AlarmReceiver()
        val notifications = context.getSystemService(NotificationManager::class.java)
        val initialNotification = AlarmIntentFactory.requestCode(MORNING_REVIEW_TASK_ID, fired.occurrenceKey, AlarmKind.MORNING_REVIEW)

        receiver.onReceive(context, AlarmIntentFactory.receiverIntent(context, MORNING_REVIEW_TASK_ID, fired.occurrenceKey, AlarmKind.MORNING_REVIEW))
        val retry = requireNotNull(stateStore.read().scheduledAlarm)
        assertEquals(MorningReviewAlarmReason.PriorityRetry, retry.reason)
        assertTrue(notifications.activeNotifications.none { it.id == initialNotification })

        File(context.filesDir, "latest_snapshot.json").delete()
        receiver.onReceive(context, AlarmIntentFactory.receiverIntent(context, MORNING_REVIEW_TASK_ID, retry.occurrenceKey, AlarmKind.MORNING_REVIEW))
        val notificationId = AlarmIntentFactory.requestCode(MORNING_REVIEW_TASK_ID, retry.occurrenceKey, AlarmKind.MORNING_REVIEW)
        assertEquals(1, waitForNotification(notifications, notificationId).size)
        notifications.cancel(notificationId)
        }
    }

    private fun grantNotifications() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            .close()
    }

    private fun waitForNotification(
        notifications: NotificationManager,
        notificationId: Int
    ): List<android.service.notification.StatusBarNotification> {
        repeat(20) {
            val posted = notifications.activeNotifications.filter { it.id == notificationId }
            if (posted.size == 1) return posted
            Thread.sleep(100)
        }
        return notifications.activeNotifications.filter { it.id == notificationId }
    }
}
