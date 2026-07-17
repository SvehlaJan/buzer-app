package app.buzer.android.enforcement

data class ScheduledAlarm(
    val taskId: String,
    val occurrenceKey: String,
    val kind: AlarmKind,
    val triggerAtEpochMillis: Long
) {
    init {
        require(taskId.isNotBlank()) { "taskId is required" }
        require(occurrenceKey.isNotBlank()) { "occurrenceKey is required" }
    }

    val identity: ScheduledAlarmIdentity = ScheduledAlarmIdentity(taskId, occurrenceKey, kind)
}

data class ScheduledAlarmIdentity(
    val taskId: String,
    val occurrenceKey: String,
    val kind: AlarmKind
) {
    init {
        require(taskId.isNotBlank()) { "taskId is required" }
        require(occurrenceKey.isNotBlank()) { "occurrenceKey is required" }
    }
}

interface AlarmScheduler {
    fun schedule(alarm: ScheduledAlarm)
    fun cancel(identity: ScheduledAlarmIdentity)
}
