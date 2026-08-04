package app.buzer.android.morningreview

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDate

enum class MorningReviewAlarmReason { Daily, Snooze, PriorityRetry }

data class MorningReviewScheduledAlarm(
    val reviewDate: LocalDate,
    val reason: MorningReviewAlarmReason,
    val triggerAt: Instant,
    val occurrenceKey: String
)

data class MorningReviewLocalState(
    val lastAcknowledgedDate: LocalDate? = null,
    val pendingSnoozeDate: LocalDate? = null,
    val pendingSnoozeAt: Instant? = null,
    val scheduledAlarm: MorningReviewScheduledAlarm? = null
)

/** Raw, whole-state persistence boundary for Morning Review's dedicated preferences file. */
interface MorningReviewStateStorage {
    fun read(): Map<String, String?>
    fun replace(values: Map<String, String?>)
}

class MorningReviewLocalStateStore(
    private val storage: MorningReviewStateStorage
) {
    fun read(): MorningReviewLocalState {
        val values = storage.read()
        val scheduledReviewDate = values[KEY_SCHEDULED_REVIEW_DATE].parseDate()
        val scheduledReason = values[KEY_SCHEDULED_REASON].parseAlarmReason()
        val scheduledTriggerAt = values[KEY_SCHEDULED_TRIGGER_AT].parseInstant()
        val scheduledOccurrenceKey = values[KEY_SCHEDULED_OCCURRENCE_KEY]?.takeIf(String::isNotBlank)

        return MorningReviewLocalState(
            lastAcknowledgedDate = values[KEY_LAST_ACKNOWLEDGED_DATE].parseDate(),
            pendingSnoozeDate = values[KEY_PENDING_SNOOZE_DATE].parseDate(),
            pendingSnoozeAt = values[KEY_PENDING_SNOOZE_AT].parseInstant(),
            scheduledAlarm = if (
                scheduledReviewDate != null &&
                scheduledReason != null &&
                scheduledTriggerAt != null &&
                scheduledOccurrenceKey != null
            ) {
                MorningReviewScheduledAlarm(
                    reviewDate = scheduledReviewDate,
                    reason = scheduledReason,
                    triggerAt = scheduledTriggerAt,
                    occurrenceKey = scheduledOccurrenceKey
                )
            } else {
                null
            }
        )
    }

    fun replace(state: MorningReviewLocalState) {
        val scheduledAlarm = state.scheduledAlarm
        storage.replace(
            mapOf(
                KEY_LAST_ACKNOWLEDGED_DATE to state.lastAcknowledgedDate?.toString(),
                KEY_PENDING_SNOOZE_DATE to state.pendingSnoozeDate?.toString(),
                KEY_PENDING_SNOOZE_AT to state.pendingSnoozeAt?.toString(),
                KEY_SCHEDULED_REVIEW_DATE to scheduledAlarm?.reviewDate?.toString(),
                KEY_SCHEDULED_REASON to scheduledAlarm?.reason?.name,
                KEY_SCHEDULED_TRIGGER_AT to scheduledAlarm?.triggerAt?.toString(),
                KEY_SCHEDULED_OCCURRENCE_KEY to scheduledAlarm?.occurrenceKey
            )
        )
    }

    companion object {
        fun fromContext(context: Context): MorningReviewLocalStateStore =
            MorningReviewLocalStateStore(
                SharedPreferencesMorningReviewStateStorage(
                    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                )
            )

        private const val PREFERENCES_NAME = "morning_review_local_state"
    }
}

class InMemoryMorningReviewStateStorage(
    initialValues: Map<String, String?> = emptyMap(),
    private val commitSucceeds: () -> Boolean = { true }
) : MorningReviewStateStorage {
    private var values = initialValues.toMap()

    override fun read(): Map<String, String?> = values.toMap()

    override fun replace(values: Map<String, String?>) {
        check(commitSucceeds()) { "Unable to persist morning review state" }
        this.values = values.toMap()
    }
}

private class SharedPreferencesMorningReviewStateStorage(
    private val preferences: SharedPreferences
) : MorningReviewStateStorage {
    override fun read(): Map<String, String?> {
        val values = preferences.all
        return KEYS.associateWith { key -> morningReviewRawStringOrNull(values[key]) }
    }

    override fun replace(values: Map<String, String?>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            if (value != null) editor.putString(key, value)
        }
        check(editor.commit()) { "Unable to persist morning review state" }
    }
}

internal fun morningReviewRawStringOrNull(value: Any?): String? = value as? String

private fun String?.parseDate(): LocalDate? = runCatching {
    this?.let(LocalDate::parse)
}.getOrNull()

private fun String?.parseInstant(): Instant? = runCatching {
    this?.let(Instant::parse)
}.getOrNull()

private fun String?.parseAlarmReason(): MorningReviewAlarmReason? = runCatching {
    this?.let(MorningReviewAlarmReason::valueOf)
}.getOrNull()

private const val KEY_LAST_ACKNOWLEDGED_DATE = "last_acknowledged_date"
private const val KEY_PENDING_SNOOZE_DATE = "pending_snooze_date"
private const val KEY_PENDING_SNOOZE_AT = "pending_snooze_at"
private const val KEY_SCHEDULED_REVIEW_DATE = "scheduled_review_date"
private const val KEY_SCHEDULED_REASON = "scheduled_reason"
private const val KEY_SCHEDULED_TRIGGER_AT = "scheduled_trigger_at"
private const val KEY_SCHEDULED_OCCURRENCE_KEY = "scheduled_occurrence_key"

private val KEYS = setOf(
    KEY_LAST_ACKNOWLEDGED_DATE,
    KEY_PENDING_SNOOZE_DATE,
    KEY_PENDING_SNOOZE_AT,
    KEY_SCHEDULED_REVIEW_DATE,
    KEY_SCHEDULED_REASON,
    KEY_SCHEDULED_TRIGGER_AT,
    KEY_SCHEDULED_OCCURRENCE_KEY
)
