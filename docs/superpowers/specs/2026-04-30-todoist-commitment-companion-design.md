# Todoist Commitment Companion Design

Date: 2026-04-30

## Goal

Build a personal Todoist companion that forces strict task commitment without replacing Todoist as the shared task system. The first version targets one user, runs on an Android phone in developer mode, and uses a small VPS backend. Todoist remains the shared source for tasks with the user's wife.

The companion enforces that tasks assigned to the user have a precise deadline and expected duration, then uses local Android alarms and blocker screens to force action, postponement, extension, rescheduling, or social accountability.

## Scope

### MVP

- Android-first client built with Kotlin Multiplatform and Compose Multiplatform.
- Android-native enforcement for alarms, full-screen blockers, widgets, boot handling, and permission checks.
- Node.js/TypeScript backend on a VPS.
- SQLite database on the VPS.
- Todoist polling every 5 minutes.
- WhatsApp escalation through `whatsapp-web.js`.
- Frictionless Android widget for adding strict tasks.

### Out Of Scope For MVP

- macOS companion app.
- Calendar blocking.
- Todoist webhooks.
- FCM push invalidation.
- Voice assistant or dictation ingestion.
- Multi-user productization.

## Task Rules

Every task created through the companion must be assigned to someone before it can be saved.

Every task assigned to the user must have:

- title/content
- assignee
- exact Todoist `due_datetime`
- expected duration

Tasks assigned to the user's wife are not required to carry the user's commitment fields in the MVP.

If the user's wife assigns a task to the user without an exact deadline or duration, the task is invalid and appears in the morning review. The morning review must force the user to fix, reassign, complete, or otherwise resolve invalid assigned-to-user tasks.

Todoist owns task-visible data:

- title/content
- project
- assignee/responsible user
- exact deadline using `due_datetime`
- expected duration
- completion/deletion state

The companion backend owns enforcement-only data:

- lifecycle state
- `started_at`
- start dismiss count
- deadline extension count
- last snoozed time
- escalation sent timestamp
- last known schedule version

The MVP does not write started state, dismiss counts, or escalation audit data into Todoist.

## Task Lifecycle

The normal lifecycle is:

```text
planned
  -> pre_start_warning
  -> start_blocker_active
  -> started
  -> deadline_blocker_active
  -> completed
```

`start_at` is computed as:

```text
start_at = due_datetime - duration
```

Completion is dual-source:

- If the task is completed in Todoist, the backend detects it during polling and propagates the completed state to Android.
- If the user taps Done in the companion, the backend completes the Todoist task and marks local enforcement state completed.

Started state is companion-only. When the user taps "I'm starting", the backend stores `started_at`, the blocker hides, and Todoist is not modified.

## Android Client

The client is a Kotlin Multiplatform / Compose Multiplatform project. Shared Kotlin code contains models, validation, backend API client code, state machine logic, date/time calculations, and view-model logic where practical.

Android-specific code owns enforcement behavior:

- exact alarms through Android alarm APIs
- full-screen blocker activities
- notification channels
- app widget
- boot receiver for alarm rescheduling
- permission and battery optimization checks
- optional overlay, accessibility, or device-admin experiments if needed

The Android app talks only to the VPS backend. It does not directly call Todoist or WhatsApp. This keeps Todoist and WhatsApp credentials on the backend.

### Android Views

The Android app includes:

- home/today view
- add committed task flow
- morning review blocker
- start blocker
- deadline blocker
- setup/permission health screen
- WhatsApp/backend health screen or banner

The Android widget opens a minimal task creation flow where assignment, exact deadline, and duration are required when the task is assigned to the user.

## Enforcement Flow

### Morning Review

The morning review runs at a configured morning time and opens a full-screen blocker.

It shows:

- invalid tasks assigned to the user
- overdue tasks
- tasks starting today
- permission/backend/WhatsApp health warnings

Invalid assigned-to-user tasks must be fixed with exact deadline and duration, reassigned, completed, deleted, or otherwise resolved. Dismissing morning review snoozes it for 5 minutes. Morning dismissals are logged but do not trigger WhatsApp escalation in the MVP.

### Pre-Start Warning

The pre-start warning fires 1 hour before computed `start_at`.

It is a normal skippable notification and does not trigger escalation.

### Start Blocker

The start blocker fires at `due_datetime - duration`.

It is a full-screen annoying blocker with:

- primary action: "I'm starting"
- secondary action: "Postpone 5 min"

First postpone snoozes the blocker for 5 minutes. Second postpone triggers WhatsApp accountability through the backend and then continues the snooze/nag loop.

Tapping "I'm starting" stores companion-only started state and hides the blocker.

### Deadline Blocker

The deadline blocker fires at `due_datetime`.

It is a full-screen blocker with:

- "Done"
- "Extend 30 min"
- "Reschedule"

"Done" completes the Todoist task through the backend.

"Extend 30 min" pushes Todoist `due_datetime` forward by exactly 30 minutes and increments the local extension count. Extensions are unlimited in the MVP.

"Reschedule" requires a new exact deadline and new duration. The backend updates Todoist and recomputes `start_at`.

## Local Reliability

Android schedules exact local alarms from the latest backend task snapshot. These alarms are the authority for timing. Backend polling is a sync aid, not a timing source. FCM push notifications are a roadmap sync aid, not a timing source.

Android refreshes from the backend:

- on app open
- on app resume
- before showing any blocker
- after each blocker action
- after boot rescheduling

If the app receives evidence that a task was completed in Todoist, it cancels pending local alarms and dismisses related blockers.

## Backend

The backend is a Node.js/TypeScript service running on the user's VPS.

Responsibilities:

- authenticate the Android app
- poll Todoist every 5 minutes
- store local task mirror and enforcement state
- validate strict task rules
- create Todoist tasks
- complete, extend, and reschedule Todoist tasks
- receive Android lifecycle actions
- send WhatsApp escalation messages
- expose health/status information to Android

The backend exposes a REST API for Android.

## SQLite Storage

The MVP uses SQLite on the VPS. One backend process owns writes.

SQLite uses:

- WAL mode
- explicit migrations
- transactional updates around Todoist/action state changes

Core tables:

- `todoist_sync_state`
- `todoist_tasks`
- `commitment_states`
- `devices`
- `whatsapp_escalations`
- `backend_jobs` or equivalent retry queue

## Todoist Sync

The backend uses Todoist Sync API with incremental `sync_token`.

Initial sync fetches:

- active tasks
- projects
- collaborators
- assignments
- durations
- current user identity

Every 5 minutes the sync worker:

- fetches incremental Todoist changes
- updates the local task mirror
- detects completed, deleted, reassigned, or changed tasks
- validates strictness rules
- recomputes task schedule snapshots

Todoist webhooks are a roadmap item and not part of the MVP.

## Android API

Initial REST endpoints:

- `GET /tasks/snapshot`
- `POST /tasks`
- `POST /tasks/:id/start`
- `POST /tasks/:id/done`
- `POST /tasks/:id/postpone-start`
- `POST /tasks/:id/extend`
- `POST /tasks/:id/reschedule`
- `POST /review/dismiss`
- `POST /device/check-in`

`GET /tasks/snapshot` returns assigned-to-user strict tasks, invalid tasks, blocker state, health warnings, and a schedule version.

`POST /tasks` creates a Todoist task. Assignment is always required. Exact deadline and duration are required when the task is assigned to the user.

`POST /tasks/:id/start` stores companion-only started state.

`POST /tasks/:id/done` completes the Todoist task and marks companion state completed.

`POST /tasks/:id/postpone-start` increments start dismiss count. The second dismiss triggers WhatsApp escalation.

`POST /tasks/:id/extend` adds exactly 30 minutes to Todoist `due_datetime`.

`POST /tasks/:id/reschedule` requires new exact deadline and duration.

`POST /review/dismiss` snoozes morning review for 5 minutes.

`POST /device/check-in` reports Android app version, permission status, alarm health, and receives server-side warnings.

## WhatsApp Escalation

The backend uses `whatsapp-web.js` for MVP accountability messages.

The WhatsApp worker:

- runs headless Chromium/Puppeteer
- uses persistent auth such as `LocalAuth`
- exposes health status to the backend
- sends escalation messages after second start-postpone
- logs success or failure

This is an unofficial WhatsApp Web automation layer. It is acceptable for this personal dev-mode tool, but it must sit behind an internal provider interface so it can later be replaced with Telegram, SMS, email, WhatsApp Business API, or another channel.

Provider interface:

```ts
interface AccountabilitySender {
  sendEscalation(input: {
    taskTitle: string
    deadlineAt: string
    dismissCount: number
    recipient: string
  }): Promise<void>
}
```

## Failure Behavior

If Todoist API is down:

- Android still shows blockers from cached local schedules.
- Actions that require Todoist writes are accepted as pending only if the backend can safely retry them.
- The UI must clearly show pending sync state.

If the VPS is down:

- Android exact alarms still fire from cached data.
- Backend-required actions cannot complete silently.
- Blocker UI should make the failure visible and annoying.

If WhatsApp is disconnected:

- Backend logs escalation failure.
- Android still records second dismiss.
- Morning review and health surfaces show that WhatsApp is disconnected.

If Android permissions or battery settings are wrong:

- The app shows a persistent setup warning.
- Morning review includes permission health.
- Exact alarm, notification, overlay/accessibility, boot, and battery-optimization status are visible.

## Testing Strategy

Backend unit tests:

- strict task validation
- lifecycle transitions
- `start_at` and deadline calculations
- extend and reschedule behavior
- Todoist sync diff handling
- WhatsApp escalation trigger rules

Backend integration tests:

- SQLite migrations and repositories
- Todoist adapter with mocked API responses
- WhatsApp sender with mocked provider
- retry behavior for pending actions

Client shared tests:

- KMP state machine
- validation rules
- schedule calculations
- API DTO serialization

Android tests:

- Compose UI tests for add task, review, start blocker, and deadline blocker
- alarm scheduling wrapper tests where practical
- instrumentation/manual tests for device-level behavior

Manual device checklist:

- exact alarm fires while locked
- boot reschedules alarms
- pre-start warning appears
- first and second start postpone behavior works
- second postpone sends WhatsApp message
- Todoist completion cancels blocker after backend sync
- extend updates Todoist and reschedules deadline blocker
- reschedule requires new deadline and duration
- WhatsApp QR/session recovery works

## Roadmap

1. FCM push invalidation from VPS to Android.
2. Todoist webhooks.
3. Calendar blocking.
4. macOS companion using shared Kotlin/Compose logic where practical.
5. Voice/dictation ingestion, including Gemini or Google Tasks bridges.
6. Stronger Android lockdown modes using accessibility, overlay, or device-admin experiments.
7. Backend deployment automation from GitHub to the VPS. Preferred first version: a self-hosted GitHub Actions runner on the VPS that redeploys with `docker compose up -d --build` when pushes change `backend/**`, `docker-compose.yml`, or deployment files.

## Open Decisions

These are intentionally deferred:

- Whether morning review repeated dismissals should also trigger WhatsApp escalation.
- Whether repeated 30-minute extensions should later trigger accountability.
- Whether to migrate from SQLite to Postgres after MVP.
- Whether to add Firebase, Supabase, or another realtime layer after FCM/webhooks are evaluated.
