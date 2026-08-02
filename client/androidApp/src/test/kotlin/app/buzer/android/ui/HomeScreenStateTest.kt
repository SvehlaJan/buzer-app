package app.buzer.android.ui

import app.buzer.android.ui.screens.HomeContentState
import app.buzer.android.ui.screens.buildHomeScreenState
import app.buzer.shared.model.ChecklistItem
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.RecurrenceKind
import app.buzer.shared.model.RecurrencePreset
import app.buzer.shared.model.ReconciliationStatus
import app.buzer.shared.model.TaskRecurrence
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.state.SnapshotViewState
import app.buzer.shared.ui.home.HomeAgendaRowState
import app.buzer.shared.ui.home.HomeAttentionAction
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeScreenStateTest {

    @Test
    fun test1_ownerFutureTasksGroupAndSortDeterministically() {
        val tasks = listOf(
            // Today (2026-08-02), start 14:00
            createTaskFixture("task-today-2", dueDatetime = "2026-08-02T14:00:00Z", durationMinutes = 30),
            // Today (2026-08-02), start 12:00
            createTaskFixture("task-today-1", dueDatetime = "2026-08-02T12:00:00Z", durationMinutes = 60),
            // Tomorrow (2026-08-03)
            createTaskFixture("task-tomorrow", dueDatetime = "2026-08-03T09:00:00Z", durationMinutes = 45),
            // Later in year (2026-08-04)
            createTaskFixture("task-later-year", dueDatetime = "2026-08-04T10:00:00Z", durationMinutes = 30),
            // Next year (2027-01-02)
            createTaskFixture("task-next-year", dueDatetime = "2027-01-02T08:00:00Z", durationMinutes = 60)
        )
        val snapshot = snapshot(tasks)

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE)

        assertEquals(4, state.futureGroups.size)

        val group0 = state.futureGroups[0]
        assertEquals("Today · Sunday, August 2", group0.heading)
        assertEquals(listOf("task-today-1", "task-today-2"), group0.rows.map { it.taskId })

        val group1 = state.futureGroups[1]
        assertEquals("Tomorrow · Monday, August 3", group1.heading)
        assertEquals(listOf("task-tomorrow"), group1.rows.map { it.taskId })

        val group2 = state.futureGroups[2]
        assertEquals("Tuesday, August 4", group2.heading)
        assertEquals(listOf("task-later-year"), group2.rows.map { it.taskId })

        val group3 = state.futureGroups[3]
        assertEquals("Saturday, January 2, 2027", group3.heading)
        assertEquals(listOf("task-next-year"), group3.rows.map { it.taskId })
    }

    @Test
    fun test2_heroIsCurrentTaskEndingSoonestAndNoDuplicates() {
        val tasks = listOf(
            // Task ending at 11:00 (starts 09:30)
            createTaskFixture("current-later-end", dueDatetime = "2026-08-02T09:30:00Z", durationMinutes = 90),
            // Task ending at 10:30 (starts 09:30), ID "task-b"
            createTaskFixture("task-b", dueDatetime = "2026-08-02T09:30:00Z", durationMinutes = 60),
            // Task ending at 10:30 (starts 09:30), ID "task-a" (tie-breaker hero!)
            createTaskFixture("task-a", dueDatetime = "2026-08-02T09:30:00Z", durationMinutes = 60)
        )
        val snapshot = snapshot(tasks)

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE)

        val hero = assertNotNull(state.currentBlock)
        assertEquals("task-a", hero.taskId)
        assertEquals(2, state.overlappingCurrentRows.size)
        assertEquals(listOf("task-b", "current-later-end"), state.overlappingCurrentRows.map { it.taskId })

        // Ensure hero ID is not duplicated in any other list
        val allOtherIds = state.overlappingCurrentRows.map { it.taskId } +
            state.overdue.rows.map { it.taskId } +
            state.unassigned.rows.map { it.taskId } +
            state.futureGroups.flatMap { g -> g.rows.map { it.taskId } }
        assertFalse(allOtherIds.contains("task-a"), "Hero task ID must not be duplicated in other sections")
    }

    @Test
    fun test3_currentBoundaryUsesHalfOpenInterval() {
        val task = createTaskFixture("boundary-task", dueDatetime = "2026-08-02T10:00:00Z", durationMinutes = 60)
        val snapshot = snapshot(listOf(task))

        // At start (10:00:00Z) -> Current
        val stateAtStart = buildHomeScreenState(snapshot, now = Instant.parse("2026-08-02T10:00:00Z"), zoneId = FIXED_ZONE)
        assertNotNull(stateAtStart.currentBlock)
        assertEquals(0, stateAtStart.overdue.totalCount)

        // At 10:59:59Z -> Current
        val stateBeforeEnd = buildHomeScreenState(snapshot, now = Instant.parse("2026-08-02T10:59:59Z"), zoneId = FIXED_ZONE)
        assertNotNull(stateBeforeEnd.currentBlock)
        assertEquals(0, stateBeforeEnd.overdue.totalCount)

        // Exactly at end (11:00:00Z) -> Overdue
        val stateAtEnd = buildHomeScreenState(snapshot, now = Instant.parse("2026-08-02T11:00:00Z"), zoneId = FIXED_ZONE)
        assertNull(stateAtEnd.currentBlock)
        assertEquals(1, stateAtEnd.overdue.totalCount)
        assertEquals("boundary-task", stateAtEnd.overdue.rows.single().taskId)
    }

    @Test
    fun test4_overdueSortsNewestMissedFirstAndHonorsExpansion() {
        val tasks = listOf(
            createTaskFixture("overdue-oldest", dueDatetime = "2026-08-02T06:00:00Z", durationMinutes = 30), // ended 06:30
            createTaskFixture("overdue-mid1", dueDatetime = "2026-08-02T07:00:00Z", durationMinutes = 30),   // ended 07:30
            createTaskFixture("overdue-mid2", dueDatetime = "2026-08-02T08:00:00Z", durationMinutes = 30),   // ended 08:30
            createTaskFixture("overdue-mid3", dueDatetime = "2026-08-02T08:30:00Z", durationMinutes = 30),   // ended 09:00
            createTaskFixture("overdue-newest", dueDatetime = "2026-08-02T09:00:00Z", durationMinutes = 30) // ended 09:30
        )
        val snapshot = snapshot(tasks)

        // Collapsed (default): exposes 3, total 5
        val stateCollapsed = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE, overdueExpanded = false)
        assertEquals(5, stateCollapsed.overdue.totalCount)
        assertEquals(3, stateCollapsed.overdue.rows.size)
        assertEquals(
            listOf("overdue-newest", "overdue-mid3", "overdue-mid2"),
            stateCollapsed.overdue.rows.map { it.taskId }
        )

        // Expanded: exposes all 5
        val stateExpanded = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE, overdueExpanded = true)
        assertEquals(5, stateExpanded.overdue.totalCount)
        assertEquals(5, stateExpanded.overdue.rows.size)
        assertEquals(
            listOf("overdue-newest", "overdue-mid3", "overdue-mid2", "overdue-mid1", "overdue-oldest"),
            stateExpanded.overdue.rows.map { it.taskId }
        )
    }

    @Test
    fun test5_explicitAndImplicitOwnerTasksEnterOwnerSections() {
        val tasks = listOf(
            // Explicit owner: isAssignedToUser = true, assigneeId set
            createTaskFixture("explicit-owner", dueDatetime = "2026-08-02T14:00:00Z", durationMinutes = 30, isAssignedToUser = true, assigneeId = "me"),
            // Implicit owner: isAssignedToUser = true (personal project)
            createTaskFixture("implicit-owner", dueDatetime = "2026-08-02T15:00:00Z", durationMinutes = 30, isAssignedToUser = true, assigneeId = null)
        )
        val snapshot = snapshot(tasks)

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE)

        val todayGroup = state.futureGroups.single()
        assertEquals(listOf("explicit-owner", "implicit-owner"), todayGroup.rows.map { it.taskId })
    }

    @Test
    fun test6_unassignedRootsSortedByUpdatedAtDescendingWhenExpanded() {
        val unassignedTasks = listOf(
            createTaskFixture("unassigned-old", isAssignedToUser = false, assigneeId = null, updatedAt = "2026-08-01T10:00:00Z"),
            createTaskFixture("unassigned-newest", isAssignedToUser = false, assigneeId = null, updatedAt = "2026-08-02T09:00:00Z"),
            createTaskFixture("unassigned-mid", isAssignedToUser = false, assigneeId = null, updatedAt = "2026-08-01T18:00:00Z")
        )
        val snapshot = snapshot(unassignedTasks)

        // Collapsed (default): count = 3, rows empty
        val stateCollapsed = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE, unassignedExpanded = false)
        assertEquals(3, stateCollapsed.unassigned.totalCount)
        assertTrue(stateCollapsed.unassigned.rows.isEmpty())
        assertTrue(stateCollapsed.futureGroups.isEmpty())

        // Expanded: count = 3, rows sorted by updatedAt descending
        val stateExpanded = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE, unassignedExpanded = true)
        assertEquals(3, stateExpanded.unassigned.totalCount)
        assertEquals(
            listOf("unassigned-newest", "unassigned-mid", "unassigned-old"),
            stateExpanded.unassigned.rows.map { it.taskId }
        )
        assertEquals(listOf(HomeAgendaRowState.Unassigned, HomeAgendaRowState.Unassigned, HomeAgendaRowState.Unassigned), stateExpanded.unassigned.rows.map { it.state })
    }

    @Test
    fun test7_assignedOtherDefensiveFixtureOmitted() {
        val assignedOther = createTaskFixture(
            "assigned-other",
            isAssignedToUser = false,
            assigneeId = "other-user-123",
            dueDatetime = "2026-08-02T14:00:00Z",
            durationMinutes = 30
        )
        val snapshot = snapshot(listOf(assignedOther))

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE)

        assertNull(state.currentBlock)
        assertEquals(0, state.overdue.totalCount)
        assertEquals(0, state.unassigned.totalCount)
        assertTrue(state.futureGroups.isEmpty())
    }

    @Test
    fun test8_crossMidnightTaskUsesStartDateGroupAndNextDayMarker() {
        // Starts 2026-08-02 at 23:30 CEST (21:30 UTC), 60m duration -> ends 2026-08-03 at 00:30 CEST
        val crossMidnight = createTaskFixture("cross-midnight", dueDatetime = "2026-08-02T21:30:00Z", durationMinutes = 60)
        val snapshot = snapshot(listOf(crossMidnight))

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE)

        val group = state.futureGroups.single()
        assertEquals("Today · Sunday, August 2", group.heading)
        val row = group.rows.single()
        assertEquals("23:30-00:30 (+1d)", row.timeLabel)
    }

    @Test
    fun test9_recurringInputContributesSingleRowNoSpeculativeProjection() {
        val recurringTask = createTaskFixture(
            "recurring-task",
            dueDatetime = "2026-08-02T14:00:00Z",
            durationMinutes = 30,
            recurrence = TaskRecurrence(kind = RecurrenceKind.supported, preset = RecurrencePreset.daily)
        )
        val snapshot = snapshot(listOf(recurringTask))

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE)

        val totalRowsCount = state.futureGroups.sumOf { it.rows.size }
        assertEquals(1, totalRowsCount)
    }

    @Test
    fun test10_ownerInvalidAndPendingReconciliationProduceRepairAttentionAndExcludedFromTimedSections() {
        val invalidTask = createTaskFixture("invalid-task", dueDatetime = "2026-08-02T14:00:00Z", durationMinutes = 30, state = CommitmentState.invalid)
        val pendingTask = createTaskFixture("pending-task", dueDatetime = "2026-08-02T15:00:00Z", durationMinutes = 30, reconciliationStatus = ReconciliationStatus.pending)
        val validTask = createTaskFixture("valid-task", dueDatetime = "2026-08-02T16:00:00Z", durationMinutes = 30)

        val snapshot = SnapshotViewState(
            scheduleVersion = 1,
            serverTime = "2026-08-02T10:00:00Z",
            invalidTasks = listOf(invalidTask),
            activeBlockers = emptyList(),
            inProgressTasks = emptyList(),
            startingToday = listOf(validTask),
            healthWarnings = emptyList(),
            tasks = listOf(invalidTask, pendingTask, validTask)
        )

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE)

        val attention = assertNotNull(state.reviewAttention)
        assertEquals("2 invalid tasks need review", attention.message)
        assertEquals(HomeAttentionAction.OpenMorningReview, attention.action)
        assertTrue(state.canOpenMorningReview)

        val futureIds = state.futureGroups.flatMap { g -> g.rows.map { it.taskId } }
        assertEquals(listOf("valid-task"), futureIds)
    }

    @Test
    fun test11_completedDeletedAndUnsupportedHierarchyAbsent() {
        val tasks = listOf(
            createTaskFixture("completed-1", dueDatetime = "2026-08-02T14:00:00Z", durationMinutes = 30, isCompleted = true),
            createTaskFixture("completed-2", dueDatetime = "2026-08-02T14:00:00Z", durationMinutes = 30, state = CommitmentState.completed),
            createTaskFixture("deleted-1", dueDatetime = "2026-08-02T14:00:00Z", durationMinutes = 30, isDeleted = true),
            createTaskFixture("child-root", dueDatetime = "2026-08-02T14:00:00Z", durationMinutes = 30, hierarchyStatus = HierarchyStatus.unsupported)
        )
        val snapshot = snapshot(tasks)

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE)

        assertNull(state.currentBlock)
        assertEquals(0, state.overdue.totalCount)
        assertEquals(0, state.unassigned.totalCount)
        assertTrue(state.futureGroups.isEmpty())
    }

    @Test
    fun test12_doneEnabledDisabledForIncompleteChecklist() {
        val incompleteTask = createTaskFixture(
            "incomplete-task",
            dueDatetime = "2026-08-02T09:30:00Z",
            durationMinutes = 60, // Current hero
            checkedCount = 1,
            totalCount = 2,
            checklistItems = listOf(
                ChecklistItem("c1", "Item 1", checked = true, order = 0, childRevision = "r1"),
                ChecklistItem("c2", "Item 2", checked = false, order = 1, childRevision = "r1")
            )
        )
        val completeTask = createTaskFixture(
            "complete-task",
            dueDatetime = "2026-08-02T14:00:00Z",
            durationMinutes = 30,
            checkedCount = 2,
            totalCount = 2,
            checklistItems = listOf(
                ChecklistItem("c1", "Item 1", checked = true, order = 0, childRevision = "r1"),
                ChecklistItem("c2", "Item 2", checked = true, order = 1, childRevision = "r1")
            )
        )
        val snapshot = snapshot(listOf(incompleteTask, completeTask))

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE)

        val hero = assertNotNull(state.currentBlock)
        assertFalse(hero.doneEnabled, "Hero Done button must be disabled when checklist is incomplete")

        val futureRow = state.futureGroups.single().rows.single()
        assertTrue(futureRow.doneEnabled, "Row Done button must be enabled when checklist is complete")
    }

    @Test
    fun test13_loadingErrorRefreshAttentionActionStatusAndAllClearStates() {
        // Null snapshot, loading
        val stateLoading = buildHomeScreenState(null, refreshInFlight = true, now = FIXED_NOW, zoneId = FIXED_ZONE)
        assertEquals(HomeContentState.Loading, stateLoading.contentState)
        assertNull(stateLoading.refreshAttention)

        // Null snapshot, refresh error
        val stateError = buildHomeScreenState(null, refreshInFlight = false, refreshError = "Network error", now = FIXED_NOW, zoneId = FIXED_ZONE)
        assertEquals(HomeContentState.Error("Network error"), stateError.contentState)

        // Snapshot present, refresh error (cached refresh error)
        val snapshotWithTask = snapshot(listOf(createTaskFixture("task-1", dueDatetime = "2026-08-02T14:00:00Z", durationMinutes = 30)))
        val stateCachedError = buildHomeScreenState(snapshotWithTask, refreshError = "Network error", now = FIXED_NOW, zoneId = FIXED_ZONE)
        assertEquals(HomeContentState.Ready, stateCachedError.contentState)
        val refreshAtt = assertNotNull(stateCachedError.refreshAttention)
        assertEquals("Refresh failed. Showing last known tasks.", refreshAtt.message)
        assertEquals(HomeAttentionAction.RetryRefresh, refreshAtt.action)

        // Action status message
        val stateActionStatus = buildHomeScreenState(snapshotWithTask, actionStatusMessage = "Task updated", now = FIXED_NOW, zoneId = FIXED_ZONE)
        assertEquals("Task updated", stateActionStatus.actionStatusMessage)

        // True all clear state (empty tasks, no errors, no attention)
        val stateAllClear = buildHomeScreenState(snapshot(emptyList()), now = FIXED_NOW, zoneId = FIXED_ZONE)
        assertEquals(HomeContentState.Ready, stateAllClear.contentState)
        assertTrue(stateAllClear.allClear)
    }

    @Test
    fun test14_invalidTimestampOrDurationFailsClosedWithoutCrash() {
        val invalidTime = createTaskFixture("invalid-time", dueDatetime = "not-a-datetime", durationMinutes = 30)
        val negativeDuration = createTaskFixture("neg-dur", dueDatetime = "2026-08-02T14:00:00Z", durationMinutes = -10)
        val nullDuration = createTaskFixture("null-dur", dueDatetime = "2026-08-02T14:00:00Z", durationMinutes = null)

        val snapshot = snapshot(listOf(invalidTime, negativeDuration, nullDuration))

        val state = buildHomeScreenState(snapshot, now = FIXED_NOW, zoneId = FIXED_ZONE)

        assertNull(state.currentBlock)
        assertEquals(0, state.overdue.totalCount)
        assertTrue(state.futureGroups.isEmpty())
    }

    @Test
    fun test15_headersHonorInjectedZoneDifferentFromUtc() {
        // UTC time is 2026-08-02T23:30:00Z
        // JST time (+9) is 2026-08-03T08:30:00 JST (Monday, August 3)
        val utcNow = Instant.parse("2026-08-02T23:30:00Z")
        val tokyoZone = ZoneId.of("Asia/Tokyo")

        val snapshot = snapshot(listOf(createTaskFixture("jst-task", dueDatetime = "2026-08-03T10:00:00+09:00", durationMinutes = 30)))

        val state = buildHomeScreenState(snapshot, now = utcNow, zoneId = tokyoZone)

        assertEquals("Monday", state.weekdayLabel)
        assertEquals("August 3", state.dateLabel)
        val group = state.futureGroups.single()
        assertEquals("Today · Monday, August 3", group.heading)
    }

    private fun snapshot(tasks: List<TaskSnapshotItem>): SnapshotViewState = SnapshotViewState(
        scheduleVersion = 1,
        serverTime = "2026-08-02T10:00:00Z",
        invalidTasks = emptyList(),
        activeBlockers = emptyList(),
        inProgressTasks = emptyList(),
        startingToday = emptyList(),
        healthWarnings = emptyList(),
        tasks = tasks
    )

    private fun createTaskFixture(
        id: String,
        title: String = id,
        description: String? = null,
        dueDatetime: String? = null,
        durationMinutes: Int? = null,
        state: CommitmentState = CommitmentState.planned,
        isAssignedToUser: Boolean = true,
        assigneeId: String? = "user-1",
        isCreatedByUser: Boolean = true,
        isCompleted: Boolean = false,
        isDeleted: Boolean = false,
        hierarchyStatus: HierarchyStatus = HierarchyStatus.valid,
        reconciliationStatus: ReconciliationStatus = ReconciliationStatus.none,
        updatedAt: String = "2026-08-02T09:00:00Z",
        checkedCount: Int = 0,
        totalCount: Int = 0,
        checklistItems: List<ChecklistItem> = emptyList(),
        recurrence: TaskRecurrence = TaskRecurrence()
    ): TaskSnapshotItem = TaskSnapshotItem(
        id = id,
        todoistTaskId = "todoist-$id",
        title = title,
        description = description,
        projectId = "proj-1",
        assigneeId = assigneeId,
        creatorId = if (isCreatedByUser) "user-1" else "user-2",
        dueDatetime = dueDatetime,
        durationMinutes = durationMinutes,
        startAt = null,
        state = state,
        isAssignedToUser = isAssignedToUser,
        isCreatedByUser = isCreatedByUser,
        isCompleted = isCompleted,
        isDeleted = isDeleted,
        validationErrors = emptyList(),
        startedAt = null,
        startDismissCount = 0,
        deadlineExtensionCount = 0,
        escalationSentAt = null,
        updatedAt = updatedAt,
        occurrenceKey = "occ-$id",
        taskRevision = "rev-1",
        recurrence = recurrence,
        checklistItems = checklistItems,
        checkedCount = checkedCount,
        totalCount = totalCount,
        hierarchyStatus = hierarchyStatus,
        reconciliationStatus = reconciliationStatus
    )

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-08-02T10:00:00Z")
        val FIXED_ZONE: ZoneId = ZoneId.of("Europe/Bratislava")
    }
}
