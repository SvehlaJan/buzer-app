package app.buzer.android.blockers

import app.buzer.android.enforcement.AlarmKind
import app.buzer.android.enforcement.BlockerAction
import app.buzer.android.enforcement.BlockerErrorReporting
import app.buzer.android.enforcement.CoordinatorActionResult
import app.buzer.android.ui.screens.BlockerActionState
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HealthStatus
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.model.TasksSnapshotResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockerHostControllerTest {
    @Test
    fun initialWarningFromOverlaySurvivesLifecycleRefreshWhileTaskRemainsUnresolvedAndClearsOnExplicitUserRefresh() = runBlocking {
        val source = FakeSource(refreshed = snapshot(task("task-1")))
        var finishCount = 0
        val controller = controller(this, source, onFinish = { finishCount++ })

        controller.bindTarget("task-1", "occurrence-current", initialWarningMessage = "Todoist is unavailable")
        controller.refresh(explicitUserRefresh = false).join()

        assertEquals("Todoist is unavailable", controller.warningMessage)
        assertFalse(controller.checking)
        assertEquals("task-1", controller.latestTask?.id)
        assertEquals(0, finishCount)

        controller.refresh(explicitUserRefresh = true).join()

        assertNull(controller.warningMessage)
        assertEquals(1, source.forceSyncCalls)
        assertEquals(0, finishCount)
    }

    @Test
    fun lifecycleRefreshFailureKeepsHandoffWarningAndReportsTheFailure() = runBlocking {
        val source = FakeSource(refreshFailure = IllegalStateException("network down"))
        val reporter = RecordingReporter()
        val controller = controller(this, source, reporter = reporter)

        controller.bindTarget("task-1", "occurrence-current", initialWarningMessage = "Todoist is unavailable")
        controller.refresh(explicitUserRefresh = false).join()

        assertEquals("Todoist is unavailable", controller.warningMessage)
        assertEquals(listOf(BlockerAction.REFRESH_SNAPSHOT), reporter.actions)
    }

    @Test
    fun lifecycleRefreshFailureWithoutHandoffWarningShowsStaleWarning() = runBlocking {
        val source = FakeSource(refreshFailure = IllegalStateException("network down"))
        val controller = controller(this, source)

        controller.bindTarget("task-1", "occurrence-current", initialWarningMessage = null)
        controller.refresh(explicitUserRefresh = false).join()

        assertEquals(BlockerHostController.STALE_BLOCKER_WARNING, controller.warningMessage)
    }

    @Test
    fun explicitRefreshFailureShowsRefreshFailedAndReportsWithoutNotification() = runBlocking {
        val source = FakeSource(refreshed = snapshot(task("task-1")), forceSyncFailure = IllegalStateException("boom"))
        val reporter = RecordingReporter()
        val controller = controller(this, source, reporter = reporter)

        controller.bindTarget("task-1", "occurrence-current", initialWarningMessage = "Todoist is unavailable")
        controller.refresh(explicitUserRefresh = true).join()

        assertEquals("Refresh failed", controller.warningMessage)
        assertEquals(listOf(BlockerAction.REFRESH_SNAPSHOT), reporter.actions)
        assertEquals(listOf(false), reporter.notificationFlags)
        assertFalse(controller.checking)
    }

    @Test
    fun rebindFromNewIntentUpdatesTaskOccurrenceAndInitialWarning() = runBlocking {
        val controller = controller(this, FakeSource(refreshed = snapshot(task("task-2", occurrenceKey = "occurrence-2"))))

        controller.bindTarget("task-1", "occurrence-1", initialWarningMessage = "first")
        controller.bindTarget("task-2", "occurrence-2", initialWarningMessage = "second")

        assertEquals("task-2", controller.taskId)
        assertEquals("occurrence-2", controller.occurrenceKey)
        assertEquals("second", controller.warningMessage)

        controller.bindTarget("task-2", "occurrence-3", initialWarningMessage = null)

        assertEquals("occurrence-3", controller.occurrenceKey)
        assertNull(controller.warningMessage)
    }

    @Test
    fun resolvedSnapshotDuringRefreshCallsOnResolvedAndFinishes() = runBlocking {
        val source = FakeSource(refreshed = snapshot(task("task-1", state = CommitmentState.completed, isCompleted = true)))
        var finishCount = 0
        val controller = controller(this, source, onFinish = { finishCount++ })

        controller.bindTarget("task-1", "occurrence-current", initialWarningMessage = null)
        controller.refresh(explicitUserRefresh = false).join()

        assertEquals(1, finishCount)
    }

    @Test
    fun startedTaskResolvesStartBlockerButNotDeadlineBlocker() = runBlocking {
        val started = snapshot(task("task-1", state = CommitmentState.started))
        var startFinishes = 0
        var deadlineFinishes = 0
        val start = controller(this, FakeSource(refreshed = started), onFinish = { startFinishes++ })
        val deadline = controller(
            this,
            FakeSource(refreshed = started),
            kind = AlarmKind.DEADLINE_BLOCKER,
            onFinish = { deadlineFinishes++ }
        )

        start.bindTarget("task-1", "occurrence-current", null)
        deadline.bindTarget("task-1", "occurrence-current", null)
        start.refresh(explicitUserRefresh = false).join()
        deadline.refresh(explicitUserRefresh = false).join()

        assertEquals(1, startFinishes)
        assertEquals(0, deadlineFinishes)
        assertTrue(deadline.snapshotConfirmed)
        assertEquals("task-1", deadline.currentTask?.id)
    }

    @Test
    fun nonSuccessMutationSetsWarningMessageAndReenablesActions() = runBlocking {
        val controller = controller(this, FakeSource(refreshed = snapshot(task("task-1"))))
        controller.bindTarget("task-1", "occurrence-current", null)
        val gate = CompletableDeferred<CoordinatorActionResult>()
        var secondRan = false

        val job = controller.runMutation("Starting...") { gate.await() }
        yield()
        assertEquals(BlockerActionState.Loading("Starting..."), controller.actionState)
        controller.runMutation("Postponing...") {
            secondRan = true
            CoordinatorActionResult.Success
        }
        gate.complete(CoordinatorActionResult.Warning("todoist_delivery_unknown", "Delivery unknown", null))
        job?.join()

        assertFalse(secondRan)
        assertEquals("Delivery unknown", controller.warningMessage)
        assertEquals(BlockerActionState.Idle, controller.actionState)
    }

    @Test
    fun successfulMutationFinishesUnlessAskedToStayOpen() = runBlocking {
        val source = FakeSource(refreshed = snapshot(task("task-1")), cached = snapshot(task("task-1", occurrenceKey = "occurrence-current")))
        var finishCount = 0
        val controller = controller(this, source, kind = AlarmKind.DEADLINE_BLOCKER, onFinish = { finishCount++ })
        controller.bindTarget("task-1", "occurrence-current", null)

        controller.runMutation("Checking...", closeOnSuccess = false) { CoordinatorActionResult.Success }?.join()
        assertEquals(0, finishCount)
        assertEquals("task-1", controller.latestTask?.id)

        controller.runMutation("Completing...") { CoordinatorActionResult.Success }?.join()
        assertEquals(1, finishCount)
    }

    @Test
    fun childActionInFlightUsesKindSpecificMessage() = runBlocking {
        val start = controller(this, FakeSource())
        val deadline = controller(this, FakeSource(), kind = AlarmKind.DEADLINE_BLOCKER)

        start.runMutation("Starting...") { CoordinatorActionResult.ChildActionInFlight }?.join()
        deadline.runMutation("Checking...") { CoordinatorActionResult.ChildActionInFlight }?.join()

        assertEquals("Action already in progress", start.warningMessage)
        assertEquals("Checklist update is already in progress", deadline.warningMessage)
    }

    private fun controller(
        scope: CoroutineScope,
        source: FakeSource,
        kind: AlarmKind = AlarmKind.START_BLOCKER,
        reporter: RecordingReporter = RecordingReporter(),
        onFinish: () -> Unit = {}
    ) = BlockerHostController(
        kind = kind,
        source = source,
        errorReporter = reporter,
        scope = scope,
        onFinish = onFinish
    )

    private class FakeSource(
        private val cached: TasksSnapshotResponse? = null,
        private val refreshed: TasksSnapshotResponse? = null,
        private val refreshFailure: Throwable? = null,
        private val forceSyncFailure: Throwable? = null
    ) : BlockerSnapshotSource {
        var forceSyncCalls = 0

        override fun loadLatestSnapshot(): TasksSnapshotResponse? = cached

        override suspend fun refreshAndReconcile(): TasksSnapshotResponse {
            refreshFailure?.let { throw it }
            return requireNotNull(refreshed)
        }

        override suspend fun forceSyncAndReconcile(): TasksSnapshotResponse {
            forceSyncCalls++
            forceSyncFailure?.let { throw it }
            return requireNotNull(refreshed)
        }
    }

    private class RecordingReporter : BlockerErrorReporting {
        val actions = mutableListOf<BlockerAction>()
        val notificationFlags = mutableListOf<Boolean>()

        override fun report(
            action: BlockerAction,
            taskId: String?,
            message: String,
            showNotification: Boolean,
            throwable: Throwable?
        ) {
            actions += action
            notificationFlags += showNotification
        }
    }

    private fun snapshot(vararg tasks: TaskSnapshotItem): TasksSnapshotResponse =
        TasksSnapshotResponse(
            scheduleVersion = 1,
            serverTime = "2026-05-28T09:00:00Z",
            tasks = tasks.toList(),
            health = emptyList<HealthStatus>(),
            snapshotGeneration = 1
        )

    private fun task(
        id: String,
        state: CommitmentState = CommitmentState.planned,
        isCompleted: Boolean = false,
        occurrenceKey: String = "occurrence-current"
    ): TaskSnapshotItem =
        TaskSnapshotItem(
            id = id,
            todoistTaskId = "todoist-$id",
            title = "Task $id",
            projectId = null,
            assigneeId = "user-self",
            creatorId = "user-self",
            dueDatetime = "2026-05-28T12:00:00Z",
            durationMinutes = 60,
            startAt = "2026-05-28T12:00:00Z",
            state = state,
            isAssignedToUser = true,
            isCreatedByUser = true,
            isCompleted = isCompleted,
            isDeleted = false,
            validationErrors = emptyList(),
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-05-28T09:00:00Z",
            occurrenceKey = occurrenceKey
        )
}
