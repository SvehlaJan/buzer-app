# Local Backend And E2E Testing Design

Date: 2026-06-14

## Goal

Make Buzer verification faster and less fragile by adding a split testing workflow:

1. A deterministic Android fake-backend E2E lane for frequent regression coverage.
2. An opt-in local real-backend smoke lane that uses live Todoist with WhatsApp dry-run and automatic Todoist cleanup.

This design replaces ad hoc manual clicking as the default confidence path for Add Task and related backend request-shape behavior. Manual device checks remain for live alarms, lock-screen behavior, overlays, and other system surfaces that are not reliably covered by emulator UI tests.

## Non-Goals

- Do not add a debug-only backend cleanup endpoint.
- Do not make live Todoist tests part of the default regression suite.
- Do not require a WhatsApp Web session for local backend smoke.
- Do not replace final real-device alarm verification.
- Do not build broad CI infrastructure in this slice.

## Existing Context

The repo already has the core pieces needed:

- `tools/fake-backend` runs a Fastify fake backend on a local port.
- Android instrumentation tests use AndroidX Test and Compose UI Test.
- `client/androidApp/build.gradle.kts` defines `pixel8Api34` as a Gradle Managed Device.
- Instrumentation arguments already support `FAKE_BACKEND_BASE_URL`.
- Debug Android builds already receive backend config through Gradle properties or ignored `client/local.properties`.
- `backend` supports `WHATSAPP_DRY_RUN=true`, so local backend smoke can avoid WhatsApp Web session setup.
- `AddTaskFakeBackendTest` exists but predates mandatory project selection and project capability rules.

## Lane 1: Fake Backend E2E

Fake-backend E2E is the default automated regression lane.

It runs:

- `tools/fake-backend` on the Mac, default port `18433`.
- Android emulator or Gradle Managed Device.
- Android app configured with `BUZER_BACKEND_BASE_URL=http://10.0.2.2:18433`.
- Test helper configured with `FAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`.
- Non-secret token and assignee IDs, for example `test-token`, `user-me`, and `user-wife`.

Responsibilities:

- Exercise real Android UI and app network code.
- Assert request shape recorded by fake backend.
- Cover project picker, validation, retry, and backend failure behavior.
- Stay deterministic and independent of Todoist, WhatsApp, VPS, or device state.

Fake backend should remain production-contract-shaped enough to catch request-shape regressions. It may expose test helper endpoints such as `__reset`, `__calls`, and failure toggles because it is not production code.

## Lane 2: Local Real-Backend Smoke

Local real-backend smoke is an opt-in integration lane.

It runs:

- Real backend on the Mac on a local port, for example `18434`.
- Local SQLite database under ignored `backend/data/`.
- Real Todoist token and current user ID.
- `WHATSAPP_DRY_RUN=true`.

Responsibilities:

- Verify backend startup and `/health`.
- Verify Todoist project sync and `GET /projects`.
- Create project-aware tasks through the local backend HTTP API.
- Confirm backend snapshot sees the created commitments.
- Clean up all Todoist tasks created by the smoke run.

Cleanup:

- The smoke helper records Todoist task IDs created during the run.
- Cleanup uses Todoist REST directly from the smoke helper, not a backend debug endpoint.
- Cleanup must run in normal success and failure paths.
- If cleanup fails, the script must print exact task IDs and titles so they can be deleted manually.

The local-real lane may create real Todoist tasks automatically. It must use clearly identifiable titles, for example a `Buzer smoke <timestamp>` prefix. Android UI coverage stays in the fake-backend E2E lane; local-real smoke is specifically for backend, Todoist, and cleanup integration.

## Android User Flow Map

### Add Task: Project Loading

- Open app.
- Tap `Add task`.
- Projects load successfully.
- First open with no persisted project does not auto-select the first project.
- Save without selecting a project shows `Project is required`.
- After selecting a project and leaving/reopening the screen, that project is preselected if it still exists.
- Project load failure disables Save and shows an error.
- Retry after project load failure reloads projects.

### Add Task: Assignable Project

- Select a shared/assignable project.
- Assignee controls appear.
- Save without assignee shows `Assignee is required for this project`.
- Select `Me`, choose deadline, enter duration, and save.
- Android sends `projectId`, `assigneeId`, `dueDatetime`, and `durationMinutes`.
- Backend create failure keeps the form visible and preserves the entered title.
- Success returns to Home.
- Later snapshot refresh can show the created commitment.

### Add Task: Non-Assignable Project

- Select a personal/non-assignable project.
- Assignee controls are hidden.
- Deadline and duration remain mandatory.
- Save sends `projectId`, `dueDatetime`, and `durationMinutes`.
- Android omits `assigneeId`.
- Success returns to Home.
- Local real-backend smoke confirms the backend treats the task as the current user's commitment through companion ownership.

### Home And Snapshot

- Home refresh succeeds and shows snapshot counts.
- Home refresh failure shows a failure banner.
- Created task appears in snapshot after backend sync.
- Scheduled alarms exist for created commitments.
- Home in-progress task visibility is a backlog flow and should be covered later when the UI is implemented.

### Blockers And Actions

- Start blocker action calls backend.
- Deadline blocker done/extend/reschedule calls backend.
- Backend failure writes local error log.
- Duplicate lock-screen plus overlay start flow should not require a second start click after the overlay watcher work.
- Full lock-screen and overlay behavior remain mostly manual/device-smoke territory, with targeted instrumentation where feasible.

## Test Coverage Matrix

| Flow | Fake E2E Now | Local Real Smoke Now | Later |
| --- | --- | --- | --- |
| Add Task opens and projects load | Yes | Yes |  |
| First open requires explicit project selection | Yes |  |  |
| Persisted project preselects on reopen | Yes |  |  |
| Project load failure disables Save | Yes |  |  |
| Project load retry recovers | Yes |  |  |
| Assignable project requires assignee | Yes |  |  |
| Assignable create posts project and assignee | Yes | Yes |  |
| Non-assignable project hides assignee | Yes |  |  |
| Non-assignable create omits assignee | Yes | Yes |  |
| Backend create failure preserves form state | Yes |  |  |
| Real Todoist project sync |  | Yes |  |
| Real Todoist task cleanup |  | Yes |  |
| Created task appears in backend snapshot |  | Yes |  |
| Alarm scheduling after live create |  | Optional/manual | Yes |
| Full lock-screen alarm journey |  |  | Yes |
| Overlay duplicate-start regression |  |  | Yes |
| Home in-progress task visibility |  |  | Yes |
| Boot recovery and reschedule after reboot |  |  | Yes |
| WhatsApp escalation with real session |  |  | Yes |

## Script Design

### `scripts/e2e-fake-backend.sh`

Purpose: run deterministic Android E2E against `tools/fake-backend`.

Behavior:

- Check that Node dependencies and Android SDK are available.
- Start fake backend on port `18433` unless a compatible instance is already running.
- Wait for fake backend readiness by calling an endpoint such as `/tasks/snapshot` with bearer token or a future fake health endpoint.
- Run Gradle Managed Device or connected instrumentation with:
  - `-PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433`
  - `-PBUZER_BACKEND_TOKEN=test-token`
  - `-PBUZER_ASSIGNEE_ME_ID=user-me`
  - `-PBUZER_ASSIGNEE_WIFE_ID=user-wife`
  - `-PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433`
- Stop only the fake backend process it started.
- Print test report paths on failure.

Default command should target the managed device:

```bash
cd client
./gradlew pixel8Api34DebugAndroidTest \
  -PBUZER_BACKEND_BASE_URL=http://10.0.2.2:18433 \
  -PBUZER_BACKEND_TOKEN=test-token \
  -PBUZER_ASSIGNEE_ME_ID=user-me \
  -PBUZER_ASSIGNEE_WIFE_ID=user-wife \
  -PFAKE_BACKEND_BASE_URL=http://10.0.2.2:18433
```

The script may also support a connected-device mode later, but managed device is the default.

### `scripts/local-real-backend-smoke.sh`

Purpose: run or guide an opt-in smoke against a local real backend with live Todoist and WhatsApp dry-run.

Behavior:

- Require explicit opt-in, for example `--i-understand-this-uses-live-todoist`.
- Load an ignored env file, for example `backend/.env.local-smoke`.
- Validate required variables:
  - `ANDROID_API_TOKEN`
  - `TODOIST_API_TOKEN`
  - `TODOIST_USER_ID`
  - `WHATSAPP_RECIPIENT_ID`
  - `WHATSAPP_DRY_RUN=true`
  - `DATABASE_PATH`
  - `PORT`
- Refuse to run if `WHATSAPP_DRY_RUN=false`.
- Start backend on the configured port, or verify an already-running backend matches `/health`.
- Wait for `/health`.
- Wait for project sync by polling `GET /projects`.
- Choose one assignable project and one non-assignable project from `GET /projects`.
- Create one task in each project through local backend `POST /tasks`.
- Collect Todoist task IDs from the backend create responses.
- Poll `GET /tasks/snapshot` until the created commitments are visible or a timeout is reached.
- Delete created tasks directly through Todoist REST `DELETE /api/v1/tasks/{task_id}`.
- Print cleanup results.

The first implementation should fully automate backend startup checks, project discovery, backend task creation, snapshot verification, and cleanup. A later version may add an Android-against-local-real smoke if there is a clear value beyond the fake-backend UI E2E lane.

## Fake Backend Enhancements

To support the mapped flows, `tools/fake-backend` should add or tighten:

- `/projects` returns at least one assignable and one non-assignable project.
- `POST /tasks` records exact request body.
- Test helper endpoint to force project loading failure, for example `POST /__fail-projects`.
- `GET /projects` respects the project failure toggle.
- Existing `__calls`, `__reset`, and action-failure toggles remain.

Fake backend does not need to enforce every backend domain rule, but tests must assert the Android request body expected by production.

## Android Test Enhancements

Update `AddTaskFakeBackendTest` to cover project-aware flows:

- `addTask_requiresExplicitProjectSelectionOnFirstOpen`
- `addTask_sharedProjectRequiresAssigneeAndCreatesTask`
- `addTask_personalProjectOmitsAssignee`
- `addTask_projectLoadFailureDisablesSaveAndRetryRecovers`
- `addTask_backendFailureKeepsFormAndEnteredTitle`
- `addTask_reopensWithPersistedProjectSelected`

Keep tests black-box at the UI boundary where practical:

- Use Compose node text/content descriptions.
- Use fake backend calls as the network assertion boundary.
- Avoid coordinate-based ADB input.
- Use polling for recorded calls because app network work is asynchronous.

## Error Handling And Safety

- Fake E2E failures should leave fake backend logs/calls available in Gradle output or test failure messages.
- Local-real smoke must never run with WhatsApp live sending.
- Local-real smoke must clearly print that it uses live Todoist.
- Created live Todoist task titles must include a unique smoke run ID.
- Cleanup should run from traps/finally blocks.
- Cleanup failures must not be hidden.

## Documentation Updates

Update local development notes with:

- fake E2E command
- local real-backend smoke env template
- emulator networking through `10.0.2.2`
- physical device options:
  - `adb reverse tcp:<port> tcp:<port>` when ADB is connected
  - Mac LAN IP
  - Tailscale IP
- warning that local-real smoke touches live Todoist

## Acceptance Criteria

- One command runs fake backend Android E2E on the managed device.
- Fake E2E covers project-aware Add Task flows for assignable and non-assignable projects.
- Local real-backend smoke can run backend with WhatsApp dry-run and verify `/health` plus project availability.
- Local real-backend smoke documents or performs automatic cleanup of created Todoist tasks through Todoist REST.
- No production backend debug cleanup endpoint is added.
- Manual/live device verification remains documented for alarms, lock-screen, overlay, and WhatsApp real-session behavior.
