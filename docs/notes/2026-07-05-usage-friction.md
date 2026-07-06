# Usage Friction Log

Running log of daily-use friction, per the go-live dogfooding loop (2026-07-05). Each entry:
observed date, friction, proposed fix, status. Fix the top open item each maintenance session.

## Open

### 1. [FIXED 2026-07-06] Deadline picker defaults to now+1h; should default to now

- Observed: 2026-07-05, first go-live session.
- `WheelDateTimeField.startDateTimeFor()` defaults the wheel to `now.plusHours(1)`.
- Fix as part of the Todoist-native due-semantics change (see backlog): under the new
  semantics the field means "Start at", where defaulting to the current time is correct.
  Under the old deadline semantics a "now" default would create an instantly-overdue task —
  do not fix this independently of the semantics change.

### 2. [FIXED 2026-07-06] Title input should capitalize sentences

- Observed: 2026-07-05.
- Add Task title `OutlinedTextField` lacks
  `KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)`.
- Small independent fix; batch with item 3.

### 3. Home refresh indicator reflows the layout

- Observed: 2026-07-05.
- The refresh indicator participates in Home's vertical Column, so content below it jumps
  when the indicator leaves composition.
- Owner preference: show it as the last item, or in a non-reflowing way (e.g. a fixed-height
  slot or a thin progress line that doesn't shift content). Small independent fix; batch
  with item 2. If the Home task list lands first, fold this into that design instead.

### 4. [FIXED 2026-07-06] CRITICAL: exact alarms doze-deferred on a locked device

- Observed: 2026-07-05 live smoke (Pixel 10, locked, stationary). Start alarm scheduled 20:57
  was still undelivered at 21:06+; a deadline alarm scheduled 21:02 delivered at 21:06:00
  (4 min late); a later task due 21:42 was acted on at 21:50 (8 min late).
- The app is on the deviceidle whitelist and holds SCHEDULE_EXACT_ALARM, but
  allow-while-idle exact alarms are budget-throttled once the device idles. For an
  enforcement app the alarm IS the product; minutes-late blockers break the contract.
- Proposed fix: schedule START_BLOCKER and DEADLINE_BLOCKER alarms via
  `AlarmManager.setAlarmClock()` (doze-exempt, prompt delivery; shows the status-bar alarm
  icon, which is an acceptable and arguably desirable signal for a commitment app).
  Pre-start warnings can stay on the throttled path.

### 5. [FIXED 2026-07-06] Overlay reports "shown" while invisible behind the keyguard

- Observed: 2026-07-05 21:06:00 — `overlay_start attempted result=true` + `overlay shown`
  on a locked phone; TYPE_APPLICATION_OVERLAY does not render above the keyguard, so the
  user saw nothing from the overlay. The full-screen-intent activity (which does have
  `setShowWhenLocked`/`setTurnScreenOn` via `BlockerWindow`) launched 200ms later and is the
  real lock-screen surface. Both surfaces were up simultaneously — the duplicate-surface
  backlog item reproduced live.
- Proposed fix: skip the overlay attempt when `KeyguardManager.isKeyguardLocked` (rely on
  the FSI activity), and revisit surface dedup once one surface acts.

### 6. [FIXED 2026-07-06] Overlay watcher floods logcat and polls indefinitely

- Observed: 2026-07-05 — the 2-3s snapshot polling of a (keyguard-invisible) overlay ran for
  an extended period, rotating the entire logcat buffer (evidence of the session was lost)
  and burning network/battery.
- Proposed: back off the poll interval, cap total watcher lifetime, and log one line per
  state change instead of one per poll.

### 7. No start blocker for tasks whose start is already in the past

- Observed: 2026-07-06 device pass. A task created with "Start at" = now (the picker default)
  gets NO start blocker: by the time the create round-trips (Todoist -> backend poll ->
  snapshot -> reconcile), `startAt <= now`, and `AlarmPlanCalculator` only schedules
  START_BLOCKER when `startAt > now`. The task skips the whole start-commitment flow -
  never enters `started` state, "I'm starting" is never asked, postponement escalation can
  never fire. The default-to-now picker makes this the common case.
- Proposed fix: during reconcile, a `planned` task with start <= now < deadline (block in
  progress) should trigger an immediate start blocker (schedule at now, or fire the surface
  directly), so committing to "start now" still demands the explicit "I'm starting".

## Fixed

Items 1, 2, 4, 5, 6 fixed by the alarm-reliability/Todoist-semantics change merged
2026-07-06 (`022ff35`, plan `docs/superpowers/plans/2026-07-05-alarm-reliability-and-todoist-semantics.md`,
handoff `docs/handoffs/2026-07-05-alarm-reliability-and-todoist-semantics.md`). Device-verified:
alarm delivered 130 ms after schedule on a locked phone; keyguard overlay skip logged; blocker
rendered over the lock screen. Open: items 3 (refresh indicator reflow) and 7 (no start blocker
for in-progress blocks).
