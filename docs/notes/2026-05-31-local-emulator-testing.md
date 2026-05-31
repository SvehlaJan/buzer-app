# Local Emulator Testing

Date: 2026-05-31

## AVD

Reusable local AVD created on this Mac:

```bash
Buzer_API_34
```

Device/image:

- Device profile: Pixel 8
- System image: `system-images;android-34;google_apis;arm64-v8a`
- Runtime observed through ADB: Android 14, model `sdk_gphone64_arm64`

## Useful Commands

SDK root:

```bash
~/Library/Android/sdk
```

List AVDs:

```bash
~/Library/Android/sdk/emulator/emulator -list-avds
```

Start emulator:

```bash
~/Library/Android/sdk/emulator/emulator -avd Buzer_API_34 -no-snapshot-save -no-boot-anim
```

Wait for boot:

```bash
~/Library/Android/sdk/platform-tools/adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done; echo booted'
```

Build and install:

```bash
cd client
./gradlew :androidApp:assembleDebug
cd ..
~/Library/Android/sdk/platform-tools/adb install -r client/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Launch:

```bash
~/Library/Android/sdk/platform-tools/adb shell am start -W -n app.buzer.android/.MainActivity
```

Read app-private state:

```bash
~/Library/Android/sdk/platform-tools/adb shell run-as app.buzer.android ls -la files
~/Library/Android/sdk/platform-tools/adb shell run-as app.buzer.android cat files/latest_snapshot.json
~/Library/Android/sdk/platform-tools/adb shell run-as app.buzer.android cat files/blocker_action_errors.log
```

The error log file may not exist when the app has not logged errors.

## Findings

The first emulator install exposed a real app bug: `android.permission.INTERNET` was missing from the Android manifest. The symptom was an app-private error log entry:

```text
action=REFRESH_SNAPSHOT ... message=Permission denied (missing INTERNET permission?)
```

After adding the permission and reinstalling with clean app state, the emulator wrote `files/latest_snapshot.json` with a backend snapshot and did not create `files/blocker_action_errors.log`.

## Current Backend Verification

With the ignored `client/local.properties` configured for the Tailscale backend, emulator startup refresh cached:

```json
{
  "scheduleVersion": 1,
  "taskCount": 40,
  "healthCount": 0
}
```
