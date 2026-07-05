# Alarm Reliability And Todoist-Native Semantics Design

Date: 2026-07-05
Status: approved design, pending implementation plan

## Context And Goal

The 2026-07-05 go-live smoke (see `docs/notes/2026-07-05-usage-friction.md`, items 1 and 4-6)
found two product-critical defects and one semantic mismatch:

1. Exact allow-while-idle alarms are doze-deferred minutes on a locked Pixel (start alarm 9+
   minutes late) despite `SCHEDULE_EXACT_ALARM` and the battery whitelist. The blocker arriving
   on time IS the product.
2. The overlay surface reports "shown" while invisible behind the keyguard, duplicating the
   full-screen-intent activity that actually renders on the lock screen; its watcher then polls
   every ~2-3s indefinitely.
3. Buzer interprets `dueDatetime` as the end of a task (`startAt = due - duration`) while
   Todoist treats `due.datetime` as the START of the block — so Todoist's calendar shows
   Buzer tasks ending after their "deadline", and Todoist-created tasks would be enforced a
   full duration early.

Owner decisions (2026-07-05): fix both in ONE combined change to the scheduling layer;
adopt Todoist-native semantics; the picker becomes "Start at" and defaults to now.

## Semantics Change (product-spec amendment — requires a decision doc)

New interpretation, applied uniformly to tasks created anywhere:

- `due.datetime` = **start of the committed block**. `startAt = dueDatetime` (backend
  `computeStartAt` becomes the identity on due; keep emitting `startAt` in the snapshot so the
  client contract shape is unchanged).
- **Enforcement deadline = due + duration.** A new backend/domain helper computes it; the
  shared alarm planner schedules DEADLINE_BLOCKER at `due + duration` and START_BLOCKER at
  `due`. PRE_START_WARNING stays at `startAt - 1h`.
- The implementation plan MUST sweep every consumer of `dueDatetime`/`startAt` for end-of-block
  assumptions (backend services incl. escalation/overdue logic, shared planner, Android UI
  formatting, fake-backend fixtures) and list each impacted test suite per the AGENTS.md
  test-migration rule.
- Migration: none needed — stored fields keep their values; only interpretation shifts.
  Existing planned tasks get enforced one duration later than before (one-time, more lenient;
  record in the decision doc).
- Decision doc: `docs/decisions/2026-07-05-todoist-native-due-semantics.md` amending the
  2026-04-30 product spec (semantics + the alarm-clock API decision below).

UI consequences (Add Task, deadline blocker reschedule, Morning Review repair):

- Field label/semantics: "Start at" (contentDescriptions and validator messages updated, e.g.
  "Start time is required").
- `WheelDateTimeField` default becomes **now** (friction item 1) instead of now+1h.
- Add Task shows the computed end as plain text under the fields ("Ends HH:MM", derived from
  start + duration) so the commitment is explicit.
- Declared rider (friction item 2): the Title field gains
  `KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)`.

## Alarm Delivery Change

- START_BLOCKER and DEADLINE_BLOCKER alarms are scheduled via **`AlarmManager.setAlarmClock()`**
  (doze-exempt, prompt delivery). The required show-intent PendingIntent opens `MainActivity`.
  The status-bar alarm icon is an accepted, desirable tradeoff.
- PRE_START_WARNING and MORNING_REVIEW stay on the current `setExactAndAllowWhileIdle` path
  (minutes of drift are harmless there, and it avoids a permanent overnight alarm icon).

## Overlay Changes

- `AlarmReceiver.openBlocker`: when `KeyguardManager.isKeyguardLocked`, skip the overlay
  attempt entirely and log `overlay_start skipped reason=keyguard_locked`; the full-screen
  notification (the surface that actually works on the lock screen) is posted as today.
- Overlay watcher: poll every 2s for the first 30s, then every 10s; hard lifetime cap of
  15 minutes, after which the watcher dismisses the overlay and logs
  `overlay watcher expired` (the FSI notification remains). Log once per state change, not
  per poll.

## Verification

- Backend: unit tests for the new start/deadline helpers; full `npm test`/typecheck/build.
- Client: JVM suites; fake-backend fixtures updated to the new semantics
  (`startAt = dueDatetime`, deadline-actionability windows shifted) and the full
  `scripts/e2e-fake-backend.sh` lane green (per AGENTS.md).
- On-device (owner's Pixel 10, phone locked): a task starting +3 minutes fires the start
  blocker within ~30s of the scheduled time; logcat shows `overlay_start skipped
  reason=keyguard_locked`; the FSI blocker renders over the keyguard; the status-bar alarm
  icon appears while a blocker alarm is pending.
- Todoist calendar: a Buzer-created task now renders as a block from the chosen start to
  start + duration (verified visually by the owner).

## Out Of Scope

- Home task list (next in queue) and the refresh-indicator reflow (friction item 3 — folds
  into the task-list design).
- UI overhaul, health surface, reliability scenario lane (shelved; its spec predates this
  change — when revived, its seeded fixtures must use the new semantics).
