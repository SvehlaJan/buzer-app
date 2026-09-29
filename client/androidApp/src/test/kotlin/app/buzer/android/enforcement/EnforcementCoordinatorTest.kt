package app.buzer.android.enforcement

import app.buzer.android.sync.SnapshotStorage
import app.buzer.shared.api.BuzerApiException
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.ExtendTaskRequest
import app.buzer.shared.model.FocusedChecklistMutationRequest
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.TaskRecurrence
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.state.SnapshotReducer
import app.buzer.shared.state.SnapshotWithCallerDraft
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnforcementCoordinatorTest {
    @Test
    fun successfulActionAppliesReturnedSnapshotWithoutFollowUpFetch() = runBlocking {
        val returnedSnapshot = snapshot(task("task-1", state = CommitmentState.started))
        val store = FakeSnapshotStore()
        val api = FakeBuzerApi(
            snapshot = snapshot(task("stale-task")),
            startHandler = {
                MutationResponse(snapshot = returnedSnapshot)
            }
        )
        val coordinator = EnforcementCoordinator(
            api = api,
            snapshotStore = store,
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T11:01:00Z" }
        )

        val result = coordinator.startTask(
            "task-1",
            TaskActionRequest(expectedOccurrenceKey = "occurrence-task-1")
        )

        assertEquals(CoordinatorActionResult.Success, result)
        assertEquals(returnedSnapshot, store.saved)
        assertEquals(0, api.snapshotCalls)
    }

    @Test
    fun conflictAppliesCurrentSnapshotWithoutChangingCallerOwnedDraft() = runBlocking {
        val conflictSnapshot = snapshot(task("task-1", state = CommitmentState.started))
        val callerOwnedState = SnapshotWithCallerDraft(
            viewState = SnapshotReducer.reduce(snapshot(task("task-1"))),
            callerDraft = "keep this edit"
        )
        val store = FakeSnapshotStore()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(
                snapshot(task("task-1")),
                startHandler = {
                    throw BuzerApiException(
                        status = 409,
                        code = "stale_task",
                        message = "Task changed remotely",
                        retryAfterSeconds = null,
                        snapshot = conflictSnapshot
                    )
                }
            ),
            snapshotStore = store,
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.startTask("task-1", action("occurrence-task-1"))

        val conflict = result as CoordinatorActionResult.Conflict
        assertEquals("stale_task", conflict.code)
        assertEquals("Task changed remotely", conflict.message)
        assertEquals(conflictSnapshot, conflict.snapshot)
        assertEquals(conflictSnapshot, store.saved)
        assertEquals(
            "keep this edit",
            SnapshotReducer.replaceAuthoritative(callerOwnedState, conflict.snapshot).callerDraft
        )
    }

    @Test
    fun operationalWarningAppliesReturnedSnapshotAndReturnsWarning() = runBlocking {
        val returnedSnapshot = snapshot(task("task-1", state = CommitmentState.started))
        val store = FakeSnapshotStore()
        val reporter = RecordingErrorReporter()
        val api = FakeBuzerApi(
            snapshot(task("task-1")),
            startHandler = {
                throw BuzerApiException(
                    status = 429,
                    code = "backend_busy",
                    message = "Server busy",
                    retryAfterSeconds = 5,
                    snapshot = returnedSnapshot
                )
            }
        )
        val coordinator = EnforcementCoordinator(
            api = api,
            snapshotStore = store,
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.startTask("task-1", action("occurrence-task-1"))

        val warning = result as CoordinatorActionResult.Warning
        assertEquals("backend_busy", warning.code)
        assertEquals("Server busy", warning.message)
        assertEquals(returnedSnapshot, warning.snapshot)
        assertEquals(returnedSnapshot, store.saved)
        assertTrue(reporter.errors.isEmpty())
    }

    @Test
    fun operationalWarningIgnoresAlreadyCachedNewerSnapshotAndReturnsWinningSnapshotInstead() = runBlocking {
        val newerCached = snapshot(task("task-1", state = CommitmentState.completed, isCompleted = true), snapshotGeneration = 9)
        val staleReturned = snapshot(task("task-1", state = CommitmentState.started), snapshotGeneration = 8)
        val scheduler = RecordingAlarmScheduler()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(
                snapshot(task("task-1")),
                startHandler = {
                    throw BuzerApiException(
                        status = 429,
                        code = "backend_busy",
                        message = "Server busy",
                        retryAfterSeconds = 5,
                        snapshot = staleReturned
                    )
                }
            ),
            snapshotStore = FakeSnapshotStore(newerCached),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.startTask("task-1", action("occurrence-task-1"))

        // The caller must never see (or be able to reapply) the stale response snapshot: the
        // Warning still carries the operation's own code/message, but its snapshot is the
        // already-cached newer authority, and the cache/alarms are left untouched.
        val warning = result as CoordinatorActionResult.Warning
        assertEquals("backend_busy", warning.code)
        assertEquals("Server busy", warning.message)
        assertEquals(newerCached, warning.snapshot)
        assertEquals(newerCached, coordinator.loadLatestSnapshot())
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun operationalWarningSurfacesLocalReconciliationFailureInsteadOfDiscardingIt() = runBlocking {
        val returnedSnapshot = snapshot(task("task-1", state = CommitmentState.started))
        val reporter = RecordingErrorReporter()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(
                snapshot(task("task-1")),
                startHandler = {
                    throw BuzerApiException(
                        status = 429,
                        code = "backend_busy",
                        message = "Server busy",
                        retryAfterSeconds = 5,
                        snapshot = returnedSnapshot
                    )
                }
            ),
            snapshotStore = FakeSnapshotStore(throwOnSave = true),
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.startTask("task-1", action("occurrence-task-1"))

        // A local reconciliation defect while applying an operational warning's own snapshot
        // must be surfaced to the caller (and owned via the error reporter), not silently
        // replaced by a generic Warning that hides the failed reconciliation.
        assertTrue(result is CoordinatorActionResult.LocalReconciliationFailed)
        assertEquals(returnedSnapshot, (result as CoordinatorActionResult.LocalReconciliationFailed).snapshot)
        assertEquals(1, reporter.errors.size)
        assertEquals(BlockerAction.REFRESH_SNAPSHOT, reporter.errors.first().action)
    }

    @Test
    fun transportFailureReturnsTransportFailureWithoutReportingSentryError() = runBlocking {
        val reporter = RecordingErrorReporter()
        val api = FakeBuzerApi(
            snapshot(task("task-1")),
            startHandler = {
                throw IOException("Connection timed out")
            }
        )
        val coordinator = EnforcementCoordinator(
            api = api,
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = reporter,
            isConnectedProvider = { true },
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.startTask("task-1", action("occurrence-task-1"))

        val transport = result as CoordinatorActionResult.TransportFailure
        assertTrue(transport.isConnected)
        assertEquals("Connection timed out", transport.message)
        assertTrue(reporter.errors.isEmpty())
    }

    @Test
    fun focusedChildRejectsOnlyConcurrentTapThenAcceptsFreshIntentAfterResponse() = runBlocking {
        val releaseFirstRequest = CompletableDeferred<Unit>()
        val startedFirstRequest = CompletableDeferred<Unit>()
        val api = FakeBuzerApi(
            snapshot(task("task-1")),
            checkHandler = {
                startedFirstRequest.complete(Unit)
                releaseFirstRequest.await()
                MutationResponse(snapshot = snapshot(task("task-1")))
            }
        )
        val coordinator = EnforcementCoordinator(
            api = api,
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )
        val firstRequest = focusedAction("child-r1")
        val first = async { coordinator.checkChild("task-1", "child-1", firstRequest) }
        startedFirstRequest.await()

        assertEquals(
            CoordinatorActionResult.ChildActionInFlight,
            coordinator.uncheckChild("task-1", "child-1", focusedAction("child-r1"))
        )

        releaseFirstRequest.complete(Unit)
        assertEquals(CoordinatorActionResult.Success, first.await())
        assertEquals(
            CoordinatorActionResult.Success,
            coordinator.uncheckChild("task-1", "child-1", focusedAction("child-r2"))
        )
    }

    @Test
    fun localStoreFailureAfterConfirmedMutationReportsErrorAndReturnsLocalReconciliationFailed() = runBlocking {
        val responseSnapshot = snapshot(task("task-1", state = CommitmentState.started))
        val reporter = RecordingErrorReporter()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(
                snapshot(task("task-1")),
                startHandler = { MutationResponse(snapshot = responseSnapshot) }
            ),
            snapshotStore = FakeSnapshotStore(throwOnSave = true),
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.startTask("task-1", action("occurrence-task-1"))

        assertTrue(result is CoordinatorActionResult.LocalReconciliationFailed)
        assertEquals(responseSnapshot, (result as CoordinatorActionResult.LocalReconciliationFailed).snapshot)
        assertEquals(1, reporter.errors.size)
        assertEquals(BlockerAction.REFRESH_SNAPSHOT, reporter.errors.first().action)
    }

    @Test
    fun delayedLowerGenerationCannotRestoreDeletedRootOrReconcileAlarms() = runBlocking {
        val newerCached = snapshot(
            task("task-1", state = CommitmentState.completed, isCompleted = true),
            snapshotGeneration = 9
        )
        val staleReturned = snapshot(
            task("task-1"),
            task("deleted-root", checklistItems = listOf(child("old-child", checked = false, revision = "opaque-old"))),
            snapshotGeneration = 8
        )
        val scheduler = RecordingAlarmScheduler()
        val notifications = RecordingBlockerNotificationCleaner()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(
                snapshot(task("task-1")),
                startHandler = { MutationResponse(snapshot = staleReturned) }
            ),
            snapshotStore = FakeSnapshotStore(newerCached),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            blockerNotificationCleaner = notifications,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.startTask("task-1", action("occurrence-task-1"))

        assertTrue(result is CoordinatorActionResult.Conflict)
        assertEquals("stale_snapshot", (result as CoordinatorActionResult.Conflict).code)
        assertEquals(newerCached, result.snapshot)
        assertEquals(newerCached, coordinator.loadLatestSnapshot())
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
        assertTrue(notifications.cleaned.isEmpty())
    }

    @Test
    fun equalGenerationReplayUsesCachedAuthorityWithoutSavingOrReconcilingAgain() = runBlocking {
        val cached = snapshot(task("task-1", state = CommitmentState.started), snapshotGeneration = 12)
        val equalButDifferent = snapshot(task("task-1", state = CommitmentState.planned), snapshotGeneration = 12)
        val scheduler = RecordingAlarmScheduler()
        val notifications = RecordingBlockerNotificationCleaner()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(
                snapshot(task("task-1")),
                startHandler = { MutationResponse(snapshot = equalButDifferent) }
            ),
            snapshotStore = FakeSnapshotStore(cached),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            blockerNotificationCleaner = notifications,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        assertEquals(CoordinatorActionResult.Success, coordinator.startTask("task-1", action("occurrence-task-1")))

        assertEquals(cached, coordinator.loadLatestSnapshot())
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
        assertEquals(listOf(NotificationCleaned("task-1", "occurrence-task-1")), notifications.cleaned)
    }

    @Test
    fun confirmedOneTimeDoneCancelsOnlyReturnedOccurrenceAlarmsWithoutFetch() = runBlocking {
        val old = task("task-1")
        val completed = task("task-1", state = CommitmentState.completed, isCompleted = true)
        val scheduler = RecordingAlarmScheduler()
        val api = FakeBuzerApi(
            snapshot(old),
            completeHandler = {
                MutationResponse(snapshot = snapshot(completed, snapshotGeneration = 2))
            }
        )
        val coordinator = EnforcementCoordinator(
            api = api,
            snapshotStore = FakeSnapshotStore(snapshot(old)),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )
        coordinator.reconcileLatestSnapshot()
        scheduler.scheduled.clear()

        assertEquals(CoordinatorActionResult.Success, coordinator.completeTask("task-1", action("occurrence-task-1")))

        assertTrue(scheduler.cancelled.all { it.taskId == "task-1" && it.occurrenceKey == old.occurrenceKey })
        assertTrue(scheduler.cancelled.isNotEmpty())
        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(0, api.snapshotCalls)
    }

    @Test
    fun successfulRefreshSavesSnapshotAndSchedulesPlannedAlarms() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val store = FakeSnapshotStore()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1"))),
            snapshotStore = store,
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val refreshed = coordinator.refreshAndReconcile()

        assertEquals(refreshed, store.saved)
        assertEquals(1, store.saved?.scheduleVersion)
        assertEquals(
            listOf(
                scheduled("task-1", AlarmKind.PRE_START_WARNING, "2026-05-28T11:00:00Z"),
                scheduled("task-1", AlarmKind.START_BLOCKER, "2026-05-28T12:00:00Z"),
                scheduled("task-1", AlarmKind.DEADLINE_BLOCKER, "2026-05-28T13:00:00Z")
            ),
            scheduler.scheduled
        )
    }

    @Test
    fun forceSyncAndReconcileCallsGetSnapshotWithForceRefreshTrue() = runBlocking {
        val initialSnapshot = snapshot(task("initial-task"), snapshotGeneration = 1)
        val forcedSnapshot = snapshot(task("forced-task"), snapshotGeneration = 2)
        val store = FakeSnapshotStore(initialSnapshot)
        val api = FakeBuzerApi(
            snapshot = initialSnapshot,
            forceSyncSnapshot = forcedSnapshot
        )
        val coordinator = EnforcementCoordinator(
            api = api,
            snapshotStore = store,
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.forceSyncAndReconcile()

        assertEquals(1, api.forceRefreshCalls)
        assertEquals(0, api.normalCalls)
        assertEquals(forcedSnapshot, result)
        assertEquals(forcedSnapshot, store.saved)
    }

    @Test
    fun actionCancellationPropagatesWithoutReportingOrRetry() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val reporter = RecordingErrorReporter()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1")), cancelPostpone = true),
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = scheduler,
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        assertFailsWith<CancellationException> {
            coordinator.postponeStart("task-1", action("occurrence-task-1"))
        }

        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(reporter.errors.isEmpty())
    }

    @Test
    fun successfulStartCancelsTaskBlockerNotifications() = runBlocking {
        val notifications = RecordingBlockerNotificationCleaner()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1", state = CommitmentState.started))),
            snapshotStore = FakeSnapshotStore(snapshot(task("task-1"))),
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = RecordingErrorReporter(),
            blockerNotificationCleaner = notifications,
            nowProvider = { "2026-05-28T11:01:00Z" }
        )

        val result = coordinator.startTask("task-1", action("occurrence-task-1"))

        assertEquals(CoordinatorActionResult.Success, result)
        assertEquals(
            listOf(NotificationCleaned("task-1", "occurrence-task-1")),
            notifications.cleaned
        )
    }

    @Test
    fun reconcileLatestSnapshotSchedulesFromCacheWithoutNetwork() {
        val scheduler = RecordingAlarmScheduler()
        val api = FakeBuzerApi(snapshot(task("task-1")))
        val coordinator = EnforcementCoordinator(
            api = api,
            snapshotStore = FakeSnapshotStore(snapshot(task("task-1"))),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        assertTrue(coordinator.reconcileLatestSnapshot())
        assertEquals(0, api.snapshotCalls)
        assertTrue(scheduler.cancelled.isEmpty())
        assertEquals(
            listOf(
                scheduled("task-1", AlarmKind.PRE_START_WARNING, "2026-05-28T11:00:00Z"),
                scheduled("task-1", AlarmKind.START_BLOCKER, "2026-05-28T12:00:00Z"),
                scheduled("task-1", AlarmKind.DEADLINE_BLOCKER, "2026-05-28T13:00:00Z")
            ),
            scheduler.scheduled
        )
    }

    @Test
    fun taskWithOffsetLessTimeIsSkippedAndReportedOncePerProcess() {
        val scheduler = RecordingAlarmScheduler()
        val reporter = RecordingErrorReporter()
        val cached = snapshot(task("task-1"), task("floating", dueDatetime = "2026-05-28T12:00:00"))
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(cached),
            snapshotStore = FakeSnapshotStore(cached),
            alarmScheduler = scheduler,
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        assertTrue(coordinator.reconcileLatestSnapshot())
        assertTrue(coordinator.reconcileLatestSnapshot())

        assertTrue(scheduler.scheduled.none { it.taskId == "floating" })
        assertTrue(scheduler.scheduled.any { it.taskId == "task-1" })
        assertEquals(listOf<String?>("floating"), reporter.errors.map { it.taskId })
        assertEquals(BlockerAction.REFRESH_SNAPSHOT, reporter.errors.single().action)
    }

    private fun snapshot(
        vararg tasks: TaskSnapshotItem,
        scheduleVersion: Long = 1,
        snapshotGeneration: Long = 1
    ): TasksSnapshotResponse =
        TasksSnapshotResponse(
            scheduleVersion = scheduleVersion,
            serverTime = "2026-05-28T09:00:00Z",
            tasks = tasks.toList(),
            health = emptyList<HealthStatus>(),
            snapshotGeneration = snapshotGeneration
        )

    private fun task(
        id: String,
        state: CommitmentState = CommitmentState.planned,
        isCompleted: Boolean = false,
        isDeleted: Boolean = false,
        occurrenceKey: String = "occurrence-$id",
        dueDatetime: String = "2026-05-28T12:00:00Z",
        startAt: String = "2026-05-28T12:00:00Z",
        recurrence: TaskRecurrence = TaskRecurrence(),
        checklistItems: List<ChecklistItem> = emptyList()
    ): TaskSnapshotItem =
        TaskSnapshotItem(
            id = id,
            todoistTaskId = "todoist-$id",
            title = "Task $id",
            projectId = null,
            assigneeId = "user-self",
            creatorId = "user-self",
            dueDatetime = dueDatetime,
            durationMinutes = 60,
            startAt = startAt,
            state = state,
            isAssignedToUser = true,
            isCreatedByUser = true,
            isCompleted = isCompleted,
            isDeleted = isDeleted,
            validationErrors = emptyList(),
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-05-28T09:00:00Z",
            occurrenceKey = occurrenceKey,
            recurrence = recurrence,
            checklistItems = checklistItems,
            checkedCount = checklistItems.count(ChecklistItem::checked),
            totalCount = checklistItems.size
        )

    private fun child(
        id: String,
        checked: Boolean = false,
        revision: String = "r:1"
    ): ChecklistItem = ChecklistItem(
        id = id,
        title = "Child $id",
        checked = checked,
        order = id.removePrefix("child-").toIntOrNull() ?: 0,
        childRevision = revision
    )

    private fun scheduled(taskId: String, kind: AlarmKind, triggerAtIso: String): ScheduledAlarm =
        ScheduledAlarm(taskId, "occurrence-$taskId", kind, Instant.parse(triggerAtIso).toEpochMilli())

    private fun action(
        occurrenceKey: String = "occurrence-task-1"
    ): TaskActionRequest = TaskActionRequest(
        expectedOccurrenceKey = occurrenceKey
    )

    private fun focusedAction(
        childRevision: String
    ): FocusedChecklistMutationRequest =
        FocusedChecklistMutationRequest(
            expectedOccurrenceKey = "occurrence-task-1",
            expectedChildRevision = childRevision
        )

    private class FakeBuzerApi(
        private val snapshot: TasksSnapshotResponse? = null,
        private val forceSyncSnapshot: TasksSnapshotResponse? = null,
        private val failForceSync: Boolean = false,
        private val startHandler: ((TaskActionRequest) -> MutationResponse)? = null,
        private val postponeHandler: ((TaskActionRequest) -> MutationResponse)? = null,
        private val checkHandler: (suspend (FocusedChecklistMutationRequest) -> MutationResponse)? = null,
        private val completeHandler: ((TaskActionRequest) -> MutationResponse)? = null,
        private val failPostpone: Boolean = false,
        private val failComplete: Boolean = false,
        private val cancelPostpone: Boolean = false
    ) : EnforcementApi {
        var snapshotCalls = 0
            private set
        var normalCalls = 0
            private set
        var forceRefreshCalls = 0
            private set
        val startRequests = mutableListOf<TaskActionRequest>()
        val postponeRequests = mutableListOf<TaskActionRequest>()

        private val effectiveSnapshot get() = snapshot ?: TasksSnapshotResponse(
            scheduleVersion = 1,
            snapshotGeneration = 1,
            serverTime = "2026-05-28T09:30:00Z",
            health = emptyList(),
            tasks = emptyList()
        )

        override suspend fun getSnapshot(forceRefresh: Boolean): TasksSnapshotResponse {
            snapshotCalls += 1
            if (forceRefresh) {
                forceRefreshCalls += 1
                if (failForceSync) error("backend unavailable")
                return forceSyncSnapshot ?: effectiveSnapshot
            } else {
                normalCalls += 1
                return effectiveSnapshot
            }
        }

        override suspend fun startTask(id: String, request: TaskActionRequest): MutationResponse {
            startRequests += request
            return startHandler?.invoke(request) ?: response()
        }

        override suspend fun postponeStart(id: String, request: TaskActionRequest): MutationResponse {
            postponeRequests += request
            if (cancelPostpone) throw CancellationException("cancelled")
            if (failPostpone) error("backend unavailable")
            return postponeHandler?.invoke(request) ?: response()
        }

        override suspend fun completeTask(id: String, request: TaskActionRequest): MutationResponse {
            if (failComplete) error("backend unavailable")
            return completeHandler?.invoke(request) ?: response()
        }
        override suspend fun extendTask(id: String, request: ExtendTaskRequest): MutationResponse = response()
        override suspend fun updateTask(id: String, request: UpdateTaskRequest): MutationResponse = response()
        override suspend fun checkChecklistItem(
            taskId: String,
            childId: String,
            request: FocusedChecklistMutationRequest
        ): MutationResponse {
            return checkHandler?.invoke(request) ?: response()
        }
        override suspend fun uncheckChecklistItem(
            taskId: String,
            childId: String,
            request: FocusedChecklistMutationRequest
        ): MutationResponse {
            return response()
        }
        private fun response(): MutationResponse = MutationResponse(
            snapshot = effectiveSnapshot
        )
    }

    private class FakeSnapshotStore(
        initial: TasksSnapshotResponse? = null,
        private val throwOnSave: Boolean = false
    ) : SnapshotStorage {
        var saved: TasksSnapshotResponse? = initial

        override fun save(snapshot: TasksSnapshotResponse) {
            if (throwOnSave) error("disk unavailable")
            saved = snapshot
        }

        override fun load(): TasksSnapshotResponse? = saved
    }

    private class RecordingAlarmScheduler(
        private var failScheduleCalls: Int = 0
    ) : AlarmScheduler {
        val scheduled = mutableListOf<ScheduledAlarm>()
        val cancelled = mutableListOf<ScheduledAlarmIdentity>()

        override fun schedule(alarm: ScheduledAlarm) {
            if (failScheduleCalls > 0) {
                failScheduleCalls -= 1
                error("alarm manager unavailable")
            }
            scheduled += alarm
        }

        override fun cancel(identity: ScheduledAlarmIdentity) {
            cancelled += identity
        }
    }

    private data class ErrorRecord(
        val action: BlockerAction,
        val taskId: String?,
        val message: String,
        val throwableMessage: String? = null
    )

    private class RecordingErrorReporter : BlockerErrorReporting {
        val errors = mutableListOf<ErrorRecord>()

        override fun report(
            action: BlockerAction,
            taskId: String?,
            message: String,
            showNotification: Boolean,
            throwable: Throwable?
        ) {
            errors += ErrorRecord(action, taskId, message, throwable?.message)
        }
    }

    private class RecordingBlockerNotificationCleaner : BlockerNotificationCleaner {
        val cleaned = mutableListOf<NotificationCleaned>()

        override fun cancelForTask(taskId: String, occurrenceKey: String) {
            cleaned += NotificationCleaned(taskId, occurrenceKey)
        }
    }

    private data class NotificationCleaned(val taskId: String, val occurrenceKey: String)
}
