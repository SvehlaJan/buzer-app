package app.buzer.android.readiness

enum class EnforcementCapability(val step: Int) {
    Notifications(1),
    ExactAlarms(2),
    FullScreenIntent(3),
    Overlay(4),
    BatteryExemption(5)
}

data class RequiredNotificationChannel(
    val id: String,
    val displayName: String,
    val minimumImportance: Int
)

sealed interface ReadinessRequirement {
    val capability: EnforcementCapability

    data object NotificationRuntime : ReadinessRequirement {
        override val capability = EnforcementCapability.Notifications
    }
    data object AppNotifications : ReadinessRequirement {
        override val capability = EnforcementCapability.Notifications
    }
    data class NotificationChannel(
        val channelId: String,
        val channelName: String
    ) : ReadinessRequirement {
        override val capability = EnforcementCapability.Notifications
    }
    data object ExactAlarms : ReadinessRequirement {
        override val capability = EnforcementCapability.ExactAlarms
    }
    data object FullScreenIntent : ReadinessRequirement {
        override val capability = EnforcementCapability.FullScreenIntent
    }
    data object Overlay : ReadinessRequirement {
        override val capability = EnforcementCapability.Overlay
    }
    data object BatteryExemption : ReadinessRequirement {
        override val capability = EnforcementCapability.BatteryExemption
    }
}

sealed interface ReadinessSettingsDestination {
    data object AppNotifications : ReadinessSettingsDestination
    data class NotificationChannel(val channelId: String) : ReadinessSettingsDestination
    data object ExactAlarms : ReadinessSettingsDestination
    data object FullScreenIntent : ReadinessSettingsDestination
    data object Overlay : ReadinessSettingsDestination
    data object BatteryExemption : ReadinessSettingsDestination
}

sealed interface EnforcementReadiness {
    data object Ready : EnforcementReadiness
    data class NeedsRuntimePermission(
        val requirement: ReadinessRequirement.NotificationRuntime
    ) : EnforcementReadiness
    data class NeedsSettingsAction(
        val requirement: ReadinessRequirement,
        val destination: ReadinessSettingsDestination
    ) : EnforcementReadiness
}

data class EnforcementCapabilitySnapshot(
    val sdkInt: Int,
    val notificationRuntimeGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val notificationChannelImportance: Map<String, Int>,
    val exactAlarmsAllowed: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val overlayAllowed: Boolean,
    val batteryOptimizationExempt: Boolean
)

object EnforcementReadinessPolicy {
    fun evaluate(
        snapshot: EnforcementCapabilitySnapshot,
        requiredChannels: List<RequiredNotificationChannel>
    ): EnforcementReadiness {
        if (snapshot.sdkInt >= 33 && !snapshot.notificationRuntimeGranted) {
            return EnforcementReadiness.NeedsRuntimePermission(
                ReadinessRequirement.NotificationRuntime
            )
        }
        if (!snapshot.appNotificationsEnabled) {
            return EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.AppNotifications,
                ReadinessSettingsDestination.AppNotifications
            )
        }
        requiredChannels.firstOrNull { channel ->
            (snapshot.notificationChannelImportance[channel.id] ?: 0) < channel.minimumImportance
        }?.let { channel ->
            return EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.NotificationChannel(channel.id, channel.displayName),
                ReadinessSettingsDestination.NotificationChannel(channel.id)
            )
        }
        if (snapshot.sdkInt >= 31 && !snapshot.exactAlarmsAllowed) {
            return EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.ExactAlarms,
                ReadinessSettingsDestination.ExactAlarms
            )
        }
        if (snapshot.sdkInt >= 34 && !snapshot.fullScreenIntentAllowed) {
            return EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.FullScreenIntent,
                ReadinessSettingsDestination.FullScreenIntent
            )
        }
        if (!snapshot.overlayAllowed) {
            return EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.Overlay,
                ReadinessSettingsDestination.Overlay
            )
        }
        if (!snapshot.batteryOptimizationExempt) {
            return EnforcementReadiness.NeedsSettingsAction(
                ReadinessRequirement.BatteryExemption,
                ReadinessSettingsDestination.BatteryExemption
            )
        }
        return EnforcementReadiness.Ready
    }
}
