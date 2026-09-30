package app.buzer.android.morningreview

import android.Manifest
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
import java.time.LocalDate
import java.time.ZoneId
import java.io.File
import java.io.FileInputStream
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        grantNotifications()
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
        assertExactlyOneActiveMorningReviewAlarm()
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
        assertTrue(notifications.activeNotifications.none {
            it.notification.extras.getString(android.app.Notification.EXTRA_TITLE) == "Morning review"
        })

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
    fun cachedOverdueTasksNeverDeferReview() {
        clearMorningReviewState()
        fakeBackend.reset("overdue")
        runBlocking { EnforcementDependencies.coordinator(context).forceSyncAndReconcile() }
        grantNotifications()
        // Pin the Morning Review clock before the 08:15 priority-cap end so only the overdue
        // rule (not the cap) can decide the outcome. The fixture tasks are overdue relative to
        // the real "now", so the pinned instant must be later than that: tomorrow 08:05 local
        // is before the cap on its own date and after every fixture interval at any wall-clock
        // time (today 08:05 would precede the fixtures on most days and could not fail on the
        // old overdue-priority code).
        val zone = ZoneId.systemDefault()
        val pinned = LocalDate.now(zone).plusDays(1).atTime(8, 5).atZone(zone).toInstant()
        MorningReviewRuntimeClock.setForTests { pinned to zone }
        MorningReviewDependencies.scheduler(context).snooze(pinned, zone)
        val fired = requireNotNull(stateStore.read().scheduledAlarm)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val notificationId = AlarmIntentFactory.requestCode(MORNING_REVIEW_TASK_ID, fired.occurrenceKey, AlarmKind.MORNING_REVIEW)

        AlarmReceiver().onReceive(
            context,
            AlarmIntentFactory.receiverIntent(context, MORNING_REVIEW_TASK_ID, fired.occurrenceKey, AlarmKind.MORNING_REVIEW)
        )

        assertEquals(1, waitForNotification(notifications, notificationId).size)
        assertNotEquals(MorningReviewAlarmReason.PriorityRetry, stateStore.read().scheduledAlarm?.reason)
        notifications.cancel(notificationId)
    }

    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
        assertTrue(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
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

    private fun assertExactlyOneActiveMorningReviewAlarm() {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys alarm")
        val dump = FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        // API 34's live alarm schedule is the Pending alarm batches section. Recent problems,
        // cancellation history, and Alarm Stats can all retain old identities and are excluded.
        val pendingHeader = Regex("(?m)^\\s*(?:\\d+ pending alarms:|Pending alarm batches: \\d+)\\s*$")
            .find(dump)
        check(pendingHeader != null) {
            "API34 alarm dump missing pending-alarm header: ${dump.take(4_000)}"
        }
        val afterPending = dump.substring(pendingHeader.range.last + 1)
        val pending = afterPending.substringBefore("Pending user blocked background alarms:")
        val marker = "app.buzer.action.ALARM_RECEIVER.MORNING_REVIEW"
        val count = pending.lineSequence().count { it.contains(marker) }
        assertEquals(
            "Expected exactly one active Morning Review PendingIntent in API34 pending batches; " +
                "marker=$marker pending=${pending.take(4_000)}",
            1,
            count
        )
    }
}
