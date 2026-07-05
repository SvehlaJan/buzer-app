# Reliability Scenario Lane Design

Date: 2026-07-05
Status: approved design, pending implementation plan

## Context And Goal

The enforcement paths that make Buzer trustworthy — a real exact alarm producing a blocker,
and boot recovery rescheduling alarms from cache — have only ever been verified manually on
a device, and those checks have been repeatedly deferred since June. This design converts
them into an automated, host-driven scenario lane on a local emulator, asserting outcomes
via the app's structured logs (the project's established verification currency, see
`docs/notes/2026-06-12-agent-token-efficient-android-testing.md`).

Decisions already made during brainstorming:

- Real waits with **~30-second alarm leads** (no emulator clock manipulation); assertion
  polling uses a grace ceiling of lead + ~30s because `setExactAndAllowWhileIdle` can land
  a few seconds late even on an idle emulator.
- **V1 scope: two scenarios** — exact-alarm start blocker and boot recovery. Out of scope
  for v1: overlay-over-a-foreground-app, duplicate-blocker-surface re-verification,
  CI promotion, and lock-credential/keyguard variants beyond screen-off.
- **Local-first**: the lane ships as a dev-machine script. Promotion to a nightly CI job is
  a separate later decision after the lane proves stable locally.

## Component 1: Fake Backend `POST /__seed`

`tools/fake-backend/src/server.ts` currently serves a static `fixedSnapshot()`. Add:

- `POST /__seed` with body `{ "tasks": [ ...full task objects... ] }` (same shape as the
  fixture tasks). After seeding, `GET /tasks/snapshot` returns
  `{ scheduleVersion, serverTime: <now>, health: [], tasks: <seeded tasks> }`.
  Each subsequent `/__seed` replaces the previous seed. `scheduleVersion` increments on
  each seed so the client treats it as fresh state.
- `POST /__reset` (existing) additionally clears the seed, restoring the built-in fixture —
  this keeps the existing 21-test instrumentation lane completely unaffected.
- Like the other `/__` control endpoints, `/__seed` bypasses auth.

Covered by node tests alongside the existing ones: seeded snapshot is served; re-seed
replaces; reset restores the fixture.

## Component 2: `scripts/reliability-e2e.sh`

Host-driven bash script, same conventions as `scripts/e2e-fake-backend.sh` (reuse-if-running
fake backend on 18433 with token `test-token`, cleanup trap). Emulator: the documented
`Buzer_API_36` AVD started headless if not already running (command from
`docs/notes/2026-06-08-android-16-emulator-setup.md`: `-no-window -no-snapshot -no-audio
-gpu swiftshader_indirect -port 5584`, serial `emulator-5584`); if the AVD does not exist,
fail with the avdmanager create command from that note rather than creating it silently.

Setup (once per run):
1. Start/reuse fake backend; wait for readiness probe.
2. Start/reuse emulator; wait for `sys.boot_completed=1`.
3. `./gradlew :androidApp:installDebug` with `-P` overrides:
   `BUZER_BACKEND_BASE_URL=http://10.0.2.2:18433`, `BUZER_BACKEND_TOKEN=test-token`,
   `BUZER_ASSIGNEE_ME_ID=user-me`, `BUZER_ASSIGNEE_WIFE_ID=user-wife`.
4. Grant enforcement permissions up front (v1 tests the happy path, not permission
   degradation): `pm grant app.buzer.android android.permission.POST_NOTIFICATIONS`,
   `cmd appops set app.buzer.android SCHEDULE_EXACT_ALARM allow`,
   `cmd appops set app.buzer.android USE_FULL_SCREEN_INTENT allow`.
   **SYSTEM_ALERT_WINDOW is deliberately NOT granted** in v1: without the overlay, the
   receiver's full-screen-intent notification is the sole blocker surface, which makes the
   scenario deterministic and exercises the lock-screen activity path.

### Scenario 1: exact alarm → start blocker on a sleeping screen

1. `POST /__reset`, then `POST /__seed` with one task (`id=rel-start-1`, `state=planned`,
   `isAssignedToUser=true`, valid duration) whose `startAt = now + 30s` and
   `dueDatetime = startAt + 30min`.
2. Clear logcat (`scripts/adb-verify.sh logcat-clear` semantics), force-stop and launch
   `MainActivity` so the coordinator refreshes and schedules alarms.
3. Assert (poll ≤ 20s): logcat contains `reconcile complete` with `scheduled=` ≥ 1, and
   app-scoped `dumpsys alarm` lists an `app.buzer.android` alarm.
4. Sleep the screen (`input keyevent KEYCODE_SLEEP`) — this makes the full-screen intent
   *launch* the blocker activity instead of showing a heads-up notification.
5. Assert (poll ≤ 60s = 30s lead + 30s grace):
   - `overlay_start attempted result=false kind=START_BLOCKER taskId=rel-start-1` —
     `AlarmReceiver` logs this line on every blocker alarm regardless of overlay
     permission, so it is the deterministic "exact alarm was delivered" proof.
   - `start_blocker show taskId=rel-start-1` — the full-screen intent launched
     `StartBlockerActivity` on the sleeping screen.
6. Wake the screen (`input keyevent KEYCODE_WAKEUP`) for cleanliness.

PASS = both lines observed. FAIL output = which line is missing plus the tag-filtered
logcat tail as evidence.

### Scenario 2: boot recovery from cached snapshot

1. `POST /__reset`, then `POST /__seed` with one task (`id=rel-boot-1`, `state=planned`)
   whose `startAt = now + 180s` (far enough that it cannot fire during the reboot window).
2. Force-stop, launch `MainActivity`, assert `reconcile complete` with `scheduled=` ≥ 1
   (this also persists the snapshot to the app's local cache).
3. `adb reboot`; wait for the device to disappear and return; wait for
   `sys.boot_completed=1` (ceiling ~120s).
4. Assert (poll ≤ 120s after boot): logcat contains `boot_reconcile received`,
   `boot_reconcile cached=true`, and `boot_reconcile complete` (all from
   `BootReconcileRunner`), and app-scoped `dumpsys alarm` again lists an
   `app.buzer.android` alarm for the seeded task.

Notes: the fake backend runs on the host, so it survives the emulator reboot and remains
reachable at `10.0.2.2` with no adb port plumbing; the emulator has no lock credential, so
`BOOT_COMPLETED` is delivered without a manual unlock.

### Script interface

- `scripts/reliability-e2e.sh [alarm-blocker] [boot-recovery]` — no args runs both, in that
  order, with `POST /__reset` between them.
- Per-scenario `PASS`/`FAIL` lines with evidence; non-zero exit if any scenario failed.
- `--keep` leaves the emulator running for fast re-runs (default: leave running if the
  script found it already running, stop it if the script started it).

## Component 3: AGENTS.md hook

Add one rule to Testing Expectations: any plan touching Android enforcement code
(`client/androidApp/.../enforcement/`, `blockers/`, alarm scheduling, or boot handling)
must run `scripts/reliability-e2e.sh` in its final verification task and record the
per-scenario results in the handoff.

## Verification Of The Lane Itself

1. Both scenarios PASS on a clean local run (twice in a row, to shake out first-run vs
   warm-run differences).
2. Negative check — the lane must be able to fail: re-run scenario 1 with
   `cmd appops set app.buzer.android SCHEDULE_EXACT_ALARM deny` and confirm it reports
   FAIL with missing-evidence output (then restore the grant). A verifier that cannot
   fail is decoration.
3. The existing lanes stay green: `tools/fake-backend` node tests and
   `scripts/e2e-fake-backend.sh` (21/21) after the `/__seed` change.

## Error Handling

- Every wait has an explicit ceiling and prints what it was waiting for on timeout.
- The script refuses to run if another device is attached and `ANDROID_SERIAL`/
  `BUZER_DEVICE_SERIAL` doesn't disambiguate (same policy as `scripts/adb-verify.sh`) —
  it must never install to or reboot the user's physical phone.
- The cleanup trap stops the fake backend if the script started it, mirroring
  `e2e-fake-backend.sh`.

## Out Of Scope (recorded so future sessions don't resurrect them accidentally)

- CI promotion of this lane (revisit after it has been stable locally).
- Overlay-over-foreground-app and duplicate-surface scenarios (v2 candidates).
- Permission-degraded paths (health warnings UI is a separate backlog concern).
- Real-device Doze/OEM behavior — stays a rare, script-assisted manual checklist.
