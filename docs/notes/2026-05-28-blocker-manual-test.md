# Blocker Manual Test

Date: 2026-05-28

## Status

Skipped in this agent session because no physical Android device or emulator was attached.

## Commands

After building and installing the debug APK:

```bash
adb install -r client/androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n app.buzer.android/.blockers.StartBlockerActivity --es app.buzer.extra.TASK_ID task-1
adb shell am start -n app.buzer.android/.blockers.DeadlineBlockerActivity --es app.buzer.extra.TASK_ID task-1
adb shell am start -n app.buzer.android/.blockers.MorningReviewActivity
```

## Expected Result

- Each blocker opens as a full-screen activity.
- Start blocker shows `I'm starting` and `Postpone 5 min`.
- Deadline blocker shows `Done`, `Extend 30 min`, and `Reschedule`.
- Morning review shows `Resolve selected` and `Snooze 5 min`.
- Locked-screen behavior should be checked after granting notification and exact-alarm permissions.

## Backend-Connected Checks

Before running these checks, configure `client/local.properties` with `BUZER_BACKEND_BASE_URL` and `BUZER_BACKEND_TOKEN`.

```bash
adb install -r client/androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n app.buzer.android/.MainActivity
adb shell am start -n app.buzer.android/.debug.DebugBlockerLauncherActivity --es app.buzer.debug.BLOCKER_KIND start --es app.buzer.extra.TASK_ID task-1
adb shell am start -n app.buzer.android/.debug.DebugBlockerLauncherActivity --es app.buzer.debug.BLOCKER_KIND deadline --es app.buzer.extra.TASK_ID task-1
adb shell am start -n app.buzer.android/.debug.DebugBlockerLauncherActivity --es app.buzer.debug.BLOCKER_KIND morning
```

Expected:

- MainActivity refreshes backend snapshot and schedules alarms.
- Start blocker buttons call backend and hide.
- Deadline blocker Done/Extend call backend and hide.
- With backend stopped, blocker action failure creates a notification and app-private error log.

The production blocker activities remain `exported=false`; use the debug-only launcher above for manual `adb` starts.
