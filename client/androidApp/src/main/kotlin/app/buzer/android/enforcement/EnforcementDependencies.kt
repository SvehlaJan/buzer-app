package app.buzer.android.enforcement

import android.content.Context
import app.buzer.android.BuildConfig
import app.buzer.android.observability.AndroidObservability
import app.buzer.android.observability.ObservabilityRuntime
import app.buzer.android.sync.DeviceIdentityStore
import app.buzer.android.sync.SnapshotStore
import app.buzer.shared.api.BuzerApiClient
import app.buzer.shared.diagnostics.AppLog

object EnforcementDependencies {
    fun deviceIdentityStore(context: Context): DeviceIdentityStore =
        DeviceIdentityStore.fromContext(context)

    fun configError(): String? {
        if (BuildConfig.BUZER_BACKEND_BASE_URL.isBlank()) {
            return "BUZER_BACKEND_BASE_URL is missing from client/local.properties"
        }
        if (BuildConfig.BUZER_BACKEND_TOKEN.isBlank()) {
            return "BUZER_BACKEND_TOKEN is missing from client/local.properties"
        }
        return null
    }

    fun buzerApiClient(
        observability: AndroidObservability = ObservabilityRuntime.instance
    ): BuzerApiClient {
        val error = configError()
        require(error == null) { error.orEmpty() }
        return BuzerApiClient(
            baseUrl = BuildConfig.BUZER_BACKEND_BASE_URL.trimEnd('/'),
            token = BuildConfig.BUZER_BACKEND_TOKEN
        )
    }

    fun coordinator(
        context: Context,
        observability: AndroidObservability = ObservabilityRuntime.instance
    ): EnforcementCoordinator {
        val client = buzerApiClient(observability)
        return EnforcementCoordinator(
            api = BuzerEnforcementApi(client),
            snapshotStore = SnapshotStore(context),
            alarmScheduler = AndroidAlarmScheduler(context),
            scheduledAlarmRegistry = ScheduledAlarmRegistry.fromContext(context),
            errorReporter = BlockerErrorReporter(context, observability = observability),
            blockerNotificationCleaner = AndroidBlockerNotificationCleaner(context),
            logger = AppLog::debug,
            observability = observability
        )
    }
}

