# Android Overlay & Boot Recovery Testing

Date: 2026-06-12

How to exercise the Phase 1 overlay blocker and background-safe boot recovery
(`docs/superpowers/plans/2026-06-10-android-overlay-boot-recovery.md`). Commands below target
the emulator; substitute the Pixel 5 serial (`11141FDD4002R1`) for real-device runs.

Scope a serial when multiple devices are attached: `BUZER_DEVICE_SERIAL=<serial>` for
`scripts/adb-verify.sh`, or `-s <serial>` for raw `adb`.

## Install

```bash
BUZER_DEVICE_SERIAL=emulator-5554 scripts/adb-verify.sh install
```

## Grant overlay permission

Open the system grant screen:

```bash
adb -s emulator-5554 shell am start \
  -a android.settings.action.MANAGE_OVERLAY_PERMISSION \
  -d package:app.buzer.android
```

Dev-device app-op shortcut (no UI tap):

```bash
adb -s emulator-5554 shell cmd appops set app.buzer.android SYSTEM_ALERT_WINDOW allow
adb -s emulator-5554 shell cmd appops get app.buzer.android | rg SYSTEM_ALERT_WINDOW
```

Overlay state also shows up in permission health via `OverlayPermission.canDraw` /
`AndroidPermissionHealthReader` (key `overlay`).

## Direct overlay smoke test (no alarm needed)

Open another app first, then launch the debug overlay:

```bash
adb -s emulator-5554 shell am start \
  -n app.buzer.android/.debug.DebugOverlayLauncherActivity \
  --es kind START_BLOCKER --es task_id debug-task
```

`kind` accepts `START_BLOCKER`, `DEADLINE_BLOCKER`, `MORNING_REVIEW`.

Expected:

- The blocker overlay appears above the foreground app with the per-kind action buttons.
- Logs show overlay activity:

```bash
BUZER_DEVICE_SERIAL=emulator-5554 scripts/adb-verify.sh logcat 100
```

Look for `debug_overlay launch result=true`, `overlay shown kind=…`, `overlay_action …`,
and `overlay dismissed` after tapping an action.

## Boot recovery test (the BAL_BLOCK fix)

With a cached snapshot containing a future alarm, reboot and confirm recovery happens without
launching an activity:

```bash
BUZER_DEVICE_SERIAL=emulator-5554 scripts/adb-verify.sh logcat-clear
adb -s emulator-5554 reboot
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 shell getprop sys.boot_completed
BUZER_DEVICE_SERIAL=emulator-5554 scripts/adb-verify.sh boot-log
BUZER_DEVICE_SERIAL=emulator-5554 scripts/adb-verify.sh alarms
```

Expected:

- `boot_reconcile received` appears in the log.
- `boot_reconcile cached=true` when a cached snapshot exists; otherwise `cached=false`.
- Either `boot_reconcile refresh=success` or a bounded `refresh=timeout` / `refresh=failed`.
- No `BAL_BLOCK` for `MainActivity`.
- `dumpsys alarm` shows Buzer alarms restored when the cached snapshot has scheduled tasks.

## Notes / limits

- The overlay is not a security boundary: `TYPE_APPLICATION_OVERLAY` draws above most apps but
  not the status bar, IME, keyguard, or permission screens. Locked-screen blocker delivery
  still relies on the full-screen-intent notification + `showWhenLocked` blocker activities.
- Real Pixel 5 timed lock-screen / foreground-app runs are deferred (device not unlockable at
  implementation time) — see the handoff for the remaining manual checks.
