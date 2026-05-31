# Handoff: Real Device Validation

Date: 2026-05-29
Agent: Codex GPT-5
Branch: `feat/android-vps-enforcement`
Device: Pixel 10 (`56061FDCR005J0`)

## Scope

Ran the first real-device smoke validation for the Android VPS-connected enforcement slice.

## Findings

- The debug APK builds and installs on the connected device.
- `MainActivity` launches successfully.
- Because `client/local.properties` is missing from the implementation worktree, the app reports the expected debug configuration error instead of attempting backend calls.
- Direct `adb shell am start` commands cannot launch blocker activities because they are correctly `exported=false`.
- Added a debug-only exported `DebugBlockerLauncherActivity` under `src/debug` to launch internal blocker activities for manual testing without exporting production blocker activities.
- The debug launcher can start Start, Deadline, and Morning Review blocker flows on device.
- With missing backend config, Start and Deadline report the expected config error and hide; Morning Review remains visible with its local UI.

## Files Changed

- `client/androidApp/src/debug/AndroidManifest.xml`
- `client/androidApp/src/debug/kotlin/app/buzer/android/debug/DebugBlockerLauncherActivity.kt`
- `docs/notes/2026-05-28-blocker-manual-test.md`
- `docs/handoffs/2026-05-29-real-device-validation.md`

## Commands Run

- Command: `adb devices -l`
  - Result: PASS
  - Evidence: Pixel 10 connected as `device`.
- Command: `cd client && ./gradlew :androidApp:assembleDebug`
  - Result: PASS
  - Evidence: `BUILD SUCCESSFUL`.
- Command: `adb install -r client/androidApp/build/outputs/apk/debug/androidApp-debug.apk`
  - Result: PASS
  - Evidence: `Success`.
- Command: `adb shell am start -n app.buzer.android/.MainActivity`
  - Result: PASS
  - Evidence: activity start command succeeded.
- Command: `adb shell am start -W -n app.buzer.android/.blockers.StartBlockerActivity --es app.buzer.extra.TASK_ID task-1`
  - Result: FAIL
  - Evidence: Android denied launch because activity is not exported.
- Command: `adb shell am start -W -n app.buzer.android/.debug.DebugBlockerLauncherActivity --es app.buzer.debug.BLOCKER_KIND start --es app.buzer.extra.TASK_ID task-1`
  - Result: PASS
  - Evidence: launcher started `StartBlockerActivity`.
- Command: `adb shell am start -W -n app.buzer.android/.debug.DebugBlockerLauncherActivity --es app.buzer.debug.BLOCKER_KIND deadline --es app.buzer.extra.TASK_ID task-1`
  - Result: PASS
  - Evidence: launcher started `DeadlineBlockerActivity`.
- Command: `adb shell am start -W -n app.buzer.android/.debug.DebugBlockerLauncherActivity --es app.buzer.debug.BLOCKER_KIND morning`
  - Result: PASS
  - Evidence: launcher started `MorningReviewActivity`.
- Command: `adb shell run-as app.buzer.android cat files/blocker_action_errors.log`
  - Result: PASS
  - Evidence: log contains `REFRESH_SNAPSHOT` entries for missing `BUZER_BACKEND_BASE_URL`.

## Verification

- Command: `cd client && ./gradlew :androidApp:assembleDebug`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL`.

## Manual Verification

- Install on real device: PASS.
- MainActivity launch: PASS.
- Start blocker debug launch: PASS.
- Deadline blocker debug launch: PASS.
- Morning review debug launch: PASS.
- Live VPS backend sync: SKIPPED because backend URL/token are not configured in `client/local.properties`.

## Known Failures Or Skipped Checks

- Live backend snapshot fetch was not tested.
- Backend action buttons were not tested against the VPS.
- Todoist completion propagation to the app was not tested.
- Exact alarm delivery and boot broadcast were not tested.
- Gradle emits the existing Kotlin Multiplatform / Android Gradle Plugin compatibility warning.

## Next Recommended Step

Add `BUZER_BACKEND_BASE_URL` and `BUZER_BACKEND_TOKEN` to `client/local.properties` in the implementation worktree, rebuild/reinstall, and rerun the backend-connected manual checks against the VPS.
