# Native Android Emulator Testing Design

Date: 2026-06-01

## Goal

Replace the brittle host-driven ADB coordinate testing workflow with native Android instrumentation tests while keeping the local fake backend as the integration boundary.

The Android tests should run through Gradle, launch real Android activities, use Compose testing APIs for UI interaction, and talk to a fake backend running on the emulator host through `10.0.2.2`.

## Scope

In scope:

- AndroidX instrumented tests under `client/androidApp/src/androidTest`.
- Gradle Managed Device configuration for repeatable emulator runs.
- Compose UI testing for blocker screens and button actions.
- Host fake backend under `tools/fake-backend`.
- Android app under test configured with fake backend URL and token via Gradle properties.
- Android test helper code optionally configured with the fake backend inspection URL via instrumentation arguments.
- Clear documentation for running fake-backend-backed Android tests.

Out of scope for this slice:

- Replacing the backend with in-process fake Android dependencies.
- UI Automator tests for notifications, system permission dialogs, launcher widgets, exact alarms, boot receiver, or full-screen/lockscreen behavior.
- Live Todoist or WhatsApp mutation.
- Public CI setup.

## Test Architecture

The test stack has three layers:

1. **JVM tests** continue covering pure shared logic, scheduling, validation, reducers, and Android helper classes that do not need a device.
2. **Native Android instrumentation tests** run in a managed emulator and verify blocker activities against the host fake backend.
3. **Future platform tests** use UI Automator for system UI and platform behavior that Compose tests cannot own.

The first Android instrumentation suite should exercise real production code paths:

```text
ActivityScenario / AndroidJUnitRunner
  -> StartBlockerActivity / DeadlineBlockerActivity
  -> EnforcementDependencies
  -> BuzerApiClient
  -> http://10.0.2.2:18433
  -> tools/fake-backend
```

This intentionally keeps HTTP serialization, auth headers, activity launch, Compose rendering, and action wiring in the test path.

## Fake Backend

The fake backend remains a Node.js/TypeScript Fastify service in `tools/fake-backend`.

Required behavior:

- `GET /tasks/snapshot`
- `POST /tasks/:id/start`
- `POST /tasks/:id/done`
- `POST /tasks/:id/postpone-start`
- `POST /tasks/:id/extend`
- `POST /tasks/:id/reschedule`
- `GET /__calls`
- `POST /__reset`
- bearer auth with `test-token` for normal endpoints
- no bearer auth for `__` test-control endpoints

The Android emulator reaches the host fake backend through:

```text
http://10.0.2.2:18433
```

## Android Test Configuration

Android instrumentation tests must not depend on `client/local.properties`.

The debug APK can still receive backend config through Gradle properties:

```bash
./gradlew :androidApp:assembleDebug \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token
```

For native Android tests, the app under test receives backend config through `BuildConfig` generated from Gradle `-P` properties. Production `main` code must not depend on AndroidX Test APIs.

Expected command:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token
```

Instrumentation arguments may still be used by test-only helper code, for example to configure the test-side fake backend inspection client.

## Managed Device

Use Gradle Managed Devices for repeatable local emulator tests.

Initial device:

```text
pixel8Api34
```

Configuration:

- device: `Pixel 8`
- API: `34`
- system image source: `google`

Gradle should own device creation, app install, test APK install, execution, report generation, and teardown.

## First Tests

The first instrumentation tests should be small and action-oriented:

- `StartBlockerActivityFakeBackendTest`
  - reset fake backend calls
  - launch `StartBlockerActivity` with `task-start`
  - assert `I'm starting` and `Postpone 5 min` are displayed
  - click `I'm starting`
  - assert fake backend recorded `POST /tasks/task-start/start`

- `DeadlineBlockerActivityFakeBackendTest`
  - reset fake backend calls
  - launch `DeadlineBlockerActivity` with `task-deadline`
  - assert `Done`, `Extend 30 min`, and `Reschedule` are displayed
  - click `Done`
  - assert fake backend recorded `POST /tasks/task-deadline/done`

- `BlockerBackendFailureFakeBackendTest`
  - run fake backend in forced failure mode
  - click a blocker action
  - assert the activity dismisses
  - assert app-private error logging records the failure

## Tooling Decision

Use native Android tooling as the primary workflow:

- AndroidX Test
- AndroidJUnitRunner
- ActivityScenario
- Compose UI Test
- Gradle Managed Devices

Do not use host-side coordinate taps, `adb shell input tap`, or `android layout` parsing as the main verification path.

UI Automator is still appropriate later for system-level tests, but it is not needed for the first blocker action suite.

## Safety Rules

- Fake backend tests may perform start/done/extend/postpone/reschedule actions because they never touch Todoist or WhatsApp.
- Live backend tests must remain read-only.
- Do not print, commit, or embed the live bearer token in docs or source files.
- Keep `client/local.properties` ignored and private.
