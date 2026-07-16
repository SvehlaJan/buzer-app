package app.buzer.android.sync

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** Persists the installation identity; action IDs intentionally stay caller-owned and ephemeral. */
class DeviceIdentityStore(
    private val storage: DeviceIdentityStorage,
    private val uuidFactory: () -> String = { UUID.randomUUID().toString() }
) {
    fun deviceId(): String {
        val existing = storage.readDeviceId()?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        return uuidFactory().also(storage::writeDeviceId)
    }

    /** Each invocation represents one newly initiated user action. Retriers retain their own ID. */
    fun newActionId(): String = uuidFactory()

    companion object {
        fun fromContext(context: Context): DeviceIdentityStore =
            DeviceIdentityStore(
                SharedPreferencesDeviceIdentityStorage(
                    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                )
            )

        private const val PREFERENCES_NAME = "device_identity"
    }
}

interface DeviceIdentityStorage {
    fun readDeviceId(): String?
    fun writeDeviceId(deviceId: String)
}

private class SharedPreferencesDeviceIdentityStorage(
    private val preferences: SharedPreferences
) : DeviceIdentityStorage {
    override fun readDeviceId(): String? = preferences.getString(DEVICE_ID_KEY, null)

    override fun writeDeviceId(deviceId: String) {
        check(preferences.edit().putString(DEVICE_ID_KEY, deviceId).commit()) {
            "Unable to persist device identity"
        }
    }

    private companion object {
        const val DEVICE_ID_KEY = "device_id"
    }
}
