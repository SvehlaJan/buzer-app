package app.buzer.android.enforcement

import app.buzer.android.sync.SnapshotStorage
import app.buzer.shared.api.BuzerApiException
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.ExtendTaskRequest
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.ReconciliationStatus
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.TaskRecurrence
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.MutationOutcome
import app.buzer.shared.model.MutationResponse
import app.buzer.shared.model.RescheduleTaskRequest
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TaskActionRequest
import app.buzer.shared.model.TasksSnapshotResponse
import app.buzer.shared.model.UpdateTaskRequest
import app.buzer.shared.state.SnapshotReducer
import app.buzer.shared.state.SnapshotWithCallerDraft
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
                MutationResponse(
                actionId = "action-start-1",
                outcome = MutationOutcome.completed,
                snapshot = returnedSnapshot
                )
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
            TaskActionRequest(
                actionId = "action-start-1",
                deviceId = "device-1",
                expectedOccurrenceKey = "occurrence-task-1"
            )
        )

        assertEquals(CoordinatorActionResult.Success, result)
        assertEquals(returnedSnapshot, store.saved)
        assertEquals(0, api.snapshotCalls)
    }

    @Test
    fun pendingMutationCancelsOnlyTheTaskSpeculativeAlarmsAndReturnsPending() = runBlocking {
        val pendingSnapshot = snapshot(
            task("task-1", reconciliationStatus = ReconciliationStatus.pending),
            task("task-2"),
            snapshotGeneration = 2
        )
        val scheduler = RecordingAlarmScheduler()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(
                snapshot(task("task-1")),
                startHandler = { request ->
                    MutationResponse(request.actionId, MutationOutcome.reconciliation_pending, pendingSnapshot)
                }
            ),
            snapshotStore = FakeSnapshotStore(snapshot(task("task-1"), task("task-2"))),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )
        coordinator.reconcileLatestSnapshot()
        scheduler.scheduled.clear()

        val result = coordinator.startTask("task-1", action("pending-1"))

        assertTrue(result is CoordinatorActionResult.Pending)
        assertTrue(scheduler.cancelled.any { it.taskId == "task-1" })
        assertFalse(scheduler.cancelled.any { it.taskId == "task-2" })
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

        val result = coordinator.startTask("task-1", action("conflict-1"))

        val stale = result as CoordinatorActionResult.Stale
        assertEquals(CoordinatorActionResult.Stale(conflictSnapshot, "stale_task"), stale)
        assertEquals(conflictSnapshot, store.saved)
        assertEquals(
            "keep this edit",
            SnapshotReducer.replaceAuthoritative(callerOwnedState, stale.snapshot).callerDraft
        )
    }

    @Test
    fun rateLimitedRetryUsesTheOriginalImmutableActionRequest() = runBlocking {
        val returnedSnapshot = snapshot(task("task-1", state = CommitmentState.started))
        var calls = 0
        val api = FakeBuzerApi(
            snapshot(task("task-1")),
            startHandler = { request ->
                calls += 1
                if (calls == 1) {
                    throw BuzerApiException(
                        status = 429,
                        code = "rate_limited",
                        retryAfterSeconds = 17,
                        snapshot = null
                    )
                }
                MutationResponse(request.actionId, MutationOutcome.completed, returnedSnapshot)
            }
        )
        val request = action("retry-same-id")
        val coordinator = EnforcementCoordinator(
            api = api,
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val rateLimited = coordinator.startTask("task-1", request)

        assertEquals(17, (rateLimited as CoordinatorActionResult.RetryAfter).retryAfterSeconds)
        assertEquals(CoordinatorActionResult.Success, rateLimited.retry())
        assertEquals(listOf(request, request), api.startRequests)
    }

    @Test
    fun focusedChildRejectsOnlyConcurrentTapThenAcceptsFreshIntentAfterResponse() = runBlocking {
        val releaseFirstRequest = CompletableDeferred<Unit>()
        val startedFirstRequest = CompletableDeferred<Unit>()
        val api = FakeBuzerApi(
            snapshot(task("task-1")),
            checkHandler = { request ->
                startedFirstRequest.complete(Unit)
                releaseFirstRequest.await()
                MutationResponse(request.actionId, MutationOutcome.completed, snapshot(task("task-1")))
            }
        )
        val coordinator = EnforcementCoordinator(
            api = api,
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )
        val firstRequest = focusedAction("check-1", "child-r1")
        val first = async { coordinator.checkChild("task-1", "child-1", firstRequest) }
        startedFirstRequest.await()

        assertEquals(
            CoordinatorActionResult.ChildActionInFlight,
            coordinator.uncheckChild("task-1", "child-1", focusedAction("uncheck-blocked", "child-r1"))
        )

        releaseFirstRequest.complete(Unit)
        assertEquals(CoordinatorActionResult.Success, first.await())
        assertEquals(
            CoordinatorActionResult.Success,
            coordinator.uncheckChild("task-1", "child-1", focusedAction("uncheck-fresh", "child-r2"))
        )
        assertEquals(listOf("check-1", "uncheck-fresh"), api.focusedActionIds)
    }

    @Test
    fun localSchedulerFailureAfterConfirmedMutationDoesNotReportOrRetryThePost() = runBlocking {
        val responseSnapshot = snapshot(task("task-1", state = CommitmentState.started))
        val scheduler = RecordingAlarmScheduler(failScheduleCalls = 1)
        val reporter = RecordingErrorReporter()
        val store = FakeSnapshotStore()
        val api = FakeBuzerApi(
            snapshot(task("task-1")),
            postponeHandler = { request -> MutationResponse(request.actionId, MutationOutcome.completed, responseSnapshot) }
        )
        val failingCoordinator = EnforcementCoordinator(
            api = api,
            snapshotStore = store,
            alarmScheduler = scheduler,
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = failingCoordinator.postponeStart("task-1", action("local-scheduler-failure"))

        assertTrue(result is CoordinatorActionResult.LocalReconciliationFailed)
        assertEquals(responseSnapshot, (result as CoordinatorActionResult.LocalReconciliationFailed).snapshot)
        assertEquals(responseSnapshot, store.saved)
        assertTrue(reporter.errors.isEmpty())
        assertEquals(1, api.postponeRequests.size)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun localStoreFailureAfterConfirmedMutationDoesNotReportOrRetryThePost() = runBlocking {
        val responseSnapshot = snapshot(task("task-1", state = CommitmentState.started))
        val api = FakeBuzerApi(
            snapshot(task("task-1")),
            startHandler = { request -> MutationResponse(request.actionId, MutationOutcome.completed, responseSnapshot) }
        )
        val reporter = RecordingErrorReporter()
        val coordinator = EnforcementCoordinator(
            api = api,
            snapshotStore = FakeSnapshotStore(throwOnSave = true),
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.startTask("task-1", action("local-store-failure"))

        assertTrue(result is CoordinatorActionResult.LocalReconciliationFailed)
        assertEquals(responseSnapshot, (result as CoordinatorActionResult.LocalReconciliationFailed).snapshot)
        assertTrue(reporter.errors.isEmpty())
        assertEquals(1, api.startRequests.size)
    }

    @Test
    fun localFailureWhileApplyingConflictSnapshotRemainsDistinctFromBackendConflict() = runBlocking {
        val conflictSnapshot = snapshot(task("task-1", state = CommitmentState.started))
        val reporter = RecordingErrorReporter()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(
                snapshot(task("task-1")),
                startHandler = {
                    throw BuzerApiException(409, "stale_task", null, conflictSnapshot)
                }
            ),
            snapshotStore = FakeSnapshotStore(throwOnSave = true),
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.startTask("task-1", action("conflict-local-failure"))

        assertTrue(result is CoordinatorActionResult.LocalReconciliationFailed)
        assertEquals(conflictSnapshot, (result as CoordinatorActionResult.LocalReconciliationFailed).snapshot)
        assertTrue(reporter.errors.isEmpty())
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
                startHandler = { request -> MutationResponse(request.actionId, MutationOutcome.completed, staleReturned) }
            ),
            snapshotStore = FakeSnapshotStore(newerCached),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            blockerNotificationCleaner = notifications,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.startTask("task-1", action("stale-root"))

        assertTrue(result is CoordinatorActionResult.Stale)
        assertEquals(newerCached, (result as CoordinatorActionResult.Stale).snapshot)
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
                startHandler = { request -> MutationResponse(request.actionId, MutationOutcome.completed, equalButDifferent) }
            ),
            snapshotStore = FakeSnapshotStore(cached),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            blockerNotificationCleaner = notifications,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        assertEquals(CoordinatorActionResult.Success, coordinator.startTask("task-1", action("equal-replay")))

        assertEquals(cached, coordinator.loadLatestSnapshot())
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
        assertEquals(listOf(NotificationCleaned("task-1", "occurrence-task-1")), notifications.cleaned)
    }

    @Test
    fun positiveServerGenerationReplacesLegacyZeroCacheAsWholeAuthority() = runBlocking {
        val legacy = snapshot(task("legacy-root"), snapshotGeneration = 0)
        val returned = snapshot(
            task("task-1", occurrenceKey = "next-occurrence", recurrence = TaskRecurrence(
                kind = RecurrenceKind.supported,
                preset = RecurrencePreset.daily
            )),
            snapshotGeneration = 1
        )
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(
                snapshot(task("task-1")),
                startHandler = { request -> MutationResponse(request.actionId, MutationOutcome.completed, returned) }
            ),
            snapshotStore = FakeSnapshotStore(legacy),
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        assertEquals(CoordinatorActionResult.Success, coordinator.startTask("task-1", action("replace-legacy")))
        assertEquals(returned, coordinator.loadLatestSnapshot())
    }

    @Test
    fun confirmedOneTimeDoneCancelsOnlyReturnedOccurrenceAlarmsWithoutFetch() = runBlocking {
        val old = task("task-1")
        val completed = task("task-1", state = CommitmentState.completed, isCompleted = true)
        val scheduler = RecordingAlarmScheduler()
        val api = FakeBuzerApi(
            snapshot(old),
            completeHandler = {
                request -> MutationResponse(
                    request.actionId,
                    MutationOutcome.completed,
                    snapshot(completed, snapshotGeneration = 2)
                )
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

        assertEquals(CoordinatorActionResult.Success, coordinator.completeTask("task-1", action("done-one-time")))

        assertTrue(scheduler.cancelled.all { it.taskId == "task-1" && it.occurrenceKey == old.occurrenceKey })
        assertTrue(scheduler.cancelled.isNotEmpty())
        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(0, api.snapshotCalls)
    }

    @Test
    fun confirmedRecurringDoneCancelsOldOccurrenceAndSchedulesReturnedNextOccurrenceWithoutFetch() = runBlocking {
        val old = task(
            "task-1",
            recurrence = TaskRecurrence(kind = RecurrenceKind.supported, preset = RecurrencePreset.daily)
        )
        val next = task(
            "task-1",
            occurrenceKey = "occurrence-task-1-next",
            dueDatetime = "2026-05-29T13:00:00Z",
            startAt = "2026-05-29T12:00:00Z",
            recurrence = TaskRecurrence(kind = RecurrenceKind.supported, preset = RecurrencePreset.daily)
        )
        val scheduler = RecordingAlarmScheduler()
        val api = FakeBuzerApi(
            snapshot(old),
            completeHandler = {
                request -> MutationResponse(
                    request.actionId,
                    MutationOutcome.completed,
                    snapshot(next, snapshotGeneration = 2)
                )
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

        assertEquals(CoordinatorActionResult.Success, coordinator.completeTask("task-1", action("done-recurring")))

        assertTrue(scheduler.cancelled.all { it.occurrenceKey == old.occurrenceKey })
        assertTrue(scheduler.cancelled.isNotEmpty())
        assertEquals(3, scheduler.scheduled.size)
        assertTrue(scheduler.scheduled.all { it.occurrenceKey == next.occurrenceKey })
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
    fun completedAndDeletedTasksScheduleNoNewAlarmsAfterRefresh() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(
                snapshot(
                    task("done", state = CommitmentState.completed, isCompleted = true),
                    task("deleted", state = CommitmentState.deleted, isDeleted = true)
                )
            ),
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        coordinator.refreshAndReconcile()

        assertTrue(scheduler.cancelled.isEmpty())
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun refreshSchedulesLatestDesiredAlarmsThroughTheIdentityRegistry() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1"))),
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T10:30:00Z" }
        )

        coordinator.refreshAndReconcile()

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
    fun postponeFailureReportsErrorAndSchedulesFiveMinuteStartBlockerRetry() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val reporter = RecordingErrorReporter()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1")), failPostpone = true),
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = scheduler,
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.postponeStart("task-1", action("postpone-1"))

        assertTrue(result is CoordinatorActionResult.FailedAndHidden)
        assertEquals(
            listOf(scheduled("task-1", AlarmKind.START_BLOCKER, "2026-05-28T09:35:00Z")),
            scheduler.scheduled
        )
        assertEquals(listOf(ErrorRecord(BlockerAction.POSTPONE_START, "task-1", "backend unavailable")), reporter.errors)
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
            coordinator.postponeStart("task-1", action("postpone-1"))
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

        val result = coordinator.startTask("task-1", action("start-1"))

        assertEquals(CoordinatorActionResult.Success, result)
        assertEquals(
            listOf(NotificationCleaned("task-1", "occurrence-task-1")),
            notifications.cleaned
        )
    }

    @Test
    fun actionCleansTheTriggeringOccurrenceRatherThanDifferentCachedOccurrence() = runBlocking {
        val notifications = RecordingBlockerNotificationCleaner()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1", state = CommitmentState.started))),
            snapshotStore = FakeSnapshotStore(snapshot(task("task-1", occurrenceKey = "occurrence-cached"))),
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = RecordingErrorReporter(),
            blockerNotificationCleaner = notifications,
            nowProvider = { "2026-05-28T11:01:00Z" }
        )

        coordinator.startTask("task-1", action("start-1", occurrenceKey = "occurrence-alarm"))

        assertEquals(listOf(NotificationCleaned("task-1", "occurrence-alarm")), notifications.cleaned)
    }

    @Test
    fun actionCleansTheTriggeringOccurrenceWhenCacheMisses() = runBlocking {
        val notifications = RecordingBlockerNotificationCleaner()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1", state = CommitmentState.started))),
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = RecordingAlarmScheduler(),
            errorReporter = RecordingErrorReporter(),
            blockerNotificationCleaner = notifications,
            nowProvider = { "2026-05-28T11:01:00Z" }
        )

        coordinator.startTask("task-1", action("start-1", occurrenceKey = "occurrence-alarm"))

        assertEquals(listOf(NotificationCleaned("task-1", "occurrence-alarm")), notifications.cleaned)
    }

    @Test
    fun deadlineCompleteFailureReportsErrorWithoutRetry() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val reporter = RecordingErrorReporter()
        val cached = snapshot(task("task-1"))
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(cached, failComplete = true),
            snapshotStore = FakeSnapshotStore(cached),
            alarmScheduler = scheduler,
            scheduledAlarmRegistry = ScheduledAlarmRegistry(InMemoryScheduledAlarmRegistryStorage()),
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )
        coordinator.reconcileLatestSnapshot()
        scheduler.scheduled.clear()

        val result = coordinator.completeTask("task-1", action("complete-1"))

        assertTrue(result is CoordinatorActionResult.FailedAndHidden)
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
        assertEquals(listOf(ErrorRecord(BlockerAction.COMPLETE_TASK, "task-1", "backend unavailable")), reporter.errors)
    }

    @Test
    fun dismissMorningReviewFailureReportsErrorWithoutRetry() = runBlocking {
        val scheduler = RecordingAlarmScheduler()
        val reporter = RecordingErrorReporter()
        val coordinator = EnforcementCoordinator(
            api = FakeBuzerApi(snapshot(task("task-1")), failDismissMorningReview = true),
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = scheduler,
            errorReporter = reporter,
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        val result = coordinator.dismissMorningReview(EnforcementCoordinator.MORNING_REVIEW_OCCURRENCE_KEY)

        assertTrue(result is CoordinatorActionResult.FailedAndHidden)
        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(
            listOf(
                ErrorRecord(
                    BlockerAction.MORNING_REVIEW_DISMISS,
                    EnforcementCoordinator.MORNING_REVIEW_TASK_ID,
                    "backend unavailable"
                )
            ),
            reporter.errors
        )
    }

    @Test
    fun reconcileLatestSnapshotReturnsFalseAndSchedulesNothingWhenCacheMisses() {
        val scheduler = RecordingAlarmScheduler()
        val api = FakeBuzerApi(snapshot(task("task-1")))
        val coordinator = EnforcementCoordinator(
            api = api,
            snapshotStore = FakeSnapshotStore(),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        assertFalse(coordinator.reconcileLatestSnapshot())
        assertEquals(0, api.snapshotCalls)
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun reconcileLatestSnapshotSkipsUnorderableLegacyGeneration() {
        val scheduler = RecordingAlarmScheduler()
        val api = FakeBuzerApi(snapshot(task("task-1")))
        val coordinator = EnforcementCoordinator(
            api = api,
            snapshotStore = FakeSnapshotStore(snapshot(task("task-1"), snapshotGeneration = 0)),
            alarmScheduler = scheduler,
            errorReporter = RecordingErrorReporter(),
            nowProvider = { "2026-05-28T09:30:00Z" }
        )

        assertFalse(coordinator.reconcileLatestSnapshot())
        assertEquals(0, api.snapshotCalls)
        assertTrue(scheduler.scheduled.isEmpty())
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
        reconciliationStatus: ReconciliationStatus = ReconciliationStatus.none,
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
            reconciliationStatus = reconciliationStatus,
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
        actionId: String,
        occurrenceKey: String = "occurrence-task-1"
    ): TaskActionRequest = TaskActionRequest(
        actionId = actionId,
        deviceId = "device-1",
        expectedOccurrenceKey = occurrenceKey
    )

    private fun focusedAction(
        actionId: String,
        childRevision: String
    ): app.buzer.shared.model.FocusedChecklistMutationRequest =
        app.buzer.shared.model.FocusedChecklistMutationRequest(
            actionId = actionId,
            deviceId = "device-1",
            expectedOccurrenceKey = "occurrence-task-1",
            expectedChildRevision = childRevision
        )

    private class FakeBuzerApi(
        private val snapshot: TasksSnapshotResponse,
        private val startHandler: ((TaskActionRequest) -> MutationResponse)? = null,
        private val postponeHandler: ((TaskActionRequest) -> MutationResponse)? = null,
        private val checkHandler: (suspend (app.buzer.shared.model.FocusedChecklistMutationRequest) -> MutationResponse)? = null,
        private val completeHandler: ((TaskActionRequest) -> MutationResponse)? = null,
        private val failPostpone: Boolean = false,
        private val failComplete: Boolean = false,
        private val cancelPostpone: Boolean = false,
        private val failDismissMorningReview: Boolean = false
    ) : EnforcementApi {
        var snapshotCalls = 0
            private set
        val startRequests = mutableListOf<TaskActionRequest>()
        val postponeRequests = mutableListOf<TaskActionRequest>()
        val focusedActionIds = mutableListOf<String>()

        override suspend fun getSnapshot(): TasksSnapshotResponse {
            snapshotCalls += 1
            return snapshot
        }
        override suspend fun startTask(id: String, request: TaskActionRequest): MutationResponse {
            startRequests += request
            return startHandler?.invoke(request) ?: response(request.actionId)
        }

        override suspend fun postponeStart(id: String, request: TaskActionRequest): MutationResponse {
            postponeRequests += request
            if (cancelPostpone) throw CancellationException("cancelled")
            if (failPostpone) error("backend unavailable")
            return postponeHandler?.invoke(request) ?: response(request.actionId)
        }

        override suspend fun completeTask(id: String, request: TaskActionRequest): MutationResponse {
            if (failComplete) error("backend unavailable")
            return completeHandler?.invoke(request) ?: response(request.actionId)
        }
        override suspend fun extendTask(id: String, request: ExtendTaskRequest): MutationResponse = response(request.actionId)
        override suspend fun rescheduleTask(id: String, request: RescheduleTaskRequest): MutationResponse = response(request.actionId)
        override suspend fun updateTask(id: String, request: UpdateTaskRequest): MutationResponse = response(request.actionId)
        override suspend fun checkChecklistItem(
            taskId: String,
            childId: String,
            request: app.buzer.shared.model.FocusedChecklistMutationRequest
        ): MutationResponse {
            focusedActionIds += request.actionId
            return checkHandler?.invoke(request) ?: response(request.actionId)
        }
        override suspend fun uncheckChecklistItem(
            taskId: String,
            childId: String,
            request: app.buzer.shared.model.FocusedChecklistMutationRequest
        ): MutationResponse {
            focusedActionIds += request.actionId
            return response(request.actionId)
        }
        override suspend fun dismissMorningReview() {
            if (failDismissMorningReview) error("backend unavailable")
        }

        private fun response(actionId: String): MutationResponse = MutationResponse(
            actionId = actionId,
            outcome = MutationOutcome.completed,
            snapshot = snapshot
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
        val message: String
    )

    private class RecordingErrorReporter : BlockerErrorReporting {
        val errors = mutableListOf<ErrorRecord>()

        override fun report(action: BlockerAction, taskId: String?, message: String, showNotification: Boolean) {
            errors += ErrorRecord(action, taskId, message)
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
