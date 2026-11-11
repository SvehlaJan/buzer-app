package app.buzer.android.morningreview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buzer.android.ui.agenda.selectOwnerAgenda
import app.buzer.shared.state.SnapshotViewState
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class MorningReviewCoordinator internal constructor(
    private val stateStore: MorningReviewLocalStateStore,
    private val scheduler: MorningReviewScheduler,
    private val calculator: MorningReviewScheduleCalculator = MorningReviewScheduleCalculator(),
    private val entryController: MorningReviewEntryController = MorningReviewEntryController(),
    private val clockProvider: () -> MorningReviewRuntimeClock.Snapshot = MorningReviewRuntimeClock::snapshot
) {
    var availability by mutableStateOf<MorningReviewAvailability?>(null)
        private set

    private var reevaluationJob: Job? = null

    fun blockingCount(snapshot: SnapshotViewState?): Int {
        val currentSnapshot = snapshot ?: return 0
        val clock = clockProvider()
        return selectOwnerAgenda(currentSnapshot, clock.instant, clock.zoneId).blocking.size
    }

    fun onResume() {
        entryController.onResume()
    }

    fun onPause() {
        entryController.onPause()
        cancelReevaluation()
    }

    fun onActivityResult(): Boolean = entryController.onResult()

    fun openFromHome(isReady: Boolean = true): Boolean {
        if (!isReady) {
            cancelReevaluation()
            return false
        }
        return entryController.openHome()
    }

    fun cancelReevaluation() {
        reevaluationJob?.cancel()
        reevaluationJob = null
    }

    fun refreshAvailability(
        snapshot: SnapshotViewState?,
        scope: CoroutineScope,
        isReady: Boolean,
        onLaunchAutomated: () -> Unit
    ) {
        if (!isReady) {
            cancelReevaluation()
            return
        }
        val clock = clockProvider()
        val now = clock.instant
        val zoneId = clock.zoneId
        scheduler.reconcile(now, zoneId)
        val ownerBlockingCount = snapshot
            ?.let { selectOwnerAgenda(it, now, zoneId).blocking.size }
            ?: 0
        val nextAvailability = calculator.availability(
            state = stateStore.read(),
            ownerBlockingCount = ownerBlockingCount,
            now = now,
            zoneId = zoneId
        )
        availability = nextAvailability
        val decision = entryController.evaluate(nextAvailability)
        armReevaluation(
            availability = nextAvailability,
            snapshot = snapshot,
            scope = scope,
            onLaunchAutomated = onLaunchAutomated
        )
        if (decision.launchAutomated) {
            onLaunchAutomated()
        }
    }

    private fun armReevaluation(
        availability: MorningReviewAvailability,
        snapshot: SnapshotViewState?,
        scope: CoroutineScope,
        onLaunchAutomated: () -> Unit
    ) {
        cancelReevaluation()
        if (!entryController.isForeground) return
        val delayMillis = Duration.between(clockProvider().instant, availability.nextReevaluationAt).toMillis()
        reevaluationJob = scope.launch {
            when (val timing = MorningReviewBoundaryTiming.from(delayMillis)) {
                is MorningReviewBoundaryTiming.Delay -> delay(timing.milliseconds)
                MorningReviewBoundaryTiming.ReevaluateImmediately -> yield()
            }
            if (!entryController.isForeground) return@launch
            refreshAvailability(
                snapshot = snapshot,
                scope = scope,
                isReady = true,
                onLaunchAutomated = onLaunchAutomated
            )
        }
    }
}
