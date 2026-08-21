package app.buzer.android.observability

import app.buzer.shared.diagnostics.DiagnosticCorrelation
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticContextStoreTest {

    private lateinit var store: DiagnosticContextStore

    @Before
    fun setup() {
        store = DiagnosticContextStore()
    }

    @After
    fun tearDown() {
        store.reset()
        DiagnosticContextStore.reset()
    }

    @Test
    fun atomicSectionUpdatesModifyRespectiveSections() {
        store.updateUi {
            it.copy(
                screen = "TaskEditor",
                currentActivity = "MainActivity",
                blockerType = "morning_review",
                appSection = "editor",
                inFlightOperation = "save_draft"
            )
        }
        store.updateSync {
            it.copy(
                scheduleVersion = "v2",
                snapshotGeneration = 42L,
                lastSyncAt = "2026-08-21T12:00:00Z",
                lastSyncResult = "success",
                cachedTaskCount = 15,
                lastSyncError = null
            )
        }
        store.updateScheduling {
            it.copy(
                alarmsRegistered = 3,
                nextAlarmAt = "2026-08-21T14:00:00Z",
                morningReviewScheduledAt = "2026-08-22T08:00:00Z",
                lastFiredAlarm = "alarm-1"
            )
        }
        store.updateReadiness {
            it.copy(
                notificationsGranted = true,
                exactAlarmGranted = true,
                fullScreenIntentGranted = true,
                overlayGranted = false,
                batteryOptimizationExempt = true
            )
        }
        store.updateActions {
            it.copy(
                pendingActionsCount = 1,
                lastActionId = "act-123",
                lastActionOutcome = "accepted",
                lastActionError = null
            )
        }
        store.updateDevice {
            it.copy(
                batteryLevel = 0.85f,
                isCharging = true,
                isInteractive = true,
                isPowerSaveMode = false,
                networkType = "WIFI",
                availableMemoryBytes = 1024L * 1024L * 512L,
                lowMemory = false,
                freeStorageBytes = 1024L * 1024L * 1024L * 10L,
                previousExitReason = "USER_REQUESTED"
            )
        }
        store.updateCorrelation {
            it.copy(
                deviceId = "dev-01",
                taskId = "task-100",
                occurrenceKey = "occ-2026-08-21",
                actionId = "act-123",
                requestId = "req-999",
                traceId = "trace-abc",
                reportSessionId = "rep-session-xyz"
            )
        }

        val snapshot = store.snapshot()

        assertEquals("TaskEditor", snapshot.ui.screen)
        assertEquals("MainActivity", snapshot.ui.currentActivity)
        assertEquals("morning_review", snapshot.ui.blockerType)
        assertEquals("editor", snapshot.ui.appSection)
        assertEquals("save_draft", snapshot.ui.inFlightOperation)

        assertEquals("v2", snapshot.sync.scheduleVersion)
        assertEquals(42L, snapshot.sync.snapshotGeneration)
        assertEquals("2026-08-21T12:00:00Z", snapshot.sync.lastSyncAt)
        assertEquals("success", snapshot.sync.lastSyncResult)
        assertEquals(15, snapshot.sync.cachedTaskCount)
        assertNull(snapshot.sync.lastSyncError)

        assertEquals(3, snapshot.scheduling.alarmsRegistered)
        assertEquals("2026-08-21T14:00:00Z", snapshot.scheduling.nextAlarmAt)
        assertEquals("2026-08-22T08:00:00Z", snapshot.scheduling.morningReviewScheduledAt)
        assertEquals("alarm-1", snapshot.scheduling.lastFiredAlarm)

        assertTrue(snapshot.readiness.notificationsGranted == true)
        assertTrue(snapshot.readiness.exactAlarmGranted == true)
        assertTrue(snapshot.readiness.fullScreenIntentGranted == true)
        assertFalse(snapshot.readiness.overlayGranted == true)
        assertTrue(snapshot.readiness.batteryOptimizationExempt == true)

        assertEquals(1, snapshot.actions.pendingActionsCount)
        assertEquals("act-123", snapshot.actions.lastActionId)
        assertEquals("accepted", snapshot.actions.lastActionOutcome)
        assertNull(snapshot.actions.lastActionError)

        assertEquals(0.85f, snapshot.device.batteryLevel)
        assertTrue(snapshot.device.isCharging == true)
        assertTrue(snapshot.device.isInteractive == true)
        assertFalse(snapshot.device.isPowerSaveMode == true)
        assertEquals("WIFI", snapshot.device.networkType)
        assertEquals(536870912L, snapshot.device.availableMemoryBytes)
        assertFalse(snapshot.device.lowMemory == true)
        assertEquals("USER_REQUESTED", snapshot.device.previousExitReason)

        assertEquals("dev-01", snapshot.correlation.deviceId)
        assertEquals("task-100", snapshot.correlation.taskId)
        assertEquals("occ-2026-08-21", snapshot.correlation.occurrenceKey)
        assertEquals("act-123", snapshot.correlation.actionId)
        assertEquals("req-999", snapshot.correlation.requestId)
        assertEquals("trace-abc", snapshot.correlation.traceId)
        assertEquals("rep-session-xyz", snapshot.correlation.reportSessionId)
    }

    @Test
    fun snapshotIsImmutableAndFrozen() {
        store.updateUi { it.copy(screen = "InitialScreen") }
        val snapshot1 = store.snapshot()
        assertEquals("InitialScreen", snapshot1.ui.screen)

        store.updateUi { it.copy(screen = "ModifiedScreen") }
        val snapshot2 = store.snapshot()

        assertEquals("InitialScreen", snapshot1.ui.screen)
        assertEquals("ModifiedScreen", snapshot2.ui.screen)
    }

    @Test
    fun setCorrelationUpdatesCorrelationAndUiFields() {
        val correlation = DiagnosticCorrelation(
            deviceId = "dev-cor-1",
            taskId = "task-cor-2",
            occurrenceKey = "occ-cor-3",
            actionId = "act-cor-4",
            requestId = "req-cor-5",
            traceId = "trace-cor-6",
            screen = "HomeScreen",
            blockerType = "deadline",
            appSection = "main_list",
            reportSessionId = "rep-session-cor-7"
        )

        store.setCorrelation(correlation)
        val snapshot = store.snapshot()

        assertEquals("dev-cor-1", snapshot.correlation.deviceId)
        assertEquals("task-cor-2", snapshot.correlation.taskId)
        assertEquals("occ-cor-3", snapshot.correlation.occurrenceKey)
        assertEquals("act-cor-4", snapshot.correlation.actionId)
        assertEquals("req-cor-5", snapshot.correlation.requestId)
        assertEquals("trace-cor-6", snapshot.correlation.traceId)
        assertEquals("rep-session-cor-7", snapshot.correlation.reportSessionId)

        assertEquals("HomeScreen", snapshot.ui.screen)
        assertEquals("deadline", snapshot.ui.blockerType)
        assertEquals("main_list", snapshot.ui.appSection)
    }

    @Test
    fun resetRestoresAllSectionsToDefault() {
        store.updateUi { it.copy(screen = "SomeScreen") }
        store.updateSync { it.copy(scheduleVersion = "v1") }
        store.updateScheduling { it.copy(alarmsRegistered = 5) }
        store.updateReadiness { it.copy(notificationsGranted = true) }
        store.updateActions { it.copy(pendingActionsCount = 3) }
        store.updateDevice { it.copy(batteryLevel = 0.5f) }
        store.updateCorrelation { it.copy(deviceId = "dev-1") }

        store.reset()
        val snapshot = store.snapshot()

        assertNull(snapshot.ui.screen)
        assertNull(snapshot.sync.scheduleVersion)
        assertNull(snapshot.scheduling.alarmsRegistered)
        assertNull(snapshot.readiness.notificationsGranted)
        assertNull(snapshot.actions.pendingActionsCount)
        assertNull(snapshot.device.batteryLevel)
        assertNull(snapshot.correlation.deviceId)
    }

    @Test
    fun exceptionInTransformNeverThrowsToCaller() {
        store.updateUi { it.copy(screen = "SafeScreen") }

        // Throw inside transform
        store.updateUi { throw RuntimeException("Explosion inside transform") }
        store.updateSync { throw IllegalStateException("Sync failure") }
        store.updateScheduling { throw NullPointerException("Scheduling failure") }
        store.updateReadiness { throw IllegalArgumentException("Readiness failure") }
        store.updateActions { throw Error("Action fatal error") }
        store.updateDevice { throw RuntimeException("Device failure") }
        store.updateCorrelation { throw RuntimeException("Correlation failure") }

        val snapshot = store.snapshot()
        assertEquals("SafeScreen", snapshot.ui.screen)
    }

    @Test
    fun concurrentSectionUpdatesAreThreadSafe() {
        val threadCount = 8
        val iterationsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        store.updateUi { it.copy(inFlightOperation = "op-$t-$i") }
                        store.updateSync { it.copy(cachedTaskCount = i) }
                        store.updateActions { it.copy(pendingActionsCount = i) }
                        store.snapshot()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue(completed)

        val finalSnapshot = store.snapshot()
        assertNotNull(finalSnapshot.ui.inFlightOperation)
        assertNotNull(finalSnapshot.sync.cachedTaskCount)
        assertNotNull(finalSnapshot.actions.pendingActionsCount)
    }
}
