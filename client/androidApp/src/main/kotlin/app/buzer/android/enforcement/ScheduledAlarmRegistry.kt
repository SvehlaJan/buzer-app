package app.buzer.android.enforcement

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/**
 * Durable record of exact alarm identities. AlarmManager itself does not let us enumerate the
 * application's PendingIntents, so this registry is the authority used to cancel a superseded
 * recurring occurrence after reboot or a same-timestamp rollover.
 */
class ScheduledAlarmRegistry(
    private val storage: ScheduledAlarmRegistryStorage
) {
    fun reconcile(desired: List<ScheduledAlarm>, scheduler: AlarmScheduler) {
        val desiredByIdentity = desired.associateBy(ScheduledAlarm::identity)
        require(desiredByIdentity.size == desired.size) { "Duplicate scheduled alarm identity" }
        val desiredIdentities = desiredByIdentity.keys
        val existing = storage.load()

        try {
            (existing - desiredIdentities).forEach(scheduler::cancel)
            desiredByIdentity.values.forEach(scheduler::schedule)
            storage.replace(desiredIdentities)
        } catch (error: Throwable) {
            // Do not leave a desired identity armed if persistence/scheduling did not complete.
            // The previous registry is deliberately retained: its atomic replacement either did
            // not run or failed, and boot reconciliation can recover it from cached authority.
            (desiredIdentities - existing).forEach { identity -> runCatching { scheduler.cancel(identity) } }
            throw error
        }
    }

    fun cancelAllForTask(taskId: String, scheduler: AlarmScheduler) {
        val existing = storage.load()
        val toCancel = existing.filter { it.taskId == taskId }.toSet()
        toCancel.forEach(scheduler::cancel)
        storage.replace(existing - toCancel)
    }

    companion object {
        fun fromContext(context: Context): ScheduledAlarmRegistry =
            ScheduledAlarmRegistry(
                SharedPreferencesScheduledAlarmRegistryStorage(
                    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                )
            )

        private const val PREFERENCES_NAME = "scheduled_alarm_registry"
    }
}

interface ScheduledAlarmRegistryStorage {
    fun load(): Set<ScheduledAlarmIdentity>
    fun replace(identities: Set<ScheduledAlarmIdentity>)
}

class InMemoryScheduledAlarmRegistryStorage(
    private var identities: Set<ScheduledAlarmIdentity> = emptySet()
) : ScheduledAlarmRegistryStorage {
    override fun load(): Set<ScheduledAlarmIdentity> = identities

    override fun replace(identities: Set<ScheduledAlarmIdentity>) {
        this.identities = identities
    }
}

private class SharedPreferencesScheduledAlarmRegistryStorage(
    private val preferences: SharedPreferences
) : ScheduledAlarmRegistryStorage {
    override fun load(): Set<ScheduledAlarmIdentity> =
        preferences.getStringSet(IDENTITIES_KEY, emptySet())
            .orEmpty()
            .mapNotNull(::decode)
            .toSet()

    override fun replace(identities: Set<ScheduledAlarmIdentity>) {
        check(preferences.edit().putStringSet(IDENTITIES_KEY, identities.map(::encode).toSet()).commit()) {
            "Unable to persist scheduled alarms"
        }
    }

    private fun encode(identity: ScheduledAlarmIdentity): String =
        listOf(identity.taskId, identity.occurrenceKey, identity.kind.name)
            .joinToString(".") { value ->
                Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(UTF_8))
            }

    private fun decode(encoded: String): ScheduledAlarmIdentity? = runCatching {
        val values = encoded.split('.')
        require(values.size == 3)
        ScheduledAlarmIdentity(
            taskId = decodePart(values[0]),
            occurrenceKey = decodePart(values[1]),
            kind = AlarmKind.valueOf(decodePart(values[2]))
        )
    }.getOrNull()

    private fun decodePart(value: String): String =
        String(Base64.getUrlDecoder().decode(value), UTF_8)

    private companion object {
        const val IDENTITIES_KEY = "identities"
    }
}
