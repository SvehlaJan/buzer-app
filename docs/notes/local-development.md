# Local Development Notes

Updated: 2026-11-11

## Backend

The backend lives in `backend/`. It uses SQLite for MVP. Runtime databases and session folders are ignored by Git.

Use Node.js 22 (the version used in CI). To verify the backend without live credentials:

```bash
cd backend
npm ci
npm test
npm run typecheck
npm run build
```

To run against your own Todoist account, copy `backend/.env.example` to `backend/.env`
and configure `TODOIST_API_TOKEN`, `TODOIST_USER_ID`, and `ANDROID_API_TOKEN`. Keep
`WHATSAPP_DRY_RUN=true` while developing, then run `npm run dev` from `backend/`.
Sentry can remain unconfigured. For a credential-free fixture server, see below.

## Android Client

The client lives in `client/`. It is a Kotlin Multiplatform / Compose Multiplatform project with Android-native enforcement code.

Use JDK 17, the checked-in Gradle wrapper, and the Android SDK platform required by
[`androidApp/build.gradle.kts`](../../client/androidApp/build.gradle.kts). Dependency
versions are in [`libs.versions.toml`](../../client/gradle/libs.versions.toml). The shared
module uses `com.android.kotlin.multiplatform.library`.

Expected local commands after client setup:

```bash
cd client
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebug

# Or, for a concise low-token pass/fail summary of the JVM unit tests:
../scripts/test.sh                 # androidApp debug unit tests
../scripts/test.sh Coordinator     # filter to matching test classes
../scripts/test.sh --shared        # also run shared host tests
```

Note: the shared JVM unit-test task is `:shared:testAndroidHostTest`. The pre-AGP-9 name `:shared:testDebugUnitTest` no longer exists; older plan/handoff docs that reference it are historical.

### Building from a git worktree

`client/local.properties` is gitignored, so a fresh `git worktree` (for example under `.worktrees/`) has no `sdk.dir` and Gradle fails with `SDK location not found`. Export the SDK location instead of editing `local.properties`:

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export ANDROID_SDK_ROOT=$HOME/Library/Android/sdk
```

### Instrumentation harnesses

- Managed device (API 34): `scripts/e2e-real-backend.sh` from the repository root configures and runs `pixel8Api34DebugAndroidTest`.
- Existing emulator: set `ANDROID_SERIAL` and `CONNECTED_DEVICE_BACKEND_URL=http://10.0.2.2:18433` when using the same script; see the example below. The [API 36 setup note](2026-06-08-android-16-emulator-setup.md) records AVD creation and startup commands; use this page for the current test harness.

When more than one device/emulator is attached, scope a connected run with `ANDROID_SERIAL=<serial>` (for example `ANDROID_SERIAL=emulator-5584`); otherwise `connectedDebugAndroidTest` runs on every attached device.

### Android E2E against the real backend

Use this as the default deterministic Android E2E lane:

```bash
scripts/e2e-real-backend.sh
```

It starts `backend/test/e2eServer.ts` on port `18433`, uses the production server and
SQLite with a simulated Todoist adapter and dry-run sender, and builds the app with
test configuration. It does not require real Todoist, WhatsApp, or Sentry credentials.
The old `tools/fake-backend` server and `e2e-fake-backend.sh` were removed.

For an already-running Android emulator:

```bash
ANDROID_SERIAL=emulator-5584 \
CONNECTED_DEVICE_BACKEND_URL=http://10.0.2.2:18433 \
scripts/e2e-real-backend.sh
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

The connected-device wrapper establishes an ADB reverse mapping and delegates to
the same real-backend harness.

### Explore the fixture app without live credentials

Start the fixture backend in one terminal:

```bash
cd backend
npm run e2e-server
```

With an Android emulator running, build and install the app from a second terminal:

```bash
cd client
./gradlew :androidApp:installDebug \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PSENTRY_ANDROID_DSN= \
  -PSENTRY_AUTH_TOKEN=
```

Open Buzer, complete the permission setup, and use the seeded tasks. The fixture
server keeps SQLite in memory; restarting it resets the backend data. If multiple
devices are attached, scope installation with `ANDROID_SERIAL`.

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

## Observability & Sentry Configuration

### Android Sentry Configuration
The Android app resolves Sentry configuration from a Gradle property, then
`client/local.properties`, then the environment. Use the latter two for local development:

```properties
SENTRY_ANDROID_DSN=https://<key>@<org>.ingest.sentry.io/<project-id>
SENTRY_ENVIRONMENT=development-device
SENTRY_ORG=jans-gi
SENTRY_PROJECT=buzer-android
```

- When `SENTRY_ANDROID_DSN` is empty or omitted, Sentry SDK initialization becomes a safe no-op while local Kermit/AppLog logging and diagnostics continue to function normally.
- In tests (`testDebugUnitTest`, `testAndroidHostTest`, `e2e-real-backend.sh`), Sentry calls use in-memory recording fakes and do not contact Sentry Cloud.

### Backend Sentry Configuration
The backend loads Sentry configuration from environment variables or `backend/.env`:

```dotenv
SENTRY_BACKEND_DSN=https://<key>@<org>.ingest.sentry.io/<project-id>
SENTRY_ENVIRONMENT=development-vps
SENTRY_ORG=jans-gi
SENTRY_PROJECT=buzer-backend
```

Leave `SENTRY_RELEASE` unset for ordinary local backend work so the backend uses its
`app.buzer@0.1.0+local` fallback. Android release values are always derived by Gradle from
its version name and `GITHUB_SHA` or the local Git SHA; setting `SENTRY_RELEASE` does not
change an Android build.

- When `SENTRY_BACKEND_DSN` is empty, backend Sentry initialization is a safe no-op.
- See `docs/notes/observability-operations.md` and `docs/runbooks/sentry-setup.md` for full operations specifications.

## Secrets

Secrets stay out of Git. Use `.env.example` files for documented variable names only.

## Handoffs

Each implementation session writes a handoff in `docs/handoffs/` before stopping.
