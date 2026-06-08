# Android 16 Emulator Setup

Date: 2026-06-08

## Result

Android 16 / API 36 emulator setup succeeded.

- AVD name: `Buzer_API_36`
- Device profile: `pixel_8`
- System image: `system-images;android-36;google_apis;arm64-v8a`
- Runtime device serial used for tests: `emulator-5584`
- Verified properties:
  - `ro.build.version.sdk`: `36`
  - `ro.build.version.release`: `16`
  - `ro.product.model`: `sdk_gphone64_arm64`

## Setup Commands

Install the API 36 system image:

```bash
android sdk install system-images/android-36/google_apis/arm64-v8a
```

Create the AVD:

```bash
/Users/svehla/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager create avd \
  -n Buzer_API_36 \
  -k "system-images;android-36;google_apis;arm64-v8a" \
  -d pixel_8 \
  --force
```

Start it reliably for headless test runs:

```bash
/Users/svehla/Library/Android/sdk/emulator/emulator \
  @Buzer_API_36 \
  -no-window \
  -no-snapshot \
  -no-audio \
  -gpu swiftshader_indirect \
  -port 5584
```

Verify with ADB:

```bash
adb devices -l
adb -s emulator-5584 shell getprop ro.build.version.sdk
adb -s emulator-5584 shell getprop ro.build.version.release
adb -s emulator-5584 shell getprop ro.product.model
```

Stop it:

```bash
adb -s emulator-5584 emu kill
```

## Testing

Start fake backend:

```bash
cd tools/fake-backend
PORT=18433 npm run dev
```

Run connected instrumentation against only the API 36 emulator:

```bash
cd client
ANDROID_SERIAL=emulator-5584 ./gradlew :androidApp:connectedDebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

Result: `BUILD SUCCESSFUL in 56s`; 17 tests ran on `Buzer_API_36(AVD) - 16`, with 0 skipped and 0 failed.

## Observations

`android emulator start --cold Buzer_API_36` reported successful startup as `emulator-5554`, but the emulator process exited after boot and was not visible to `adb devices`. The raw emulator command above stayed running and was visible to ADB.

The emulator log reports:

- `Warning: Please update the emulator to one that supports the feature(s): VulkanVirtualQueue`
- `Guest Angle is still unstable for API > 35`

These warnings did not prevent boot or instrumentation tests.
