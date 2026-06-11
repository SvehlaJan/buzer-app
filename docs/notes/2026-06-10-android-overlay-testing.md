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
- Foreground app refresh is bounded by a 10-second timeout. On a device without a route to the Tailscale backend, Buzer logs `refresh_runner timeout timeoutMillis=10000` and reports refresh failure instead of waiting for Android's longer TCP timeout.

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

## Live Backend/Todoist Smoke Attempt

On 2026-06-11, a real Todoist-backed task was created through the live backend:

```text
backend id: fd91322f-fce4-4524-a962-b9f2805ca2cc
todoist id: 6gqpxJW3MWxj5M57
title: Buzer Android live smoke 20260611T063541Z
```

Backend creation and cleanup worked:

- `POST /tasks` returned the backend and Todoist task IDs.
- `POST /tasks/fd91322f-fce4-4524-a962-b9f2805ca2cc/done` returned `{"ok":true}`.
- Follow-up snapshot showed `state=completed` and `isCompleted=true`.

The Android side did not complete the live end-to-end path because the phone could not reach the VPS Tailscale IP:

```text
java.net.ConnectException: Failed to connect to /100.105.180.34:18432
adb shell ping -c 1 -W 3 100.105.180.34 -> 100% packet loss
```

This is an environment prerequisite for live Android sync, not an alarm/overlay failure. The dev phone needs Tailscale connectivity to the VPS, or the debug build needs to point at an endpoint reachable from the phone.
