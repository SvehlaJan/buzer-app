package app.buzer.android.observability

import app.buzer.shared.diagnostics.DiagnosticCorrelation
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class UiDiagnosticContext(
    val screen: String? = null,
    val currentActivity: String? = null,
    val blockerType: String? = null,
    val appSection: String? = null,
    val inFlightOperation: String? = null
)

@Serializable
data class SyncDiagnosticContext(
    val scheduleVersion: String? = null,
    val snapshotGeneration: Long? = null,
    val lastSyncAt: String? = null,
    val lastSyncResult: String? = null,
    val cachedTaskCount: Int? = null,
    val lastSyncError: String? = null
)

@Serializable
data class SchedulingDiagnosticContext(
    val alarmsRegistered: Int? = null,
    val nextAlarmAt: String? = null,
    val morningReviewScheduledAt: String? = null,
    val lastFiredAlarm: String? = null
)

@Serializable
data class ReadinessDiagnosticContext(
    val notificationsGranted: Boolean? = null,
    val exactAlarmGranted: Boolean? = null,
    val fullScreenIntentGranted: Boolean? = null,
    val overlayGranted: Boolean? = null,
    val batteryOptimizationExempt: Boolean? = null
)

@Serializable
data class ActionsDiagnosticContext(
    val pendingActionsCount: Int? = null,
    val lastActionId: String? = null,
    val lastActionOutcome: String? = null,
    val lastActionError: String? = null
)

@Serializable
data class DeviceDiagnosticContext(
    val batteryLevel: Float? = null,
    val isCharging: Boolean? = null,
    val isInteractive: Boolean? = null,
    val isPowerSaveMode: Boolean? = null,
    val networkType: String? = null,
    val availableMemoryBytes: Long? = null,
    val lowMemory: Boolean? = null,
    val freeStorageBytes: Long? = null,
    val previousExitReason: String? = null
)

@Serializable
data class CorrelationDiagnosticContext(
    val deviceId: String? = null,
    val taskId: String? = null,
    val occurrenceKey: String? = null,
    val actionId: String? = null,
    val requestId: String? = null,
    val traceId: String? = null,
    val reportSessionId: String? = null
)

@Serializable
data class DiagnosticContextSnapshot(
    val ui: UiDiagnosticContext = UiDiagnosticContext(),
    val sync: SyncDiagnosticContext = SyncDiagnosticContext(),
    val scheduling: SchedulingDiagnosticContext = SchedulingDiagnosticContext(),
    val readiness: ReadinessDiagnosticContext = ReadinessDiagnosticContext(),
    val actions: ActionsDiagnosticContext = ActionsDiagnosticContext(),
    val device: DeviceDiagnosticContext = DeviceDiagnosticContext(),
    val correlation: CorrelationDiagnosticContext = CorrelationDiagnosticContext()
)

open class DiagnosticContextStore {
    private val uiRef = AtomicReference(UiDiagnosticContext())
    private val syncRef = AtomicReference(SyncDiagnosticContext())
    private val schedulingRef = AtomicReference(SchedulingDiagnosticContext())
    private val readinessRef = AtomicReference(ReadinessDiagnosticContext())
    private val actionsRef = AtomicReference(ActionsDiagnosticContext())
    private val deviceRef = AtomicReference(DeviceDiagnosticContext())
    private val correlationRef = AtomicReference(CorrelationDiagnosticContext())

    fun updateUi(transform: (UiDiagnosticContext) -> UiDiagnosticContext) {
        runCatching {
            uiRef.updateAndGet { current ->
                runCatching { transform(current) }.getOrDefault(current)
            }
        }
    }

    fun updateSync(transform: (SyncDiagnosticContext) -> SyncDiagnosticContext) {
        runCatching {
            syncRef.updateAndGet { current ->
                runCatching { transform(current) }.getOrDefault(current)
            }
        }
    }

    fun updateScheduling(transform: (SchedulingDiagnosticContext) -> SchedulingDiagnosticContext) {
        runCatching {
            schedulingRef.updateAndGet { current ->
                runCatching { transform(current) }.getOrDefault(current)
            }
        }
    }

    fun updateReadiness(transform: (ReadinessDiagnosticContext) -> ReadinessDiagnosticContext) {
        runCatching {
            readinessRef.updateAndGet { current ->
                runCatching { transform(current) }.getOrDefault(current)
            }
        }
    }

    fun updateActions(transform: (ActionsDiagnosticContext) -> ActionsDiagnosticContext) {
        runCatching {
            actionsRef.updateAndGet { current ->
                runCatching { transform(current) }.getOrDefault(current)
            }
        }
    }

    fun updateDevice(transform: (DeviceDiagnosticContext) -> DeviceDiagnosticContext) {
        runCatching {
            deviceRef.updateAndGet { current ->
                runCatching { transform(current) }.getOrDefault(current)
            }
        }
    }

    fun updateCorrelation(transform: (CorrelationDiagnosticContext) -> CorrelationDiagnosticContext) {
        runCatching {
            correlationRef.updateAndGet { current ->
                runCatching { transform(current) }.getOrDefault(current)
            }
        }
    }

    fun setCorrelation(correlation: DiagnosticCorrelation) {
        runCatching {
            correlationRef.updateAndGet { current ->
                current.copy(
                    deviceId = correlation.deviceId ?: current.deviceId,
                    taskId = correlation.taskId ?: current.taskId,
                    occurrenceKey = correlation.occurrenceKey ?: current.occurrenceKey,
                    actionId = correlation.actionId ?: current.actionId,
                    requestId = correlation.requestId ?: current.requestId,
                    traceId = correlation.traceId ?: current.traceId,
                    reportSessionId = correlation.reportSessionId ?: current.reportSessionId
                )
            }
            if (correlation.screen != null || correlation.blockerType != null || correlation.appSection != null) {
                uiRef.updateAndGet { current ->
                    current.copy(
                        screen = correlation.screen ?: current.screen,
                        blockerType = correlation.blockerType ?: current.blockerType,
                        appSection = correlation.appSection ?: current.appSection
                    )
                }
            }
        }
    }

    fun snapshot(): DiagnosticContextSnapshot {
        return DiagnosticContextSnapshot(
            ui = uiRef.get(),
            sync = syncRef.get(),
            scheduling = schedulingRef.get(),
            readiness = readinessRef.get(),
            actions = actionsRef.get(),
            device = deviceRef.get(),
            correlation = correlationRef.get()
        )
    }

    fun reset() {
        uiRef.set(UiDiagnosticContext())
        syncRef.set(SyncDiagnosticContext())
        schedulingRef.set(SchedulingDiagnosticContext())
        readinessRef.set(ReadinessDiagnosticContext())
        actionsRef.set(ActionsDiagnosticContext())
        deviceRef.set(DeviceDiagnosticContext())
        correlationRef.set(CorrelationDiagnosticContext())
    }

    companion object : DiagnosticContextStore() {
        val instance: DiagnosticContextStore get() = this
    }
}
