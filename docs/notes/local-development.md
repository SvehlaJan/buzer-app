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

### Fake-backend Android E2E

Use this as the default deterministic Android E2E lane:

```bash
scripts/e2e-fake-backend.sh
```

For a physical device, use the single-command wrapper. It performs the scoped
ADB reverse automatically when the app is configured for host loopback:

```bash
scripts/e2e-connected-device.sh
```

It safely auto-selects the sole connected device; set
`BUZER_DEVICE_SERIAL=<serial>` only when more than one device is attached.

The wrapper intentionally does not support a Tailscale HTTP override: the debug
network-security policy permits the host-loopback test address, not arbitrary
tailnet cleartext HTTP. Use its automatic ADB reverse path unless a separate
TLS/debug-network-security decision explicitly changes that policy.

`ANDROID_TEST_CLASS` can filter either form to one instrumentation class or
method. The wrapper also accepts a focused filter without another shell command:

```bash
scripts/e2e-connected-device.sh --class app.buzer.android.addtask.AddTaskFakeBackendTest
```

The wrapper is intentionally the only command that needs persistent device-test
approval in Codex.

The script starts `tools/fake-backend` on port `18433` when needed, runs `pixel8Api34DebugAndroidTest`, and passes the emulator URL `http://10.0.2.2:18433` into both the app and instrumentation helper.

### Local Real-Backend Smoke

This opt-in smoke uses live Todoist and must not be run casually. It starts the real backend locally with `WHATSAPP_DRY_RUN=true`, creates one assignable-project task and one non-assignable-project task through backend `POST /tasks`, verifies they appear in `/tasks/snapshot`, then deletes the created Todoist tasks directly through Todoist REST.

Create an ignored `backend/.env.local-smoke`:

```properties
NODE_ENV=development
PORT=18434
DATABASE_PATH=./data/buzer-local-smoke.db
ANDROID_API_TOKEN=replace-with-local-token
TODOIST_API_TOKEN=replace-with-real-token
TODOIST_USER_ID=replace-with-current-user-id
SMOKE_ASSIGNEE_ID=replace-with-current-user-id
WHATSAPP_RECIPIENT_ID=dry-run-recipient
WHATSAPP_DRY_RUN=true
WHATSAPP_SESSION_PATH=./whatsapp-sessions
WHATSAPP_CLIENT_ID=buzer-local-smoke
```

Run:

```bash
scripts/local-real-backend-smoke.mjs --i-understand-this-uses-live-todoist
```

The script refuses to run if `WHATSAPP_DRY_RUN` is not `true`. If cleanup fails, it prints the Todoist task IDs and titles for manual deletion.

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
