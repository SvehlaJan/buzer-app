# 2026-06-10 Android Overlay Testing

## Debug Helpers

This branch adds two debug-only activities for real-device testing:

```bash
adb -s 11141FDD4002R1 shell am start \
  -n app.buzer.android/.debug.DebugOverlayLauncherActivity \
  --es kind START_BLOCKER \
  --es task_id debug-task
```

```bash
adb -s 11141FDD4002R1 shell am start \
  -n app.buzer.android/.debug.DebugAlarmSchedulerActivity \
  --es kind START_BLOCKER \
  --es task_id debug-task \
  --ei delay_seconds 10
```

Overlay grant:

```bash
adb -s 11141FDD4002R1 shell cmd appops set app.buzer.android SYSTEM_ALERT_WINDOW allow
adb -s 11141FDD4002R1 shell cmd appops get app.buzer.android | rg SYSTEM_ALERT_WINDOW
```

## Findings

- Direct debug overlay launch starts `OverlayBlockerService` and adds a `SYSTEM_ALERT_WINDOW` window.
- The custom overlay is visible above the launcher and normal foreground surfaces.
- Android Settings can suppress or obscure the custom overlay on some Settings pages. In that case the full-screen notification fallback remains visible.
- Exact alarm delivery starts the overlay path through `AlarmReceiver`; the receiver also posts the existing full-screen notification fallback.
- On the lock-screen test, the alarm woke the device, started `OverlayBlockerService`, and the full-screen blocker activity became the focused fallback surface.
- Overlay action calls are bounded by a 15-second timeout so a debug task or slow backend cannot leave the overlay stuck indefinitely.

## Boot Recovery Test Method

For the boot recovery test, the real cached snapshot was backed up from:

```text
/data/user/0/app.buzer.android/files/latest_snapshot.json
```

A temporary one-task snapshot was written with `scheduleVersion=999001`, `id=boot-test-task`, and a future deadline. After reboot:

- Android started `app.buzer.android/.enforcement.BootReceiver`.
- Buzer logged `boot_reconcile refresh=timeout` and `boot_reconcile complete`.
- `dumpsys alarm` showed an `RTC_WAKEUP` alarm for `app.buzer.android/.enforcement.AlarmReceiver` restored from the cached snapshot.
- No `BAL_BLOCK` was observed for `MainActivity`.

The original 21 KB cached snapshot was restored afterward, and the app was launched once so it was not left in the stopped state.
