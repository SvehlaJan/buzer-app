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
    private data class ApiClientKey(val baseUrl: String, val bearerToken: String)

    private val apiClientLock = Any()
    private var cachedApiClientKey: ApiClientKey? = null
    private var cachedApiClient: BuzerApiClient? = null

    fun deviceIdentityStore(context: Context): DeviceIdentityStore =
        DeviceIdentityStore.fromContext(context)

    fun configError(
        baseUrl: String = BuildConfig.BUZER_BACKEND_BASE_URL,
        bearerToken: String = BuildConfig.BUZER_BACKEND_TOKEN
    ): String? {
        if (baseUrl.isBlank()) {
            return "BUZER_BACKEND_BASE_URL is missing from client/local.properties"
        }
        if (bearerToken.isBlank()) {
            return "BUZER_BACKEND_TOKEN is missing from client/local.properties"
        }
        return null
    }

    fun apiClient(
        @Suppress("UNUSED_PARAMETER") context: Context? = null,
        baseUrl: String = BuildConfig.BUZER_BACKEND_BASE_URL.trimEnd('/'),
        bearerToken: String = BuildConfig.BUZER_BACKEND_TOKEN
    ): BuzerApiClient {
        val error = configError(baseUrl, bearerToken)
        require(error == null) { error.orEmpty() }
        val key = ApiClientKey(baseUrl.trimEnd('/'), bearerToken)
        synchronized(apiClientLock) {
            val existing = cachedApiClient
            if (existing != null && cachedApiClientKey == key) {
                return existing
            }
            existing?.close()
            return BuzerApiClient(
                baseUrl = key.baseUrl,
                token = key.bearerToken
            ).also {
                cachedApiClientKey = key
                cachedApiClient = it
            }
        }
    }

    fun buzerApiClient(): BuzerApiClient = apiClient()

    internal fun resetApiClientForTests() {
        synchronized(apiClientLock) {
            cachedApiClient?.close()
            cachedApiClient = null
            cachedApiClientKey = null
        }
    }

    fun coordinator(
        context: Context,
        observability: AndroidObservability = ObservabilityRuntime.instance
    ): EnforcementCoordinator {
        return EnforcementCoordinator(
            api = apiClient(context),
            snapshotStore = SnapshotStore(context),
            alarmScheduler = AndroidAlarmScheduler(context),
            scheduledAlarmRegistry = ScheduledAlarmRegistry.fromContext(context),
            errorReporter = BlockerErrorReporter(context, observability = observability),
            blockerNotificationCleaner = AndroidBlockerNotificationCleaner(context),
            logger = AppLog::debug
        )
    }
}
