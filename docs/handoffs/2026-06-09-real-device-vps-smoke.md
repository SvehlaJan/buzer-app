# 2026-06-09 Real Device VPS Smoke Test

## Task / Plan

- Task: run Android smoke test against the live VPS backend after manual deployment.
- Related plan: `docs/superpowers/plans/2026-06-09-vps-deployment-port.md`
- Result: smoke test passed on a connected Pixel 5.

## Files Changed

- `docs/handoffs/2026-06-09-real-device-vps-smoke.md`

No app or backend code changed.

## Commands Run

- `git status --short`
- `adb devices -l`
- `adb kill-server`
- `adb start-server`
- `adb devices -l`
- `grep -E '^(BUZER_BACKEND_BASE_URL|BUZER_BACKEND_TOKEN|ANDROID_HOME|ANDROID_SDK_ROOT)=' client/local.properties | sed -E 's/=.*/=<set>/'`
- `grep '^BUZER_BACKEND_BASE_URL=' client/local.properties | sed 's/BUZER_BACKEND_BASE_URL=//'`
- `ANDROID_SERIAL=11141FDD4002R1 ./gradlew :androidApp:installDebug`
- `adb -s 11141FDD4002R1 logcat -c`
- `adb -s 11141FDD4002R1 shell pm path app.buzer.android`
- `adb -s 11141FDD4002R1 shell am start -W -n app.buzer.android/.MainActivity`
- `adb -s 11141FDD4002R1 logcat -d | rg -i 'Buzer|main refresh|snapshot|ktor|UnknownHost|ConnectException|SocketTimeout|Cleartext|REFRESH_SNAPSHOT|FATAL EXCEPTION|AndroidRuntime'`
- `adb -s 11141FDD4002R1 exec-out uiautomator dump /dev/tty`
- `adb -s 11141FDD4002R1 shell run-as app.buzer.android ls -la files`

## Verification

- Command: `adb devices -l`
- Result: PASS
- Evidence: detected `11141FDD4002R1` as `Pixel_5` / `redfin`.

- Command: `ANDROID_SERIAL=11141FDD4002R1 ./gradlew :androidApp:installDebug`
- Result: PASS
- Evidence: installed `androidApp-debug.apk` on `Pixel 5 - 14`; Gradle reported `BUILD SUCCESSFUL`.

- Command: `adb -s 11141FDD4002R1 shell am start -W -n app.buzer.android/.MainActivity`
- Result: PASS
- Evidence: launch status `ok`, `LaunchState: COLD`, `Activity: app.buzer.android/.MainActivity`.

- Command: `adb -s 11141FDD4002R1 logcat -d | rg -i 'Buzer|main refresh|snapshot|ktor|UnknownHost|ConnectException|SocketTimeout|Cleartext|REFRESH_SNAPSHOT|FATAL EXCEPTION|AndroidRuntime'`
- Result: PASS
- Evidence: app logged `refresh fetched scheduleVersion=2 serverTime=2026-06-09T20:53:35.144Z tasks=43 visible=39 invalid=34 active=0 startingToday=0 completed=4 deleted=0 healthWarnings=0`, saved `latest_snapshot.json`, reconciled alarms, updated main snapshot state, and completed the refresh runner. No matched network failure or fatal exception was present.

- Command: `adb -s 11141FDD4002R1 exec-out uiautomator dump /dev/tty`
- Result: PASS
- Evidence: home screen showed `Today`, `Invalid: 34`, `Active blockers: 0`, `Starting today: 0`, `Health warnings: 0`, `Add task`, and `Morning review`; no `Refresh failed` text was present.

- Command: `adb -s 11141FDD4002R1 shell run-as app.buzer.android ls -la files`
- Result: PASS
- Evidence: app-private files contained `latest_snapshot.json` and no `blocker_action_errors.log`.

## Manual Verification

- Confirmed `client/local.properties` contains both backend URL and token keys without printing the token.
- Confirmed the debug backend URL used for the smoke test is `http://100.105.180.34:18432`.
- Direct HTTP probing from Android shell was skipped because the device image does not provide `curl` or `wget`; app-level refresh verified connectivity instead.

## Known Failures Or Skipped Checks

- Did not create or mutate live tasks from the Android UI to avoid Todoist side effects during the smoke test.
- Did not run fake-backend instrumentation tests in this pass because the requested smoke target was the live VPS backend on a real device.

## Assumptions

- The connected Pixel 5 is on the same Tailscale network path required to reach `100.105.180.34:18432`.
- The current `client/local.properties` bearer token is valid for the live VPS backend.

## Next Recommended Step

Keep `WHATSAPP_DRY_RUN=true` until a deliberate live escalation test is scheduled. The next low-risk check is opening the Android `Add task` form without submitting, then separately deciding whether to create a real Todoist test task.
