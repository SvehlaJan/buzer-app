package app.buzer.android.enforcement

data class ScheduledAlarm(
    val taskId: String,
    val kind: AlarmKind,
    val triggerAtEpochMillis: Long
)

interface AlarmScheduler {
    fun schedule(alarm: ScheduledAlarm)
    fun cancel(taskId: String, kind: AlarmKind)
    fun cancelAllForTask(taskId: String)
}
