package app.buzer.shared.scheduling

import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.HierarchyStatus
import app.buzer.shared.model.TaskSnapshotItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CommitmentEligibilityTest {
    @Test
    fun terminalCheckCoversFlagsAndTerminalStates() {
        assertFalse(CommitmentEligibility.isTerminalCommitment(task("active")))
        assertTrue(CommitmentEligibility.isTerminalCommitment(task("completed-flag", isCompleted = true)))
        assertTrue(CommitmentEligibility.isTerminalCommitment(task("deleted-flag", isDeleted = true)))
        assertTrue(CommitmentEligibility.isTerminalCommitment(task("completed-state", state = CommitmentState.completed)))
        assertTrue(CommitmentEligibility.isTerminalCommitment(task("deleted-state", state = CommitmentState.deleted)))
    }

    @Test
    fun ownerBlockingCheckIncludesInvalidUnsupportedAndValidationErrorsWhileExcludingMalformedPlanned() {
        assertTrue(CommitmentEligibility.isOwnerBlockingTask(task("invalid-state", state = CommitmentState.invalid)))
        assertTrue(CommitmentEligibility.isOwnerBlockingTask(task("unsupported", hierarchyStatus = HierarchyStatus.unsupported)))
        assertTrue(CommitmentEligibility.isOwnerBlockingTask(task("validation-errors", validationErrors = listOf("missing_duration"))))

        // Malformed planned tasks without validationErrors/unsupported must not enter blocking.
        assertFalse(CommitmentEligibility.isOwnerBlockingTask(task("bad-time", dueDatetime = "not-a-time", startAt = null)))
        assertFalse(CommitmentEligibility.isOwnerBlockingTask(task("bad-duration", durationMinutes = 0)))

        // Non-owner and terminal tasks never block the owner.
        assertFalse(
            CommitmentEligibility.isOwnerBlockingTask(
                task("other-user", isAssignedToUser = false, state = CommitmentState.invalid)
            )
        )
        assertFalse(
            CommitmentEligibility.isOwnerBlockingTask(
                task("completed-invalid", isCompleted = true, state = CommitmentState.invalid)
            )
        )
    }

    @Test
    fun enforceableOwnerCheckRequiresActiveOwnerValidHierarchyAndNoValidationErrors() {
        assertTrue(CommitmentEligibility.isEnforceableOwnerCommitment(task("valid")))
        assertFalse(CommitmentEligibility.isEnforceableOwnerCommitment(task("unassigned", isAssignedToUser = false)))
        assertFalse(CommitmentEligibility.isEnforceableOwnerCommitment(task("invalid", state = CommitmentState.invalid)))
        assertFalse(CommitmentEligibility.isEnforceableOwnerCommitment(task("unsupported", hierarchyStatus = HierarchyStatus.unsupported)))
        assertFalse(CommitmentEligibility.isEnforceableOwnerCommitment(task("errors", validationErrors = listOf("missing_duration"))))
        assertFalse(CommitmentEligibility.isEnforceableOwnerCommitment(task("completed", isCompleted = true)))
    }

    @Test
    fun timedWindowExtractsStartAndEndOrReturnsNullWhenMalformedOrUnenforceable() {
        val fromStartAt = CommitmentEligibility.timedWindowOrNull(
            task("start-at", startAt = "2026-08-03T07:00:00Z", dueDatetime = "2026-08-03T07:00:00Z", durationMinutes = 30)
        )
        assertEquals(
            CommitmentWindow(
                start = Instant.parse("2026-08-03T07:00:00Z"),
                end = Instant.parse("2026-08-03T07:30:00Z")
            ),
            fromStartAt
        )

        val fallbackDue = CommitmentEligibility.timedWindowOrNull(
            task("due-fallback", startAt = null, dueDatetime = "2026-08-03T09:00:00Z", durationMinutes = 45)
        )
        assertEquals(
            CommitmentWindow(
                start = Instant.parse("2026-08-03T09:00:00Z"),
                end = Instant.parse("2026-08-03T09:45:00Z")
            ),
            fallbackDue
        )

        assertNull(CommitmentEligibility.timedWindowOrNull(task("null-duration", durationMinutes = null)))
        assertNull(CommitmentEligibility.timedWindowOrNull(task("zero-duration", durationMinutes = 0)))
        assertNull(CommitmentEligibility.timedWindowOrNull(task("offsetless", startAt = "2026-08-03T07:00:00", dueDatetime = "2026-08-03T07:00:00")))
        assertNull(CommitmentEligibility.timedWindowOrNull(task("unsupported", hierarchyStatus = HierarchyStatus.unsupported)))
        val maxInstantIso = "+1000000000-12-31T23:59:59.999999999Z"
        assertNull(CommitmentEligibility.timedWindowOrNull(task("overflow", startAt = maxInstantIso, dueDatetime = maxInstantIso, durationMinutes = 1)))
    }

    @Test
    fun hasActiveStartPriorityAtHonorsHalfOpenIntervalUnstartedStatesAndNeverDefersForOverdue() {
        val start = "2026-08-03T07:00:00Z"
        val atStart = Instant.parse("2026-08-03T07:00:00Z")
        val midWindow = Instant.parse("2026-08-03T08:00:00Z")
        val atEnd = Instant.parse("2026-08-03T09:00:00Z")
        val afterEnd = Instant.parse("2026-08-03T09:30:00Z")

        for (unstartedState in listOf(
            CommitmentState.planned,
            CommitmentState.pre_start_warning,
            CommitmentState.start_blocker_active
        )) {
            val candidate = listOf(task("unstarted-${unstartedState.name}", state = unstartedState, startAt = start, dueDatetime = start, durationMinutes = 120))
            assertTrue(CommitmentEligibility.hasActiveStartPriorityAt(candidate, atStart))
            assertTrue(CommitmentEligibility.hasActiveStartPriorityAt(candidate, midWindow))
            assertFalse(CommitmentEligibility.hasActiveStartPriorityAt(candidate, atEnd))
            assertFalse(CommitmentEligibility.hasActiveStartPriorityAt(candidate, afterEnd))
        }

        // Started tasks never produce start priority.
        val startedByState = listOf(task("started-state", state = CommitmentState.started, startAt = start, dueDatetime = start, durationMinutes = 120))
        val startedByTimestamp = listOf(task("started-ts", state = CommitmentState.planned, startedAt = "2026-08-03T07:05:00Z", startAt = start, dueDatetime = start, durationMinutes = 120))
        assertFalse(CommitmentEligibility.hasActiveStartPriorityAt(startedByState, midWindow))
        assertFalse(CommitmentEligibility.hasActiveStartPriorityAt(startedByTimestamp, midWindow))
    }

    private fun task(
        id: String,
        state: CommitmentState = CommitmentState.planned,
        dueDatetime: String? = "2026-08-03T07:00:00Z",
        durationMinutes: Int? = 60,
        startAt: String? = "2026-08-03T07:00:00Z",
        isAssignedToUser: Boolean = true,
        isCompleted: Boolean = false,
        isDeleted: Boolean = false,
        validationErrors: List<String> = emptyList(),
        startedAt: String? = null,
        hierarchyStatus: HierarchyStatus = HierarchyStatus.valid
    ): TaskSnapshotItem = TaskSnapshotItem(
        id = id,
        todoistTaskId = "todoist-$id",
        title = "Task $id",
        projectId = "project",
        assigneeId = if (isAssignedToUser) "user-1" else null,
        creatorId = "user-1",
        dueDatetime = dueDatetime,
        durationMinutes = durationMinutes,
        startAt = startAt,
        state = state,
        isAssignedToUser = isAssignedToUser,
        isCreatedByUser = true,
        isCompleted = isCompleted,
        isDeleted = isDeleted,
        validationErrors = validationErrors,
        startedAt = startedAt,
        startDismissCount = 0,
        deadlineExtensionCount = 0,
        escalationSentAt = null,
        updatedAt = "2026-08-03T06:00:00Z",
        occurrenceKey = "occurrence-$id",
        hierarchyStatus = hierarchyStatus
    )
}
