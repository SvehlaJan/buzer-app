# Handoff: Android Overlay Boot Recovery (Phase 1)

Date: 2026-06-12

## Task / Plan

Plan: `docs/superpowers/plans/2026-06-10-android-overlay-boot-recovery.md`, executed task-by-task.

Checkbox status:

- Task 1 (Cached Reconciliation API): complete.
- Task 2 (Background-safe boot recovery): complete.
- Task 3 (Overlay Permission Health): Steps 1-3, 5 complete; Step 4 (UI affordance) deferred — no permission-health UI consumer exists yet.
- Task 4 (Overlay Service and View): complete.
- Task 5 (Alarm Receiver overlay-first): complete (Step 4 compile-only coverage).
- Task 6 (Debug Launch Path): complete.
- Task 7 (Verification): Steps 1-4, 7 done on the emulator; Steps 5-6 (timed lock-screen / foreground-app real-alarm) deferred to the Pixel 5.

Workspace: implemented directly on `master` with explicit user consent.

## Files Changed

Production:

- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt` — public `reconcileLatestSnapshot()`.
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReconcileRunner.kt` (new) — `goAsync()` boot work.
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReceiver.kt` — wired to `BootReconcileRunner`.
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/RescheduleAfterBoot.kt` (deleted) — dead activity-launch path.
- `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`, `ui/App.kt` — removed vestigial `bootRescheduleRequired` chain.
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/PermissionHealth.kt`, `AndroidPermissionHealthReader.kt` — overlay health item.
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/OverlayPermission.kt`, `OverlayBlockerRequest.kt`, `OverlayBlockerService.kt`, `BlockerOverlayController.kt` (new) — overlay stack.
- `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt` — overlay-first, no direct background `startActivity`.
- `client/androidApp/src/main/AndroidManifest.xml` — overlay permissions + special-use service.
- `client/androidApp/src/debug/kotlin/app/buzer/android/debug/DebugOverlayLauncherActivity.kt` (new) + `src/debug/AndroidManifest.xml`.

Tests:

- `EnforcementCoordinatorTest.kt`, `PermissionHealthTest.kt`, `overlay/OverlayBlockerRequestTest.kt`.

Tooling/docs:

- `scripts/test.sh` (new), `scripts/adb-verify.sh` (new + logcat-filter fix), `.claude/settings.json` (new).
- `docs/notes/2026-06-12-agent-token-efficient-android-testing.md`, `docs/notes/2026-06-12-android-overlay-testing.md`, `docs/notes/local-development.md`, `AGENTS.md`.

## Commands Run

Verification:
- Command: `scripts/test.sh` (`:androidApp:testDebugUnitTest`)
- Result: PASS
- Evidence: `tests=53 skipped=0 failures=0 errors=0 RESULT: PASS`.

Verification:
- Command: `client/gradlew -p client :androidApp:assembleDebug --console=plain -q`
- Result: PASS
- Evidence: exit 0, no output.

Verification:
- Command: `BUZER_DEVICE_SERIAL=emulator-5554 scripts/adb-verify.sh install`
- Result: PASS
- Evidence: `Installed debug build on emulator-5554.`

Verification:
- Command: `adb -s emulator-5554 shell cmd appops set app.buzer.android SYSTEM_ALERT_WINDOW allow` then `scripts/adb-verify.sh perms`
- Result: PASS
- Evidence: `SYSTEM_ALERT_WINDOW: allow`.

Verification:
- Command: debug overlay launch (`am start ... DebugOverlayLauncherActivity --es kind START_BLOCKER`)
- Result: PASS
- Evidence: `Background started FGS: Allowed [... OverlayBlockerService ...]`; `Buzer: debug_overlay launch result=true`; `Buzer: overlay shown kind=START_BLOCKER taskId=debug-task`.

Verification:
- Command: emulator reboot, then `scripts/adb-verify.sh boot-log`
- Result: PASS
- Evidence: `boot_reconcile received`, `boot_reconcile cached=true`, `cached reconcile complete scheduleVersion=4`, `boot_reconcile refresh=success`, `boot_reconcile complete`; no `BAL_BLOCK` for `MainActivity` in the full boot log (21376 lines scanned).

## Tests Run

- `EnforcementCoordinatorTest` (cache miss/hit, no-network), `PermissionHealthTest` (overlay error item), `OverlayBlockerRequestTest` (start/deadline/morning/invalid) — all green within the 53-test suite. Each new behavior was written test-first (RED confirmed, then GREEN).

## Manual Verification

- Emulator (`emulator-5554`, API 34): install, overlay grant, overlay launch above a foreground app (log-confirmed), live snapshot refresh + cache save, and full reboot boot-recovery sequence.
- Overlay visual confirmation over the Settings app was omitted on the emulator: `TYPE_APPLICATION_OVERLAY` sits below system UI, which is the documented Phase 1 limitation. Overlay-add success is confirmed via `overlay shown` logs.
- Pixel 5 (`11141FDD4002R1`): install + permissions confirmed (`SYSTEM_ALERT_WINDOW/POST_NOTIFICATION/SCHEDULE_EXACT_ALARM/USE_FULL_SCREEN_INTENT: allow`, battery whitelisted). Debug overlay launched over the Clock app and **rendered correctly with the full blocker UI** (title, summary, Start now / Postpone 5 min) — visual confirmation over a real non-system foreground app. Tapping `Start now` logged `overlay_action action=START` → `action start action=START_TASK`, confirming the action→coordinator wiring.

## Known Failures / Skipped Checks

- Step 5 (timed lock-screen) and Step 6 (timed foreground-app, real exact alarm) deferred to the Pixel 5 — device was not unlockable at implementation time.
- Boot recovery showed `scheduled=0` because the live snapshot has no active/starting-today tasks; alarm restoration with real future alarms is unverified (data limitation, not code).
- Task 3 Step 4 overlay setup affordance is unwired — `AndroidPermissionHealthReader` has no UI consumer in the app yet; `OverlayPermission.settingsIntent()` is implemented and ready.
- `AlarmReceiver` overlay branching has compile-only coverage (framework-coupled); verified via emulator.
- **Robustness gap found and fixed on Pixel 5:** tapping an overlay action against a non-existent `debug-task` left the action hanging at `action start` for minutes — the overlay had no action-level timeout, so a hung/slow backend trapped the full-screen overlay (had to `am force-stop` to clear it). Fixed by wrapping the coordinator action in `withTimeout(15s)` in `OverlayBlockerService.handleAction`; a no-escape "dismiss" button was deliberately not added (it would defeat enforcement). Re-verified on the Pixel 5: `overlay_action timeout … afterMs=15000` → `overlay dismissed` ~15s after the tap, screen released. Remaining follow-up: the existing blocker activities (`StartBlockerActivity` etc.) still share the no-action-timeout behavior.

## Assumptions

- `master` is the intended baseline (user chose to implement on it directly).
- Requiring a manually granted overlay permission is acceptable for this personal dev-device MVP.
- The overlay is additive; blocker activities + full-screen notifications remain the fallback (and the lock-screen path).

## Next Recommended Step

On the Pixel 5 when available: install the debug build, grant overlay (`appops ... SYSTEM_ALERT_WINDOW allow`), then run the deferred Steps 5-6 (timed lock-screen blocker and timed foreground-app overlay from a real exact alarm with a real scheduled task), and re-confirm the reboot boot-recovery with a snapshot that contains a future alarm so `dumpsys alarm` shows restored alarms. Commands: `docs/notes/2026-06-12-android-overlay-testing.md`.
