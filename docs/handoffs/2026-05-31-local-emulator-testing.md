# Handoff: Local Emulator Testing

Date: 2026-05-31
Agent: Codex GPT-5
Plan/task attempted: set up and use a local Android emulator for backend-connected client testing.

## Scope

- Located Android Studio under `/Users/svehla/Applications/Android Studio.app`.
- Used the installed Android SDK command-line tools to create a reusable local AVD.
- Booted the emulator, installed the debug APK, launched the app, and verified backend snapshot caching.
- Fixed the Android manifest after emulator testing exposed a missing `INTERNET` permission.

## Files Changed

- `client/androidApp/src/main/AndroidManifest.xml`
- `docs/handoffs/2026-05-31-local-emulator-testing.md`
- `docs/notes/2026-05-31-local-emulator-testing.md`

## Local State Created

- AVD: `Buzer_API_34` in the local Android emulator config.
- Ignored local backend config remains in `client/local.properties`.

## Commands Run

- `npx ctx7@latest library "Android Emulator" "create and start Android virtual device from command line avdmanager sdkmanager emulator adb wait-for-device"`
- `npx ctx7@latest docs "/websites/developer_android" "create and start Android virtual device from command line avdmanager sdkmanager emulator adb wait-for-device"`
- `find /Applications ~/Applications -maxdepth 2 -iname '*Android*Studio*.app' -print`
- `plutil -p "/Users/svehla/Applications/Android Studio.app/Contents/Info.plist"`
- `~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager list avd`
- `~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --list_installed`
- `~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager create avd --force --name Buzer_API_34 --package 'system-images;android-34;google_apis;arm64-v8a' --device pixel_8`
- `~/Library/Android/sdk/emulator/emulator -avd Buzer_API_34 -no-snapshot-save -no-boot-anim`
- `~/Library/Android/sdk/platform-tools/adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done; echo booted'`
- `~/Library/Android/sdk/platform-tools/adb install -r client/androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- `~/Library/Android/sdk/platform-tools/adb shell am start -W -n app.buzer.android/.MainActivity`
- `~/Library/Android/sdk/platform-tools/adb shell run-as app.buzer.android cat files/blocker_action_errors.log`
- `~/Library/Android/sdk/build-tools/35.0.0/aapt dump permissions client/androidApp/build/outputs/apk/debug/androidApp-debug.apk | rg INTERNET`
- `./gradlew :androidApp:assembleDebug`
- `./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`

## Verification

- Command: `~/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager create avd --force --name Buzer_API_34 --package 'system-images;android-34;google_apis;arm64-v8a' --device pixel_8`
- Result: PASS
- Evidence: created the AVD and auto-selected `arm64-v8a`.

- Command: `~/Library/Android/sdk/platform-tools/adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done; echo booted'`
- Result: PASS
- Evidence: printed `booted`.

- Command: `~/Library/Android/sdk/platform-tools/adb shell getprop ro.build.version.release`
- Result: PASS
- Evidence: printed `14`.

- Command: `~/Library/Android/sdk/platform-tools/adb shell getprop ro.product.model`
- Result: PASS
- Evidence: printed `sdk_gphone64_arm64`.

- Command: initial app launch before manifest fix
- Result: FAIL
- Evidence: `files/blocker_action_errors.log` contained `Permission denied (missing INTERNET permission?)`.

- Command: `~/Library/Android/sdk/build-tools/35.0.0/aapt dump permissions client/androidApp/build/outputs/apk/debug/androidApp-debug.apk | rg INTERNET`
- Result: FAIL then PASS
- Evidence: initially no output; after the manifest fix it printed `uses-permission: name='android.permission.INTERNET'`.

- Command: `~/Library/Android/sdk/platform-tools/adb uninstall app.buzer.android && adb install ... && adb shell am start ...`
- Result: PASS
- Evidence: uninstall/install succeeded and `MainActivity` launched with `Status: ok`.

- Command: `~/Library/Android/sdk/platform-tools/adb shell run-as app.buzer.android ls -la files`
- Result: PASS
- Evidence: after the fix, app-private files included `latest_snapshot.json` and did not include `blocker_action_errors.log`.

- Command: read `files/latest_snapshot.json`
- Result: PASS
- Evidence: cached snapshot contained `scheduleVersion=1`, `taskCount=40`, `healthCount=0`.

- Command: `cd client && ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug`
- Result: PASS
- Evidence: `BUILD SUCCESSFUL in 1s`; 70 actionable tasks, 4 executed.

## Known Failures Or Skipped Checks

- The emulator was not tested with Tailscale installed inside Android. Backend access works through the emulator's host networking path to the Tailscale hostname/IP.
- Full-screen blocker interactions were not re-tested in this session after the internet-permission fix.

## Assumptions

- API 34 is sufficient for local MVP testing even though the app compiles with SDK 35.
- The AVD should be kept reusable instead of deleted after this session.

## Next Recommended Step

- Use `Buzer_API_34` as the default local automated/manual Android target for backend-connected checks.
- Add instrumentation tests around startup refresh and blocker launch flows when the client behavior stabilizes enough to automate them.
