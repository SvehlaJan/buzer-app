# Decision: Todoist-Native Due Semantics And Alarm-Clock Delivery

Date: 2026-07-05
Status: accepted (owner decision during go-live dogfooding)
Amends: docs/superpowers/specs/2026-04-30-todoist-commitment-companion-design.md

## Semantics

`due.datetime` is the START of a committed block, matching Todoist's native
interpretation. The enforcement deadline is `due + durationMinutes`. Previously Buzer
treated due as the end (`startAt = due - duration`), which made Todoist's calendar render
Buzer tasks after their "deadline" and would have enforced Todoist-created tasks a full
duration early.

Consequences:
- Backend `startAt` equals `dueDatetime`; the snapshot contract shape is unchanged.
- The shared alarm planner schedules START_BLOCKER at due, DEADLINE_BLOCKER at
  due + duration, PRE_START_WARNING at due - 1h.
- Android pickers mean "Start at" and default to now; screens show the computed end.
- No data migration: stored values keep their meaning at the Todoist boundary; existing
  planned tasks are enforced one duration later than under the old interpretation
  (one-time, accepted).

## Alarm delivery

START_BLOCKER and DEADLINE_BLOCKER alarms use `AlarmManager.setAlarmClock()` because
allow-while-idle exact alarms are doze-deferred minutes on a locked device (observed
2026-07-05: start alarm 9+ minutes late). The status-bar alarm icon is accepted.
PRE_START_WARNING and MORNING_REVIEW stay on `setExactAndAllowWhileIdle`.
