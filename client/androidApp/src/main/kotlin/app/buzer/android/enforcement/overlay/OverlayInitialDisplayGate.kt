package app.buzer.android.enforcement.overlay

import app.buzer.android.blockers.BlockerTaskSnapshotGate
import app.buzer.shared.model.TasksSnapshotResponse

/** Pure initial-display policy: fresh authority always wins over cached/offline fallback. */
internal object OverlayInitialDisplayGate {
    fun decide(
        request: OverlayBlockerRequest,
        freshSnapshot: TasksSnapshotResponse?,
        cachedSnapshot: TasksSnapshotResponse?
    ): OverlayInitialDisplayDecision {
        if (freshSnapshot != null) {
            return if (BlockerTaskSnapshotGate.isBlockerResolved(
                    freshSnapshot,
                    request.taskId,
                    request.occurrenceKey,
                    request.kind
                )) {
                OverlayInitialDisplayDecision.DismissStaleOrResolved
            } else {
                OverlayInitialDisplayDecision.Show(
                    task = freshSnapshot.tasks.firstOrNull { it.id == request.taskId },
                    isOfflineFallback = false
                )
            }
        }

        return if (
            cachedSnapshot != null &&
            BlockerTaskSnapshotGate.isBlockerResolved(
                cachedSnapshot,
                request.taskId,
                request.occurrenceKey,
                request.kind
            )
        ) {
            OverlayInitialDisplayDecision.DismissStaleOrResolved
        } else {
            OverlayInitialDisplayDecision.Show(
                task = cachedSnapshot?.tasks?.firstOrNull { it.id == request.taskId },
                isOfflineFallback = true
            )
        }
    }
}

internal sealed interface OverlayInitialDisplayDecision {
    data object DismissStaleOrResolved : OverlayInitialDisplayDecision
    data class Show(
        val task: app.buzer.shared.model.TaskSnapshotItem?,
        val isOfflineFallback: Boolean
    ) : OverlayInitialDisplayDecision
}
