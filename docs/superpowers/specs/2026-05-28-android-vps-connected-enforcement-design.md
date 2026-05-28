# Android VPS-Connected Enforcement MVP Design

Date: 2026-05-28

## Goal

Make the Android app usable against the VPS backend for the first real-device enforcement loop. The app fetches tasks from the backend, persists the latest snapshot locally, schedules exact local alarms, shows blocker activities, calls backend actions from blocker buttons, and visibly reports backend failures.

This is the next slice after the Android native enforcement foundation. It does not attempt full offline correctness or production onboarding.

## Scope

In scope:

- Debug-only backend configuration from ignored local Gradle properties.
- Backend snapshot fetch through the existing shared `BuzerApiClient`.
- App-private JSON file persistence for the latest `TasksSnapshotResponse`.
- Shared KMP alarm-planning logic that converts a snapshot into desired alarms.
- Android enforcement coordinator that fetches, persists, computes, and reconciles alarms.
- Main app refresh on open/resume.
- Blocker activity refresh before showing backend-dependent actions.
- Blocker action wiring to backend endpoints.
- Failure notifications plus local error log for blocker action failures.
- Start-postpone failure schedules a local 5-minute retry before hiding the blocker.
- Deadline action failures hide the blocker without scheduling a retry.

Out of scope:

- In-app setup UI for backend URL/token.
- QR or deep-link provisioning.
- Durable offline pending-action queue.
- Android Room/SQLite storage.
- Production-grade permission remediation flows.
- Push notifications.
- macOS reuse beyond keeping pure alarm planning in shared KMP code.

## Configuration

The Android debug build reads backend settings from `client/local.properties`:

```properties
BUZER_BACKEND_BASE_URL=https://example-vps
BUZER_BACKEND_TOKEN=replace-me
```

Gradle injects these into Android `BuildConfig` fields. Real values stay untracked. The repository documents the keys and fails clearly or shows an error state if they are missing.

This is intentionally a dev-mode path. A later setup flow can replace it with encrypted local storage.

## Shared Alarm Planning

The `shared` module owns pure scheduling decisions:

- `DesiredAlarm`
- `DesiredAlarmKind`
- `AlarmPlan`
- `AlarmPlanCalculator`

Input:

- `TasksSnapshotResponse`
- current time

Output:

- desired alarms to schedule
- task IDs whose alarms must be cancelled

Rules:

- Completed and deleted tasks produce no alarms and must cancel local alarms.
- Invalid assigned-to-user tasks are morning-review material, not per-task start/deadline blockers.
- Valid active tasks with `startAt` get:
  - pre-start warning 1 hour before `startAt`, when still in the future
  - start blocker at `startAt`, when still in the future
  - deadline blocker at `dueDatetime`, when still in the future
- Overdue or already-started tasks remain represented in the app snapshot, but this slice does not invent retroactive alarms. Morning review/app refresh can surface them.

The shared module must remain Android-free.

## Android Persistence

Android stores the latest snapshot as JSON in app-private files, for example:

```text
files/latest_snapshot.json
```

`SnapshotStore` responsibilities:

- save `TasksSnapshotResponse`
- load latest snapshot or return null
- tolerate missing files
- report corrupted JSON as a recoverable local error and allow overwriting on next successful fetch

The app does not store secrets in this snapshot file.

## Enforcement Coordinator

`EnforcementCoordinator` is Android-specific. It depends on:

- `BuzerApiClient`
- `SnapshotStore`
- `AlarmPlanCalculator`
- `AndroidAlarmScheduler`
- `BlockerErrorReporter`

Responsibilities:

- `refreshAndReconcile()`:
  - fetch snapshot from backend
  - save snapshot locally
  - compute alarm plan
  - cancel local alarms for completed/deleted tasks
  - schedule desired alarms
- `loadLatestSnapshot()`:
  - read the persisted snapshot for offline display/fallback
- blocker action methods:
  - start task
  - postpone start
  - complete task
  - extend deadline
  - reschedule task

On successful backend actions, the coordinator refreshes the snapshot and reconciles alarms.

## Main App Flow

On `MainActivity` open/resume:

1. Ensure notification channels exist.
2. Create backend API client from `BuildConfig`.
3. Call `refreshAndReconcile()`.
4. Render the latest available snapshot state.
5. If refresh fails, show the most recent local snapshot if available and report the failure.

If the app was opened by the boot receiver, it runs the same refresh/reconcile path. This upgrades the existing boot placeholder into real alarm restoration.

## Blocker Flow

Each blocker activity receives a task ID when applicable.

Before showing actions:

1. Load latest local snapshot immediately if available.
2. Attempt backend refresh.
3. If backend says the task is completed/deleted, cancel task alarms and finish.
4. Otherwise show the blocker using the freshest known task data.

Actions:

- Start blocker:
  - `I'm starting`: call backend `startTask`, then refresh/reconcile and finish.
  - `Postpone 5 min`: call backend `postponeStart`, then schedule according to refreshed backend state and finish.
- Deadline blocker:
  - `Done`: call backend `completeTask`, then cancel task alarms and finish.
  - `Extend 30 min`: call backend `extendTask`, then refresh/reconcile and finish.
  - `Reschedule`: call backend `rescheduleTask` after collecting exact deadline and duration, then refresh/reconcile and finish.
- Morning review:
  - This slice refreshes/reconciles and can show invalid/overdue tasks from snapshot, but deep fix/reassign/delete flows remain later work.
  - Snooze schedules a local 5-minute morning-review alarm.

## Failure Policy

Backend action failures must not trap the user inside a blocker.

For all blocker action failures:

- hide the blocker,
- emit a visible system notification,
- append an entry to a local error log,
- leave the backend-owned task state unchanged locally unless a subsequent refresh succeeds.

Start blocker special cases:

- `I'm starting` failure:
  - hide blocker,
  - do not mark local started,
  - do not schedule a retry.
- `Postpone 5 min` failure:
  - hide blocker,
  - schedule a local `START_BLOCKER` retry for 5 minutes later.

Deadline blocker special cases:

- `Done`, `Extend 30 min`, and `Reschedule` failures:
  - hide blocker,
  - do not schedule a local retry.

Backlog:

- A proper offline pending-action queue belongs in a later slice for durable retries, conflict resolution, and duplicate-escalation protection. This slice explicitly avoids that complexity.

## Error Reporting

`BlockerErrorReporter` records backend action failures in two places:

- a system notification that survives after the blocker hides,
- an app-private local error log file.

Minimum log fields:

- timestamp
- action
- task ID if available
- human-readable message

The notification text must be direct, for example:

```text
Buzer backend action failed. Check VPS/backend health.
```

The local error log is for development inspection and future UI surfacing.

## Testing

Automated tests:

- shared alarm planner tests:
  - schedules start/deadline/pre-start alarms for valid future tasks
  - skips completed/deleted tasks and returns cancellations
  - skips invalid tasks for per-task alarms
- Android unit tests:
  - snapshot store round-trip
  - corrupted snapshot read is recoverable
  - coordinator schedules alarms after successful refresh using fakes
  - coordinator cancels alarms for completed/deleted tasks
  - start-postpone failure schedules local 5-minute retry
  - deadline failure emits error without retry
  - error reporter writes local log entries

Manual checks:

- install debug APK on Android phone
- configure `client/local.properties`
- open app and verify snapshot fetch
- verify alarms are scheduled from a known backend task
- launch blocker activities via `adb`
- verify blocker actions call backend and hide
- stop backend and verify failure notification/local log behavior

## Open Risks

- Android full-screen behavior varies by OS version and device settings; physical-device verification is required.
- Exact alarm and notification permissions still need a better user remediation flow.
- Hardcoded debug config is acceptable for dev mode but must not become production setup.
- Without pending actions, backend downtime can still leave task state unresolved until refresh or morning review.
