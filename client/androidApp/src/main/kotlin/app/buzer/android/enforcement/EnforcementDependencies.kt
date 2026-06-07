package app.buzer.android.enforcement

import android.content.Context
import app.buzer.android.BuildConfig
import app.buzer.android.sync.SnapshotStore
import app.buzer.shared.diagnostics.AppLog
import app.buzer.shared.api.BuzerApiClient

object EnforcementDependencies {
    fun configError(): String? {
        if (BuildConfig.BUZER_BACKEND_BASE_URL.isBlank()) {
            return "BUZER_BACKEND_BASE_URL is missing from client/local.properties"
        }
        if (BuildConfig.BUZER_BACKEND_TOKEN.isBlank()) {
            return "BUZER_BACKEND_TOKEN is missing from client/local.properties"
        }
        return null
    }

    fun coordinator(context: Context): EnforcementCoordinator {
        val error = configError()
        require(error == null) { error.orEmpty() }
        val client = BuzerApiClient(
            baseUrl = BuildConfig.BUZER_BACKEND_BASE_URL.trimEnd('/'),
            token = BuildConfig.BUZER_BACKEND_TOKEN
        )
        return EnforcementCoordinator(
            api = BuzerEnforcementApi(client),
            snapshotStore = SnapshotStore(context),
            alarmScheduler = AndroidAlarmScheduler(context),
            errorReporter = BlockerErrorReporter(context),
            logger = AppLog::debug
        )
    }
}
