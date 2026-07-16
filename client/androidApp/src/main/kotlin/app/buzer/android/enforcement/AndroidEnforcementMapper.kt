package app.buzer.android.enforcement

import app.buzer.shared.enforcement.DesiredAlarm
import app.buzer.shared.enforcement.DesiredAlarmKind
import java.time.Instant

object AndroidEnforcementMapper {
    fun toScheduledAlarm(alarm: DesiredAlarm): ScheduledAlarm =
        ScheduledAlarm(
            taskId = alarm.taskId,
            occurrenceKey = alarm.occurrenceKey,
            kind = toAlarmKind(alarm.kind),
            triggerAtEpochMillis = Instant.parse(alarm.triggerAtIso).toEpochMilli()
        )

    fun toAlarmKind(kind: DesiredAlarmKind): AlarmKind =
        when (kind) {
            DesiredAlarmKind.PRE_START_WARNING -> AlarmKind.PRE_START_WARNING
            DesiredAlarmKind.START_BLOCKER -> AlarmKind.START_BLOCKER
            DesiredAlarmKind.DEADLINE_BLOCKER -> AlarmKind.DEADLINE_BLOCKER
        }
}
