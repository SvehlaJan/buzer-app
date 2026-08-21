package app.buzer.android.observability

import app.buzer.android.enforcement.AlarmIntentFactory
import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.AlarmReceiver
import app.buzer.android.enforcement.AlarmScheduler
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporting
import app.buzer.android.enforcement.EnforcementApi
import app.buzer.android.enforcement.EnforcementCoordinator
import app.buzer.android.enforcement.InMemoryScheduledAlarmRegistryStorage
import app.buzer.android.enforcement.ScheduledAlarm
import app.buzer.android.enforcement.ScheduledAlarmIdentity
import app.buzer.android.enforcement.ScheduledAlarmRegistry
import app.buzer.android.sync.SnapshotStorage
import app.buzer.shared.model.MutationOutcome
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AndroidContextWiringTest {
    private val contextStore = DiagnosticContextStore.instance
    private val recordingObservability = RecordingAndroidObservability()

    @BeforeTest
    fun setUp() {
        contextStore.reset()
        recordingObservability.reset()
    }

    @Test
    fun noDirectSentryImportsOutsideObservabilityPackage() {
        val rootCandidates = listOf(
            File("src/main/kotlin/app/buzer/android"),
            File("client/androidApp/src/main/kotlin/app/buzer/android"),
            File("../androidApp/src/main/kotlin/app/buzer/android"),
            File("../client/androidApp/src/main/kotlin/app/buzer/android")
        )
        val sourceDir = rootCandidates.firstOrNull { it.exists() && it.isDirectory }
        assertNotNull(sourceDir, "Could not locate android main source directory among candidates")

        val violations = mutableListOf<String>()
        sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.path.contains(File.separator + "observability" + File.separator) }
            .forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (line.trim().startsWith("import io.sentry")) {
                            violations.add("${file.path}:${index + 1}: $line")
                        }
                    }
                }
            }

        assertTrue(
            violations.isEmpty(),
            "Found forbidden direct Sentry imports outside observability package:\n${violations.joinToString("\n")}"
        )
    }

    @Test
    fun coordinatorSyncAndReconcileUpdatesContextStore() {
        runBlocking {
            val fakeApi = object : EnforcementApi {
                override suspend fun getSnapshot(): TasksSnapshotResponse = sampleSnapshot(
                    scheduleVersion = 42L,
                    snapshotGeneration = 100L,
                    taskCount = 3
                )
                override suspend fun forceSync(): TasksSnapshotResponse = getSnapshot()
                override suspend fun startTask(taskId: String, request: TaskActionRequest): MutationResponse = error("not used")
                override suspend fun postponeStart(taskId: String, request: TaskActionRequest): MutationResponse = error("not used")
                override suspend fun completeTask(taskId: String, request: TaskActionRequest): MutationResponse = error("not used")
                override suspend fun extendTask(id: String, request: app.buzer.shared.model.ExtendTaskRequest): MutationResponse = error("not used")
                override suspend fun updateTask(id: String, request: app.buzer.shared.model.UpdateTaskRequest): MutationResponse = error("not used")
                override suspend fun checkChecklistItem(id: String, childId: String, request: app.buzer.shared.model.FocusedChecklistMutationRequest): MutationResponse = error("not used")
                override suspend fun uncheckChecklistItem(id: String, childId: String, request: app.buzer.shared.model.FocusedChecklistMutationRequest): MutationResponse = error("not used")
            }

            val inMemoryStorage = object : SnapshotStorage {
                private var saved: TasksSnapshotResponse? = null
                override fun load(): TasksSnapshotResponse? = saved
                override fun save(snapshot: TasksSnapshotResponse) { saved = snapshot }
            }

            val scheduledAlarms = mutableListOf<ScheduledAlarm>()
            val alarmScheduler = object : AlarmScheduler {
                override fun schedule(alarm: ScheduledAlarm) { scheduledAlarms.add(alarm) }
                override fun cancel(identity: ScheduledAlarmIdentity) { scheduledAlarms.removeIf { it.identity == identity } }
            }

            val coordinator = EnforcementCoordinator(
                api = fakeApi,
                snapshotStore = inMemoryStorage,
                alarmScheduler = alarmScheduler,
                scheduledAlarmRegistry = ScheduledAlarmRegistry(InMemoryScheduledAlarmRegistryStorage()),
                errorReporter = object : BlockerErrorReporting {
                    override fun report(action: BlockerAction, taskId: String?, message: String, showNotification: Boolean, throwable: Throwable?) {}
                },
                nowProvider = { "2026-08-21T10:00:00Z" },
                observability = recordingObservability,
                contextStore = contextStore
            )

            coordinator.refreshAndReconcile()

            val snapshot = contextStore.snapshot()
            assertEquals("42", snapshot.sync.scheduleVersion)
            assertEquals(100L, snapshot.sync.snapshotGeneration)
            assertEquals("success", snapshot.sync.lastSyncResult)
            assertEquals(3, snapshot.sync.cachedTaskCount)
            assertEquals("2026-08-21T10:00:00Z", snapshot.sync.lastSyncAt)
            assertNotNull(snapshot.scheduling.alarmsRegistered)
        }
    }

    @Test
    fun coordinatorActionUpdatesActionContextAndCorrelation() {
        runBlocking {
            val sampleSnap = sampleSnapshot(scheduleVersion = 1L, snapshotGeneration = 200L, taskCount = 1)
            val fakeApi = object : EnforcementApi {
                override suspend fun getSnapshot(): TasksSnapshotResponse = sampleSnap
                override suspend fun forceSync(): TasksSnapshotResponse = sampleSnap
                override suspend fun startTask(taskId: String, request: TaskActionRequest): MutationResponse {
                    return MutationResponse(
                        actionId = request.actionId,
                        outcome = MutationOutcome.completed,
                        snapshot = sampleSnap
                    )
                }
                override suspend fun postponeStart(taskId: String, request: TaskActionRequest): MutationResponse = error("not used")
                override suspend fun completeTask(taskId: String, request: TaskActionRequest): MutationResponse = error("not used")
                override suspend fun extendTask(id: String, request: app.buzer.shared.model.ExtendTaskRequest): MutationResponse = error("not used")
                override suspend fun updateTask(id: String, request: app.buzer.shared.model.UpdateTaskRequest): MutationResponse = error("not used")
                override suspend fun checkChecklistItem(id: String, childId: String, request: app.buzer.shared.model.FocusedChecklistMutationRequest): MutationResponse = error("not used")
                override suspend fun uncheckChecklistItem(id: String, childId: String, request: app.buzer.shared.model.FocusedChecklistMutationRequest): MutationResponse = error("not used")
            }

            val inMemoryStorage = object : SnapshotStorage {
                private var saved: TasksSnapshotResponse? = sampleSnap
                override fun load(): TasksSnapshotResponse? = saved
                override fun save(snapshot: TasksSnapshotResponse) { saved = snapshot }
            }

            val coordinator = EnforcementCoordinator(
                api = fakeApi,
                snapshotStore = inMemoryStorage,
                alarmScheduler = object : AlarmScheduler {
                    override fun schedule(alarm: ScheduledAlarm) {}
                    override fun cancel(identity: ScheduledAlarmIdentity) {}
                },
                scheduledAlarmRegistry = ScheduledAlarmRegistry(InMemoryScheduledAlarmRegistryStorage()),
                errorReporter = object : BlockerErrorReporting {
                    override fun report(action: BlockerAction, taskId: String?, message: String, showNotification: Boolean, throwable: Throwable?) {}
                },
                nowProvider = { "2026-08-21T10:00:00Z" },
                observability = recordingObservability,
                contextStore = contextStore
            )

            val request = TaskActionRequest(
                actionId = "action-uuid-999",
                deviceId = "device-1",
                expectedOccurrenceKey = "task-1#2026-08-21"
            )
            val result = coordinator.startTask("task-1", request)
            assertEquals(app.buzer.android.enforcement.CoordinatorActionResult.Success, result)

            val snapshot = contextStore.snapshot()
            assertEquals("action-uuid-999", snapshot.actions.lastActionId)
            assertEquals("completed", snapshot.actions.lastActionOutcome)
            assertEquals("task-1", snapshot.correlation.taskId)
            assertEquals("task-1#2026-08-21", snapshot.correlation.occurrenceKey)
            assertEquals("action-uuid-999", snapshot.correlation.actionId)

            // Sentry context was also updated
            assertTrue(recordingObservability.contexts.isNotEmpty())
            val lastCorrelation = recordingObservability.contexts.last()
            assertEquals("task-1", lastCorrelation.taskId)
            assertEquals("action-uuid-999", lastCorrelation.actionId)
        }
    }

    @Test
    fun uiContextUpdatesTransitions() {
        contextStore.updateUi {
            it.copy(screen = "Home", currentActivity = "MainActivity", appSection = "Home")
        }
        assertEquals("Home", contextStore.snapshot().ui.screen)
        assertEquals("MainActivity", contextStore.snapshot().ui.currentActivity)

        contextStore.updateUi {
            it.copy(screen = "StartBlocker", blockerType = "StartBlocker", appSection = "Enforcement")
        }
        assertEquals("StartBlocker", contextStore.snapshot().ui.screen)
        assertEquals("StartBlocker", contextStore.snapshot().ui.blockerType)
        assertEquals("Enforcement", contextStore.snapshot().ui.appSection)
    }

    @Test
    fun coordinatorActionFailureRoutesNonFatalAndUpdatesActionContext() {
        runBlocking {
            val sampleSnap = sampleSnapshot(scheduleVersion = 1L, snapshotGeneration = 200L, taskCount = 1)
            val fakeApi = object : EnforcementApi {
                override suspend fun getSnapshot(): TasksSnapshotResponse = sampleSnap
                override suspend fun forceSync(): TasksSnapshotResponse = sampleSnap
                override suspend fun startTask(id: String, request: TaskActionRequest): MutationResponse {
                    throw IllegalStateException("Database write failed on backend")
                }
                override suspend fun postponeStart(id: String, request: TaskActionRequest): MutationResponse = error("not used")
                override suspend fun completeTask(id: String, request: TaskActionRequest): MutationResponse = error("not used")
                override suspend fun extendTask(id: String, request: app.buzer.shared.model.ExtendTaskRequest): MutationResponse = error("not used")
                override suspend fun updateTask(id: String, request: app.buzer.shared.model.UpdateTaskRequest): MutationResponse = error("not used")
                override suspend fun checkChecklistItem(id: String, childId: String, request: app.buzer.shared.model.FocusedChecklistMutationRequest): MutationResponse = error("not used")
                override suspend fun uncheckChecklistItem(id: String, childId: String, request: app.buzer.shared.model.FocusedChecklistMutationRequest): MutationResponse = error("not used")
            }

            val inMemoryStorage = object : SnapshotStorage {
                private var saved: TasksSnapshotResponse? = sampleSnap
                override fun load(): TasksSnapshotResponse? = saved
                override fun save(snapshot: TasksSnapshotResponse) { saved = snapshot }
            }

            var reportedAction: BlockerAction? = null
            var reportedThrowable: Throwable? = null
            val errorReporter = object : BlockerErrorReporting {
                override fun report(
                    action: BlockerAction,
                    taskId: String?,
                    message: String,
                    showNotification: Boolean,
                    throwable: Throwable?
                ) {
                    reportedAction = action
                    reportedThrowable = throwable
                }
            }

            val coordinator = EnforcementCoordinator(
                api = fakeApi,
                snapshotStore = inMemoryStorage,
                alarmScheduler = object : AlarmScheduler {
                    override fun schedule(alarm: ScheduledAlarm) {}
                    override fun cancel(identity: ScheduledAlarmIdentity) {}
                },
                scheduledAlarmRegistry = ScheduledAlarmRegistry(InMemoryScheduledAlarmRegistryStorage()),
                errorReporter = errorReporter,
                nowProvider = { "2026-08-21T10:00:00Z" },
                observability = recordingObservability,
                contextStore = contextStore
            )

            val request = TaskActionRequest(
                actionId = "action-uuid-fail",
                deviceId = "device-1",
                expectedOccurrenceKey = "task-1#2026-08-21"
            )
            val result = coordinator.startTask("task-1", request)
            assertTrue(result is app.buzer.android.enforcement.CoordinatorActionResult.FailedAndHidden)

            val snapshot = contextStore.snapshot()
            assertEquals("action-uuid-fail", snapshot.actions.lastActionId)
            assertEquals("error", snapshot.actions.lastActionOutcome)
            assertEquals("Database write failed on backend", snapshot.actions.lastActionError)

            assertEquals(BlockerAction.START_TASK, reportedAction)
            assertNotNull(reportedThrowable)
            assertEquals("Database write failed on backend", reportedThrowable?.message)
        }
    }

    @Test
    fun schedulingContextTracksAlarmActivity() {
        contextStore.updateScheduling {
            it.copy(
                alarmsRegistered = 5,
                nextAlarmAt = "2026-08-21T18:00:00Z",
                morningReviewScheduledAt = "2026-08-22T08:00:00Z",
                lastFiredAlarm = "START_BLOCKER taskId=task-1 occurrenceKey=task-1#2026-08-21"
            )
        }
        val scheduling = contextStore.snapshot().scheduling
        assertEquals(5, scheduling.alarmsRegistered)
        assertEquals("2026-08-21T18:00:00Z", scheduling.nextAlarmAt)
        assertEquals("2026-08-22T08:00:00Z", scheduling.morningReviewScheduledAt)
        assertEquals("START_BLOCKER taskId=task-1 occurrenceKey=task-1#2026-08-21", scheduling.lastFiredAlarm)
    }

    @Test
    fun readinessContextUpdatesTransitions() {
        contextStore.updateReadiness {
            it.copy(
                notificationsGranted = true,
                exactAlarmGranted = true,
                fullScreenIntentGranted = true,
                overlayGranted = false,
                batteryOptimizationExempt = true
            )
        }
        val readiness = contextStore.snapshot().readiness
        assertEquals(true, readiness.notificationsGranted)
        assertEquals(true, readiness.exactAlarmGranted)
        assertEquals(true, readiness.fullScreenIntentGranted)
        assertEquals(false, readiness.overlayGranted)
        assertEquals(true, readiness.batteryOptimizationExempt)
    }

    private fun sampleSnapshot(
        scheduleVersion: Long,
        snapshotGeneration: Long,
        taskCount: Int
    ): TasksSnapshotResponse {
        val tasks = (1..taskCount).map { i ->
            TaskSnapshotItem(
                id = "task-$i",
                todoistTaskId = "todoist-$i",
                title = "Task $i",
                projectId = null,
                assigneeId = "user-1",
                creatorId = "user-1",
                dueDatetime = "2026-08-21T18:00:00Z",
                durationMinutes = 30,
                startAt = "2026-08-21T17:30:00Z",
                state = app.buzer.shared.model.CommitmentState.planned,
                isAssignedToUser = true,
                isCreatedByUser = true,
                isCompleted = false,
                isDeleted = false,
                validationErrors = emptyList(),
                startedAt = null,
                startDismissCount = 0,
                deadlineExtensionCount = 0,
                escalationSentAt = null,
                updatedAt = "2026-08-21T10:00:00Z",
                occurrenceKey = "task-$i#2026-08-21",
                reconciliationStatus = app.buzer.shared.model.ReconciliationStatus.none,
                recurrence = app.buzer.shared.model.TaskRecurrence(),
                checklistItems = emptyList(),
                checkedCount = 0,
                totalCount = 0
            )
        }

        return TasksSnapshotResponse(
            scheduleVersion = scheduleVersion,
            serverTime = "2026-08-21T10:00:00Z",
            tasks = tasks,
            health = emptyList(),
            snapshotGeneration = snapshotGeneration
        )
    }
}
