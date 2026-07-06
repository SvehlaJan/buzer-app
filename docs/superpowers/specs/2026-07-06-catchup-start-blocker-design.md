# Catch-Up Start Blocker Design

Date: 2026-07-06
Status: approved design, pending implementation plan

## Context And Goal

Friction item 7 (`docs/notes/2026-07-05-usage-friction.md`): a task whose start is already in
the past when reconcile runs gets NO start blocker — `AlarmPlanCalculator` only schedules
START_BLOCKER when `startAt > now`. Since the picker now defaults to "now", this is the common
case: the task skips the entire start-commitment flow (never `started`, "I'm starting" never
asked, postponement escalation unreachable).

Owner decision (2026-07-06): **nag until acted** — while a committed block is in progress and
the task is still un-started, every reconcile re-demands action. No per-task dedup state.

## Behavior

In `AlarmPlanCalculator.desiredAlarmsFor` (shared, pure): for a task with
`state == CommitmentState.planned`, `startAt != null`, `durationMinutes != null`, where
`startAt <= now < startAt + duration` (block in progress), emit a catch-up
`DesiredAlarm(taskId, START_BLOCKER, triggerAtIso = nowIso)`. The existing future-start branch
is unchanged; the catch-up applies only when the future branch did not.

Resulting dynamics (all intentional, no new mechanism):

- The alarm fires immediately after each reconcile that still sees an in-progress planned
  task (app open, refresh, boot, overlay-watcher refresh). Acting ("I'm starting" →
  `started`; postpone → backend moves the block) ends the nag naturally because the planner
  condition stops matching.
- Delivery reuses the entire existing path: `setAlarmClock` → `AlarmReceiver` →
  keyguard-aware overlay + full-screen notification. Re-posting the same notification id is a
  silent update; re-starting the overlay service while its overlay is visible is a no-op
  visually.
- Tasks whose whole block has passed (deadline also in the past) get NO catch-up — they are
  Morning Review territory, out of scope here.
- `started` tasks never get a catch-up (they acted); `invalid` tasks are already filtered by
  `validationErrors`.

## Tests

- Planner unit tests (extend `AlarmPlanCalculatorTest`):
  - planned task with start in the past, deadline in the future → plan contains START_BLOCKER
    at `nowIso` (plus the existing DEADLINE_BLOCKER at due+duration).
  - same timing but `state = started` → no START_BLOCKER (deadline alarm only; this replaces
    nothing — the existing `schedulesDeadlineForInProgressBlock` test uses a planned task and
    must be updated to expect the new catch-up alarm alongside the deadline alarm).
  - planned task with BOTH start and deadline in the past → no alarms.
- Instrumentation/e2e: no changes expected — the fake fixture timestamps (2026-05-31) are
  entirely in the past relative to real device time, so no catch-up fires during the lane
  (deadline also past). Verify the lane stays 21/21.
- On-device (owner): create a task with Start = now, lock or keep using the phone; the start
  blocker must appear within seconds of the next refresh without waiting for a future alarm;
  pressing "I'm starting" stops re-prompts.

## Out Of Scope

- Fully-overdue planned tasks (block entirely past) — Morning Review handles them.
- Any UI change; any backend change (the planner is the only logic change).
- Rate-limiting the nag — explicitly rejected by the owner in favor of strict enforcement.
