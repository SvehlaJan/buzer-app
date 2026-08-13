package app.buzer.android.readiness

import android.content.Context

sealed interface ForegroundReadinessGateState {
    data object Ready : ForegroundReadinessGateState
    data class Blocking(
        val readiness: EnforcementReadiness,
        val actionInFlight: Boolean = false
    ) : ForegroundReadinessGateState
    data class Error(val message: String) : ForegroundReadinessGateState
}

sealed interface ForegroundReadinessAction {
    data object RequestNotificationPermission : ForegroundReadinessAction
    data class OpenSettings(
        val destination: ReadinessSettingsDestination
    ) : ForegroundReadinessAction
    data object Retry : ForegroundReadinessAction
}

interface NotificationPermissionRequestHistory {
    fun wasRequested(): Boolean
    fun markRequested()
}

class SharedPreferencesNotificationPermissionRequestHistory(
    context: Context
) : NotificationPermissionRequestHistory {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun wasRequested(): Boolean =
        preferences.getBoolean(KEY_NOTIFICATION_REQUESTED, false)

    override fun markRequested() {
        check(preferences.edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).commit()) {
            "Could not persist notification permission request history"
        }
    }

    companion object {
        const val PREFERENCES_NAME = "buzer_enforcement_readiness"
        const val KEY_NOTIFICATION_REQUESTED = "notification_permission_requested"
    }
}

class InMemoryNotificationPermissionRequestHistory(
    private var requested: Boolean = false
) : NotificationPermissionRequestHistory {
    override fun wasRequested(): Boolean = requested
    override fun markRequested() { requested = true }
}

class ForegroundReadinessController(
    private val checker: EnforcementReadinessChecker,
    private val requestHistory: NotificationPermissionRequestHistory,
    private val reportError: (String, Exception) -> Unit
) {
    var state: ForegroundReadinessGateState = ForegroundReadinessGateState.Error(
        "Device readiness has not been checked"
    )
        private set

    fun refresh(): ForegroundReadinessGateState {
        state = try {
            when (val readiness = checker.check()) {
                EnforcementReadiness.Ready -> ForegroundReadinessGateState.Ready
                is EnforcementReadiness.NeedsRuntimePermission -> {
                    if (requestHistory.wasRequested()) {
                        ForegroundReadinessGateState.Blocking(
                            EnforcementReadiness.NeedsSettingsAction(
                                ReadinessRequirement.AppNotifications,
                                ReadinessSettingsDestination.AppNotifications
                            )
                        )
                    } else {
                        ForegroundReadinessGateState.Blocking(readiness)
                    }
                }
                is EnforcementReadiness.NeedsSettingsAction ->
                    ForegroundReadinessGateState.Blocking(readiness)
            }
        } catch (error: Exception) {
            reportError("readiness_check_failed", error)
            ForegroundReadinessGateState.Error("Buzer couldn't verify device readiness.")
        }
        return state
    }

    fun beginPrimaryAction(): ForegroundReadinessAction? {
        val current = state
        val action = when (current) {
            ForegroundReadinessGateState.Ready -> null
            is ForegroundReadinessGateState.Error -> ForegroundReadinessAction.Retry
            is ForegroundReadinessGateState.Blocking -> when (val readiness = current.readiness) {
                EnforcementReadiness.Ready -> null
                is EnforcementReadiness.NeedsRuntimePermission -> {
                    requestHistory.markRequested()
                    ForegroundReadinessAction.RequestNotificationPermission
                }
                is EnforcementReadiness.NeedsSettingsAction ->
                    ForegroundReadinessAction.OpenSettings(readiness.destination)
            }
        }
        if (action != null && current is ForegroundReadinessGateState.Blocking) {
            state = current.copy(actionInFlight = true)
        }
        return action
    }

    fun onExternalResult(): ForegroundReadinessGateState = refresh()

    fun onLaunchFailure(error: Exception): ForegroundReadinessGateState {
        reportError("readiness_action_launch_failed", error)
        state = ForegroundReadinessGateState.Error("Buzer couldn't open the required system control.")
        return state
    }
}
