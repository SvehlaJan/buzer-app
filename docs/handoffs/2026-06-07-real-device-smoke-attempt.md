# Handoff: Real Device Smoke Attempt

Date: 2026-06-07

## Task / Plan

Task: Run a real-device smoke test after merging and pushing the home static blocker route cleanup.

Plan: No standalone implementation plan was created because this was a verification-only session. The intended path was a connected physical-device smoke against `tools/fake-backend` through `adb reverse`, avoiding live Todoist and WhatsApp side effects.

Status: Partial but sufficient for the merged route-cleanup scope. The fake backend and Android test APK setup passed. Connected-device instrumentation failed in the AndroidX/Espresso test harness before app behavior was exercised. After the physical device was reconnected, manual fake-backend smoke covered the Home, Add task, and Morning review paths. Direct manual blocker launch through ADB is blocked because the blocker activities are correctly `exported=false`; complete physical-device blocker smoke needs either fixed connected instrumentation or a debug-only internal launcher.

## Files Changed

- `docs/handoffs/2026-06-07-real-device-smoke-attempt.md`

## Commands Run

Verification:
- Command: `adb devices -l`
- Result: PASS
- Evidence: initially detected `56061FDCR005J0`, model `Pixel_10`, device codename `frankel`.

Verification:
- Command: `cd client && ./gradlew :androidApp:assembleDebugAndroidTest`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 1s`.

Verification:
- Command: `cd tools/fake-backend && npm test`
- Result: PASS
- Evidence: `tests 11`; `pass 11`; `fail 0`.

Verification:
- Command: `cd tools/fake-backend && PORT=18433 npm run dev`
- Result: PASS
- Evidence: fake backend printed `fake backend listening on 18433`.

Verification:
- Command: `adb -s 56061FDCR005J0 reverse tcp:18433 tcp:18433`
- Result: PASS
- Evidence: command returned `18433`; `adb reverse --list` showed `UsbFfs tcp:18433 tcp:18433`.

Verification:
- Command: `cd client && ./gradlew :androidApp:connectedDebugAndroidTest -PBUZER_BACKEND_BASE_URL=http://127.0.0.1:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife -PFAKE_BACKEND_BASE_URL=http://127.0.0.1:18433`
- Result: FAIL
- Evidence: all 17 tests failed on `Pixel 10 - 16` with the same framework error: `NoSuchMethodException: android.hardware.input.InputManager.getInstance []` through `androidx.test.espresso.Espresso.onIdle`.

Verification:
- Command: `adb -s 56061FDCR005J0 shell getprop ro.build.version.sdk`
- Result: PASS
- Evidence: `36`.

Verification:
- Command: `adb -s 56061FDCR005J0 shell getprop ro.build.version.release`
- Result: PASS
- Evidence: `16`.

Verification:
- Command: `npx ctx7@latest library "AndroidX Test" "AndroidX Test Espresso NoSuchMethodException android.hardware.input.InputManager.getInstance Android 16 connectedDebugAndroidTest"`
- Result: PASS
- Evidence: resolved official AndroidX docs.

Verification:
- Command: `npx ctx7@latest docs "/websites/developer_android_jetpack_androidx_releases" "AndroidX Test Espresso NoSuchMethodException android.hardware.input.InputManager.getInstance Android 16 connectedDebugAndroidTest release notes"`
- Result: PASS
- Evidence: official AndroidX Test release docs list current `androidx.test.espresso:espresso-core:3.7.0`.

Verification:
- Command: `cd client && ./gradlew :androidApp:dependencyInsight --configuration debugAndroidTestRuntimeClasspath --dependency espresso-core`
- Result: PASS
- Evidence: connected test runtime resolves `androidx.test.espresso:espresso-core:3.5.0` transitively through `androidx.compose.ui:ui-test-android:1.7.6`.

Verification:
- Command: `android run --device=56061FDCR005J0 --type=ACTIVITY --activity=.MainActivity --apks=client/androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- Result: FAIL
- Evidence: Android CLI reported `Device with serial or AVD name '56061FDCR005J0' not found`.

Verification:
- Command: `adb -s 56061FDCR005J0 install -r client/androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- Result: PASS after reconnect
- Evidence: debug APK installed successfully on physical device `56061FDCR005J0`.

Verification:
- Command: `adb devices -l`
- Result: PASS after reconnect
- Evidence: `56061FDCR005J0`, model `Pixel_10`, device codename `frankel`, remained visible at the end of the session.

Verification:
- Command: `cd client && ./gradlew :androidApp:assembleDebug -PBUZER_BACKEND_BASE_URL=http://127.0.0.1:18433 -PBUZER_BACKEND_TOKEN=test-token -PBUZER_ASSIGNEE_ME_ID=user-me -PBUZER_ASSIGNEE_WIFE_ID=user-wife`
- Result: PASS
- Evidence: debug APK rebuilt with fake-backend config before manual physical-device smoke.

Verification:
- Command: `adb -s 56061FDCR005J0 shell am start -n app.buzer.android/.MainActivity`
- Result: PASS
- Evidence: launched the app on the physical device.

Verification:
- Command: `adb -s 56061FDCR005J0 shell am start -n app.buzer.android/.blockers.StartBlockerActivity --es app.buzer.extra.TASK_ID task-start`
- Result: FAIL / expected limitation
- Evidence: Android returned `SecurityException` because `StartBlockerActivity` is not exported. This is correct for production security, but it prevents direct host-side ADB launch for manual smoke.

Verification:
- Command: `lsof -iTCP:18433 -sTCP:LISTEN -n -P`
- Result: PASS
- Evidence: no output after stopping the fake backend.

## Manual Verification

- Home screen smoke: PASS. UI showed `Today`, `Invalid: 1`, `Active blockers: 0`, `Starting today: 1`, `Health warnings: 0`, `Add task`, and `Morning review`. The removed static `Start` / `Deadline` demo route buttons were not present.
- Add task smoke: PASS. Created a fake task assigned to `Me` with due datetime and duration. Fake backend recorded `POST /tasks` with body containing `title`, `assigneeId: "user-me"`, `dueDatetime`, and `durationMinutes: 30`.
- Morning review smoke: PASS. Opened Morning review, saw the invalid assigned fixture with `missing_due_datetime, missing_duration`, tapped `Snooze 5 min`, returned to Home, and fake backend recorded `POST /review/dismiss` with body `{}`.
- Direct Start blocker ADB launch: SKIPPED after expected failure. The activity is intentionally non-exported, so host-side ADB cannot launch it directly on Android 16.
- No live Todoist, WhatsApp, or VPS destructive checks were performed.

## Known Failures Or Skipped Checks

- Physical-device connected instrumentation is blocked on Pixel 10 / Android 16 by an AndroidX/Espresso harness failure before app assertions execute.
- Direct host-side ADB blocker launch is blocked by the intended non-exported activity configuration.
- Real lock-screen, exact-alarm idle behavior, widget tap, and boot rescheduling remain unverified on physical hardware in this attempt.

## Assumptions

- The 17 connected test failures are not evidence of app behavior failures because every failure has the same `Espresso.onIdle` / `InputManager.getInstance` root cause.
- Managed-device API 34 results remain the reliable automated native test baseline until the connected Android 16 test harness is updated.
- For the merged static route cleanup, the emulator/instrumentation baseline is sufficient acceptance coverage. Physical-device smoke is optional hardware confidence, not required for this change.

## Next Recommended Step

Do one of:

1. Stop here for the route-cleanup task; the relevant managed-device regression coverage passed before merge.
2. In a separate planned slice, update connected-test dependencies to force a current Espresso version, then rerun `connectedDebugAndroidTest` on Android 16.
3. If complete physical-device blocker smoke is still desired, add a debug-only internal launcher or rely on fixed instrumentation rather than direct ADB launches of non-exported activities.
