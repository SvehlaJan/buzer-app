# 2026-06-10 Android Overlay Boot Recovery

## Task / Plan

- Task: implement `docs/superpowers/plans/2026-06-10-android-overlay-boot-recovery.md`.
- Completed plan sections:
  - Task 1: cached reconciliation API.
  - Task 2: background boot recovery with `goAsync()`.
  - Task 3: overlay permission health and setup action.
  - Task 4: overlay service, native overlay controller, and actions.
  - Task 5: alarm receiver overlay-first fallback.
  - Task 6: debug overlay launch path and testing note.
  - Task 7: JVM/build/device smoke tests.

## Files Changed

- Android boot/reconcile:
  - `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementCoordinator.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReceiver.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/BootReconcileRunner.kt`
  - deleted `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/RescheduleAfterBoot.kt`
- Android overlay:
  - `client/androidApp/src/main/AndroidManifest.xml`
  - `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AlarmReceiver.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/overlay/*`
- Permission health/UI:
  - `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/AndroidPermissionHealthReader.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/PermissionHealth.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/MainActivity.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/ui/App.kt`
  - `client/androidApp/src/main/kotlin/app/buzer/android/ui/screens/HomeScreen.kt`
- Debug helpers:
  - `client/androidApp/src/debug/AndroidManifest.xml`
  - `client/androidApp/src/debug/kotlin/app/buzer/android/debug/DebugOverlayLauncherActivity.kt`
  - `client/androidApp/src/debug/kotlin/app/buzer/android/debug/DebugAlarmSchedulerActivity.kt`
- Tests/docs:
  - `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementCoordinatorTest.kt`
  - `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/BootReconcileRunnerTest.kt`
  - `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/PermissionHealthTest.kt`
  - `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/overlay/OverlayBlockerRequestTest.kt`
  - `docs/superpowers/plans/2026-06-10-android-overlay-boot-recovery.md`
  - `docs/notes/2026-06-10-android-overlay-testing.md`
  - `docs/handoffs/2026-06-10-android-overlay-boot-recovery.md`

## Commands Run

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.EnforcementCoordinatorTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.BootReconcileRunnerTest' --tests 'app.buzer.android.enforcement.EnforcementCoordinatorTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.PermissionHealthTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.overlay.OverlayBlockerRequestTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

Verification:
- Command: `ANDROID_SERIAL=11141FDD4002R1 ./gradlew :androidApp:installDebug`
- Result: PASS
- Evidence: Gradle installed `androidApp-debug.apk` on `Pixel 5 - 14`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell cmd appops set app.buzer.android SYSTEM_ALERT_WINDOW allow`
- Result: PASS
- Evidence: `cmd appops get app.buzer.android | rg SYSTEM_ALERT_WINDOW` returned `SYSTEM_ALERT_WINDOW: allow`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell am start -n app.buzer.android/.debug.DebugOverlayLauncherActivity --es kind START_BLOCKER --es task_id debug-task`
- Result: PASS
- Evidence: Buzer logs showed `debug_overlay_launcher result=true` and `overlay_service shown`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell am start -n app.buzer.android/.debug.DebugAlarmSchedulerActivity --es kind START_BLOCKER --es task_id debug-task --ei delay_seconds 10`
- Result: PASS
- Evidence: Buzer logs showed `debug_alarm_scheduler scheduled`, then `overlay_start attempted result=true`, then `overlay_service shown`.

Verification:
- Command: `adb -s 11141FDD4002R1 exec-out screencap -p > /tmp/buzer-overlay-home.png`
- Result: PASS
- Evidence: screenshot showed the custom Buzer overlay above the launcher with `I'm starting` and `Postpone 5 min`.

Verification:
- Command: lock-screen debug alarm test using `DebugAlarmSchedulerActivity`, `adb shell input keyevent 26`, and log/window inspection.
- Result: PASS
- Evidence: logs showed overlay service start; `dumpsys window` showed `StartBlockerActivity` focused as full-screen fallback.

Verification:
- Command: reboot boot recovery test with seeded cached snapshot.
- Result: PASS
- Evidence: Android started `app.buzer.android/.enforcement.BootReceiver`; logs showed `boot_reconcile refresh=timeout` and `boot_reconcile complete`; `dumpsys alarm` showed `RTC_WAKEUP` for `app.buzer.android/.enforcement.AlarmReceiver`; no `BAL_BLOCK` appeared.

## Tests Run

- Android JVM tests: PASS.
- Debug APK assemble: PASS.
- Real Pixel 5 install: PASS.
- Direct overlay smoke test: PASS.
- Foreground-app/launcher exact-alarm overlay test: PASS.
- Lock-screen exact-alarm blocker test: PASS.
- Boot recovery test: PASS.

## Manual Verification

- The custom overlay appears above normal foreground surfaces.
- Android Settings can suppress the custom overlay, but the notification fallback remains visible.
- Exact alarm receiver starts overlay first and keeps full-screen notification fallback.
- Lock-screen alarm wakes the phone and brings the blocker fallback to focus.
- Boot recovery no longer starts `MainActivity`; Android delivered `BootReceiver` and no `BAL_BLOCK` was observed.

## Known Failures / Skipped Checks

- The timed tests used debug exact alarms and debug task IDs, not live Todoist task creation, to avoid modifying real Todoist data during this infrastructure pass.
- Overlay action against debug task IDs times out after 15 seconds if the backend request does not complete; this is intentional cleanup behavior.
- Settings pages can hide/suppress app overlays. This is an Android platform limitation; fallback notifications/activities remain necessary.

## Assumptions

- `SYSTEM_ALERT_WINDOW` remains acceptable because this is a dev-device-only MVP.
- Full-screen activity fallback should remain in place even after overlay succeeds because overlays are not a complete enforcement boundary.
- The debug scheduler activities are debug-source-set only and are not part of a release build.

## Next Recommended Step

Use the app against a real short-lived strict task only when you are comfortable creating/modifying live Todoist data. The Android enforcement plumbing is now ready for that MVP check; the remaining risk is backend/Todoist action latency rather than alarm or overlay delivery.
