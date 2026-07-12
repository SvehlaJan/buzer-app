package app.buzer.android.enforcement.overlay

import android.content.Intent
import app.buzer.android.enforcement.AlarmKind
import app.buzer.shared.model.CommitmentState
import app.buzer.shared.model.TaskSnapshotItem
import app.buzer.shared.ui.blockers.BlockerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverlayBlockerRequestTest {
    @Test
    fun parsesStartBlockerWithCompactSurfaceData() {
        val request = OverlayBlockerRequest.fromExtras(
            taskId = "task-1",
            kindName = "START_BLOCKER",
            title = "Pay apartment deposit",
            description = "Use the saved IBAN and attach receipt.",
            dueDatetime = "2026-07-11T10:00:00Z",
            durationMinutes = 30,
            startAt = "2026-07-11T10:00:00Z"
        )

        assertEquals(AlarmKind.START_BLOCKER, request?.kind)
        assertEquals(BlockerKind.OverlayStart, request?.blockerKind)
        assertEquals("task-1", request?.taskId)
        assertEquals("Pay apartment deposit", request?.title)
        assertEquals("Use the saved IBAN and attach receipt.", request?.description)
        assertEquals("2026-07-11T10:00:00Z", request?.dueDatetime)
        assertEquals(30, request?.durationMinutes)
        assertEquals("2026-07-11T10:00:00Z", request?.startAt)
    }

    @Test
    fun rejectsNonStartBlockers() {
        assertNull(OverlayBlockerRequest.fromExtras("task-2", "DEADLINE_BLOCKER"))
        assertNull(OverlayBlockerRequest.fromExtras(null, "MORNING_REVIEW"))
    }

    @Test
    fun rejectsTaskBlockerWithoutTaskId() {
        assertNull(OverlayBlockerRequest.fromExtras(null, "START_BLOCKER"))
    }

    @Test
    fun rejectsPreStartUnknownAndMissingKind() {
        assertNull(OverlayBlockerRequest.fromExtras("task-1", "PRE_START_WARNING"))
        assertNull(OverlayBlockerRequest.fromExtras("task-1", "BOGUS"))
        assertNull(OverlayBlockerRequest.fromExtras("task-1", null))
    }

    @Test
    fun writeToRoundTripsCompactSurfaceDataThroughIntent() {
        val request = OverlayBlockerRequest(
            kind = AlarmKind.START_BLOCKER,
            taskId = "task-1",
            title = "Pay apartment deposit",
            description = "Use the saved IBAN and attach receipt.",
            dueDatetime = "2026-07-11T10:00:00Z",
            durationMinutes = 30,
            startAt = "2026-07-11T10:00:00Z"
        )

        val parsed = OverlayBlockerRequest.fromIntent(request.writeTo(FakeIntent()))

        assertEquals(request, parsed)
    }

    @Test
    fun withTaskCopiesSnapshotData() {
        val request = OverlayBlockerRequest(
            kind = AlarmKind.START_BLOCKER,
            taskId = "task-1"
        )

        val enriched = request.withTask(
            task(
                title = "Pay apartment deposit",
                description = "Use the saved IBAN and attach receipt.",
                dueDatetime = "2026-07-11T10:00:00Z",
                durationMinutes = 30,
                startAt = "2026-07-11T10:00:00Z"
            )
        )

        assertEquals("Pay apartment deposit", enriched.title)
        assertEquals("Use the saved IBAN and attach receipt.", enriched.description)
        assertEquals("2026-07-11T10:00:00Z", enriched.dueDatetime)
        assertEquals(30, enriched.durationMinutes)
        assertEquals("2026-07-11T10:00:00Z", enriched.startAt)
    }

    @Test
    fun withTaskKeepsRequestWhenSnapshotIsNull() {
        val request = OverlayBlockerRequest(
            kind = AlarmKind.START_BLOCKER,
            taskId = "task-1",
            title = "Fallback title"
        )

        assertEquals(request, request.withTask(null))
    }

    private fun task(
        title: String,
        description: String?,
        dueDatetime: String?,
        durationMinutes: Int?,
        startAt: String?
    ): TaskSnapshotItem =
        TaskSnapshotItem(
            id = "task-1",
            todoistTaskId = "todoist-1",
            title = title,
            description = description,
            projectId = null,
            assigneeId = null,
            creatorId = null,
            dueDatetime = dueDatetime,
            durationMinutes = durationMinutes,
            startAt = startAt,
            state = CommitmentState.start_blocker_active,
            isAssignedToUser = true,
            isCreatedByUser = false,
            isCompleted = false,
            isDeleted = false,
            validationErrors = emptyList(),
            startedAt = null,
            startDismissCount = 0,
            deadlineExtensionCount = 0,
            escalationSentAt = null,
            updatedAt = "2026-07-11T09:00:00Z"
        )

    private class FakeIntent : Intent() {
        private val stringExtras = mutableMapOf<String, String>()
        private val intExtras = mutableMapOf<String, Int>()

        override fun putExtra(name: String?, value: String?): Intent {
            if (name != null && value != null) stringExtras[name] = value
            return this
        }

        override fun putExtra(name: String?, value: Int): Intent {
            if (name != null) intExtras[name] = value
            return this
        }

        override fun getStringExtra(name: String?): String? =
            stringExtras[name]

        override fun hasExtra(name: String?): Boolean =
            stringExtras.containsKey(name) || intExtras.containsKey(name)

        override fun getIntExtra(name: String?, defaultValue: Int): Int =
            intExtras[name] ?: defaultValue
    }
}
