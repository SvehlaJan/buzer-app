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
  - `client/androidApp/src/main/kotlin/app/buzer/android/enforcement/EnforcementRefreshRunner.kt`
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
  - `client/androidApp/src/test/kotlin/app/buzer/android/enforcement/EnforcementRefreshRunnerTest.kt`
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
- Command: `./gradlew :androidApp:testDebugUnitTest --tests 'app.buzer.android.enforcement.EnforcementRefreshRunnerTest'`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`; timeout test confirms refresh reports `EnforcementRefreshTimeoutException`.

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

Verification:
- Command: live backend/Todoist smoke task through `POST /tasks`, Android refresh attempt, and cleanup through `POST /tasks/:id/done`.
- Result: PARTIAL
- Evidence: backend created task `fd91322f-fce4-4524-a962-b9f2805ca2cc` with Todoist ID `6gqpxJW3MWxj5M57`; cleanup returned `{"ok":true}` and snapshot showed `state=completed`, `isCompleted=true`. Android could not pull the live snapshot because the phone could not connect to `100.105.180.34:18432`.

Verification:
- Command: `adb -s 11141FDD4002R1 shell ping -c 1 -W 3 100.105.180.34`
- Result: FAIL
- Evidence: `1 packets transmitted, 0 received, 100% packet loss`.

Verification:
- Command: reinstall updated debug APK and launch Buzer with phone unable to reach backend.
- Result: PASS
- Evidence: logcat showed `refresh_runner timeout timeoutMillis=10000` and `EnforcementRefreshTimeoutException: snapshot refresh timed out after 10000ms`.

## Tests Run

- Android JVM tests: PASS.
- Debug APK assemble: PASS.
- Real Pixel 5 install: PASS.
- Direct overlay smoke test: PASS.
- Foreground-app/launcher exact-alarm overlay test: PASS.
- Lock-screen exact-alarm blocker test: PASS.
- Boot recovery test: PASS.
- Live backend/Todoist cleanup: PASS.
- Live Android sync against the VPS: BLOCKED by phone network route to the Tailscale VPS.

## Manual Verification

- The custom overlay appears above normal foreground surfaces.
- Android Settings can suppress the custom overlay, but the notification fallback remains visible.
- Exact alarm receiver starts overlay first and keeps full-screen notification fallback.
- Lock-screen alarm wakes the phone and brings the blocker fallback to focus.
- Boot recovery no longer starts `MainActivity`; Android delivered `BootReceiver` and no `BAL_BLOCK` was observed.
- A real Todoist-backed smoke task was created and completed during follow-up testing. It did not remain active.
- The connected phone currently cannot reach the VPS Tailscale IP `100.105.180.34`; Android live sync requires fixing this device network route.

## Known Failures / Skipped Checks

- The main alarm/overlay timed tests used debug exact alarms and debug task IDs. A separate live Todoist-backed task was created and completed for backend cleanup verification, but the Android device could not sync it because it cannot reach the Tailscale backend IP.
- Overlay action against debug task IDs times out after 15 seconds if the backend request does not complete; this is intentional cleanup behavior.
- Settings pages can hide/suppress app overlays. This is an Android platform limitation; fallback notifications/activities remain necessary.
- Full live Android end-to-end verification remains pending until the phone can reach the VPS backend.

## Assumptions

- `SYSTEM_ALERT_WINDOW` remains acceptable because this is a dev-device-only MVP.
- Full-screen activity fallback should remain in place even after overlay succeeds because overlays are not a complete enforcement boundary.
- The debug scheduler activities are debug-source-set only and are not part of a release build.

## Next Recommended Step

Fix phone-to-backend connectivity for live Android sync. The most direct option is installing/enabling Tailscale on the Pixel 5 so it can reach `100.105.180.34:18432`. After that, rerun the live short-task smoke test from Android; the app should refresh within 10 seconds, schedule the strict task alarms, show the overlay/fallback blocker, and complete the task through the backend.
