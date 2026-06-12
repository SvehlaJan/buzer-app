# Local Development Notes

Date: 2026-05-08

## Backend

The backend lives in `backend/`. It uses SQLite for MVP. Runtime databases and session folders are ignored by Git.

Expected local commands after backend setup:

```bash
cd backend
npm install
npm test
npm run dev
```

## Android Client

The client lives in `client/`. It is a Kotlin Multiplatform / Compose Multiplatform project with Android-native enforcement code.

Toolchain (since the 2026-06-08 latest-stable migration): Gradle 9.5.1, Android Gradle Plugin 9.2.1, Kotlin 2.4.0, Compose Multiplatform 1.11.1, `compileSdk = 36`. The shared module uses the `com.android.kotlin.multiplatform.library` plugin (not the legacy `com.android.library`).

Expected local commands after client setup:

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebug

# Or, for a concise low-token pass/fail summary of the JVM unit tests:
scripts/test.sh                 # androidApp debug unit tests
scripts/test.sh Coordinator     # filter to matching test classes
scripts/test.sh --shared        # also run shared host tests
```

Note: the shared JVM unit-test task is `:shared:testAndroidHostTest`. The pre-AGP-9 name `:shared:testDebugUnitTest` no longer exists; older plan/handoff docs that reference it are historical.

### Building from a git worktree

`client/local.properties` is gitignored, so a fresh `git worktree` (for example under `.worktrees/`) has no `sdk.dir` and Gradle fails with `SDK location not found`. Export the SDK location instead of editing `local.properties`:

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export ANDROID_SDK_ROOT=$HOME/Library/Android/sdk
```

### Instrumentation harnesses

- Managed device (API 34): `./gradlew pixel8Api34DebugAndroidTest` (defined in `client/androidApp/build.gradle.kts`).
- Android 16 / API 36 emulator: see `docs/notes/2026-06-08-android-16-emulator-setup.md` for the `Buzer_API_36` AVD and the reliable headless start command.

When more than one device/emulator is attached, scope a connected run with `ANDROID_SERIAL=<serial>` (for example `ANDROID_SERIAL=emulator-5584`); otherwise `connectedDebugAndroidTest` runs on every attached device.

### Device verification helper

`scripts/adb-verify.sh` wraps the common low-token device checks so verification output stays small and consistent (cleared + tag-filtered logcat, app-scoped `dumpsys alarm`, overlay/notification/exact-alarm app-op state, exit-checked downscaled screenshots). It resolves the target from `BUZER_DEVICE_SERIAL`, then `ANDROID_SERIAL`, then the sole attached device, and refuses to guess when several are connected.

```bash
scripts/adb-verify.sh devices
scripts/adb-verify.sh logcat-clear          # before triggering an action
BUZER_DEVICE_SERIAL=11141FDD4002R1 scripts/adb-verify.sh logcat 200
BUZER_DEVICE_SERIAL=11141FDD4002R1 scripts/adb-verify.sh boot-log
BUZER_DEVICE_SERIAL=11141FDD4002R1 scripts/adb-verify.sh alarms
BUZER_DEVICE_SERIAL=11141FDD4002R1 scripts/adb-verify.sh perms
BUZER_DEVICE_SERIAL=11141FDD4002R1 scripts/adb-verify.sh screenshot /tmp/buzer.png
BUZER_DEVICE_SERIAL=11141FDD4002R1 scripts/adb-verify.sh install
BUZER_DEVICE_SERIAL=11141FDD4002R1 scripts/adb-verify.sh health
```

Rationale: `docs/notes/2026-06-12-agent-token-efficient-android-testing.md`.

## Android Debug Backend Configuration

The Android debug app reads VPS backend settings from ignored `client/local.properties`:

```properties
BUZER_BACKEND_BASE_URL=https://your-vps.example
BUZER_BACKEND_TOKEN=your-dev-token
```

Do not commit real values. If either value is blank, the Android app shows a local configuration error instead of calling the backend.

## Secrets

Secrets stay out of Git. Use `.env.example` files for documented variable names only.

## Handoffs

Each implementation session writes a handoff in `docs/handoffs/` before stopping.
